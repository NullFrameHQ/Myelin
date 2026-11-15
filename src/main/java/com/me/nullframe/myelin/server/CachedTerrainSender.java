/*
 * Copyright (C) 2026 Myelin contributors
 *
 * This file is part of Myelin.
 * Myelin is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3 only.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 * https://www.gnu.org/licenses/gpl-3.0.html
 */

package com.me.nullframe.myelin.server;

import com.me.nullframe.myelin.MyelinMod;
import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.chunk.SurfaceChunkCache;
import com.me.nullframe.myelin.server.scheduling.StripGeometry;
import com.me.nullframe.myelin.server.scheduling.StripSnapshotScheduler;

import com.me.nullframe.myelin.server.MyelinChunkManager.ChunkKey;
import com.me.nullframe.myelin.network.SurfaceChunkBatchS2CPacket;
import com.nullframe.core.StepTimings;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class CachedTerrainSender {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);
    private final MyelinConfig config;
    private final SurfaceChunkCache surfaceChunkCache;
    private final Map<UUID, StripSnapshotScheduler.PlayerWindow> playerWindows;
    private final Map<UUID, ServerPlayerEntity> onlinePlayers;
    private final ReentrantReadWriteLock publicationGate;
    private final LongSupplier surfaceCacheGeneration;
    private final BooleanSupplier closed;
    private final Predicate<ChunkKey> inPlayerWindow;

    // Maximum cached previews accumulated in one packet for a player.
    private static final int MAX_SURFACE_BATCH_SIZE = 512;


    private final Map<ChunkKey, SurfaceChunkSnapshot> readyToSend = new ConcurrentHashMap<>();
    private record CachedReplay(UUID player, StripSnapshotScheduler.Strip strip) {}
    private final Map<StripSnapshotScheduler.Strip, Set<UUID>> previewStripSubscribers = new ConcurrentHashMap<>();
    private final Map<CachedReplay, Long> cachedReplays = new ConcurrentHashMap<>();
    // A single sender thread owns preview batching.
    // Use the player map maintained by the server thread instead of reading vanilla's player map.
    private volatile Thread previewSenderThread;
    private volatile boolean previewSenderShutdown = false;

    // Group ready previews by distance to the nearest preview subscriber.
    // Reused buckets let each sweep send nearer terrain first without sorting every entry.
    private static final int DRAIN_BUCKETS = 128;

    /** One entry staged for a drain pass. Pooled and reused across sweeps. */
    private static final class DrainCandidate {
        ChunkKey key;
        SurfaceChunkSnapshot snapshot;
        DrainCandidate next;
    }

    // Sender thread only — never touched by the main thread.
    private final DrainCandidate[] drainBuckets = new DrainCandidate[DRAIN_BUCKETS];
    // Sender thread only: whether the last sweep sent or removed an entry.
    // If nothing changed, the sender backs off instead of repeatedly scanning the same work.
    private boolean productiveSweep = false;
    private static final long ORPHAN_BACKOFF_NANOS = 50_000_000L; // 50ms
    private final java.util.List<DrainCandidate> drainPool = new java.util.ArrayList<>();
    CachedTerrainSender(MyelinConfig config, SurfaceChunkCache surfaceChunkCache,
                        Map<UUID, StripSnapshotScheduler.PlayerWindow> playerWindows,
                        Map<UUID, ServerPlayerEntity> onlinePlayers,
                        ReentrantReadWriteLock publicationGate, LongSupplier surfaceCacheGeneration,
                        BooleanSupplier closed, Predicate<ChunkKey> inPlayerWindow) {
        this.config = config;
        this.surfaceChunkCache = surfaceChunkCache;
        this.playerWindows = playerWindows;
        this.onlinePlayers = onlinePlayers;
        this.publicationGate = publicationGate;
        this.surfaceCacheGeneration = surfaceCacheGeneration;
        this.closed = closed;
        this.inPlayerWindow = inPlayerWindow;
    }

    void snapshotReady(ChunkKey position, SurfaceChunkSnapshot value) {
        Set<UUID> subscribers = previewSubscribers(position);
        if (subscribers != null && !subscribers.isEmpty()) {
            readyToSend.put(position, value);
            wakePreviewSender();
        }
    }

    void invalidate() { readyToSend.clear(); }

    void requestShutdown() { previewSenderShutdown = true; }

    void awaitTermination(long timeoutMillis) throws InterruptedException {
        Thread sender = previewSenderThread;
        if (sender != null) sender.join(timeoutMillis);
    }

    void clear() {
        previewStripSubscribers.clear();
        cachedReplays.clear();
        readyToSend.clear();
    }

    void subscribeStrip(UUID player, StripSnapshotScheduler.Strip strip, long epoch, boolean preview) {
        if (!preview) return;
        previewStripSubscribers.computeIfAbsent(strip, unused -> ConcurrentHashMap.newKeySet()).add(player);
        cachedReplays.put(new CachedReplay(player, strip), epoch);
        wakePreviewSender();
    }
    void unsubscribeStrip(UUID player, StripSnapshotScheduler.Strip strip, long epoch) {
        Set<UUID> subscribers = previewStripSubscribers.get(strip);
        if (subscribers != null) {
            subscribers.remove(player);
            if (subscribers.isEmpty()) previewStripSubscribers.remove(strip, subscribers);
        }
        cachedReplays.remove(new CachedReplay(player, strip), epoch);
    }
    private Set<UUID> previewSubscribers(ChunkKey key) {
        return previewStripSubscribers.get(new StripSnapshotScheduler.Strip(key.dimensionId(),
                Math.floorDiv(key.chunkX(), StripGeometry.WIDTH), Math.floorDiv(key.chunkZ(), StripGeometry.HEIGHT)));
    }

    private void drainCachedReplays() {
        int handled = 0;
        for (Map.Entry<CachedReplay, Long> entry : cachedReplays.entrySet()) {
            if (!cachedReplays.remove(entry.getKey(), entry.getValue())) continue;
            CachedReplay replay = entry.getKey();
            StripSnapshotScheduler.PlayerWindow window = playerWindows.get(replay.player());
            if (window == null || window.epoch() != entry.getValue() || !window.eligible() || !window.preview()) continue;
            StripSnapshotScheduler.Strip strip = replay.strip();
            for (int z = 0; z < StripGeometry.HEIGHT; z++) for (int x = 0; x < StripGeometry.WIDTH; x++) {
                ChunkKey position = new ChunkKey(strip.dimension(), strip.x() + x, strip.z() + z);
                if (!canSend(window, position)) continue;
                SurfaceChunkSnapshot value = surfaceChunkCache.get(position.dimensionId(), position.chunkX(), position.chunkZ());
                if (value != null) readyToSend.put(position, value);
            }
            if (++handled == 2) break;
        }
    }

    void cleanupMemoryLeaks() {
        readyToSend.entrySet().removeIf(entry -> previewSubscribers(entry.getKey()) == null
                && !inPlayerWindow.test(entry.getKey()));
    }

    private void sendReadySnapshotsFromSender() {
        Arrays.fill(drainBuckets, null);
        int used = 0;
        productiveSweep = false;
        for (Map.Entry<ChunkKey, SurfaceChunkSnapshot> entry : readyToSend.entrySet()) {
            ChunkKey key = entry.getKey();
            SurfaceChunkSnapshot value = entry.getValue();
            if (surfaceChunkCache.get(key.dimensionId(), key.chunkX(), key.chunkZ()) != value
                    || !inPlayerWindow.test(key)) {
                readyToSend.remove(key, value);
                productiveSweep = true;
                continue;
            }
            int nearest = DRAIN_BUCKETS - 1;
            for (StripSnapshotScheduler.PlayerWindow window : playerWindows.values()) {
                if (window.eligible() && window.preview() && window.dimension() == key.dimensionId()) {
                    nearest = Math.min(nearest, chebyshevDistance(key.chunkX(), key.chunkZ(), window.chunkX(), window.chunkZ()));
                }
            }
            DrainCandidate candidate;
            if (used < drainPool.size()) candidate = drainPool.get(used);
            else { candidate = new DrainCandidate(); drainPool.add(candidate); }
            candidate.key = key;
            candidate.snapshot = value;
            candidate.next = drainBuckets[nearest];
            drainBuckets[nearest] = candidate;
            used++;
        }
        for (DrainCandidate head : drainBuckets) for (DrainCandidate candidate = head; candidate != null; candidate = candidate.next) {
            ChunkKey key = candidate.key;
            SurfaceChunkSnapshot value = candidate.snapshot;
            Set<UUID> subscribers = previewSubscribers(key);
            if (subscribers != null) for (UUID playerId : subscribers) {
                StripSnapshotScheduler.PlayerWindow window = playerWindows.get(playerId);
                if (!canSend(window, key)) continue;
                ServerPlayerEntity player = onlinePlayers.get(playerId);
                if (player == null) continue;
                SurfaceBatch batch = surfaceBatches.computeIfAbsent(playerId, unused -> new SurfaceBatch());
                if (batch.chunks.isEmpty()) batch.firstAddNanos = System.nanoTime();
                batch.chunks.add(value);
                if (batch.chunks.size() >= MAX_SURFACE_BATCH_SIZE) {
                    flushSurfaceBatch(player, batch.chunks);
                    surfaceBatches.remove(playerId, batch);
                }
            }
            readyToSend.remove(key, value);
            productiveSweep = true;
        }
        for (int i = 0; i < used; i++) {
            DrainCandidate candidate = drainPool.get(i);
            candidate.key = null; candidate.snapshot = null; candidate.next = null;
        }
    }

    private boolean canSend(StripSnapshotScheduler.PlayerWindow window, ChunkKey key) {
        return !closed.getAsBoolean() && window != null && window.eligible() && window.preview()
                && window.epoch() == surfaceCacheGeneration.getAsLong() && window.dimension() == key.dimensionId()
                && window.range().contains(Math.floorDiv(key.chunkX(), StripGeometry.WIDTH),
                        Math.floorDiv(key.chunkZ(), StripGeometry.HEIGHT));
    }

    /**
     * Flushes partial batches after {@link #BATCH_LINGER_NANOS}.
     * Call on every sender-loop iteration, even when no new snapshots are ready.
     * Only the sender thread may access or flush these batches.
     */
    private void flushAgedBatches() {
        long nowNanos = System.nanoTime();
        for (java.util.Map.Entry<UUID, SurfaceBatch> e : surfaceBatches.entrySet()) {
            SurfaceBatch b = e.getValue();
            if (b.chunks.isEmpty()) {
                surfaceBatches.remove(e.getKey(), b);
                continue;
            }
            if (nowNanos - b.firstAddNanos >= BATCH_LINGER_NANOS) {
                ServerPlayerEntity player = onlinePlayers.get(e.getKey());
                if (player != null) {
                    flushSurfaceBatch(player, b.chunks);
                }
                surfaceBatches.remove(e.getKey(), b);
            }
        }
    }

    /** Sends one batched packet for the given player's accumulated chunks. */
    private void flushSurfaceBatch(ServerPlayerEntity player, java.util.List<SurfaceChunkSnapshot> batch) {
        if (closed.getAsBoolean() || !config.enabled || batch == null || batch.isEmpty()) return;
        publicationGate.readLock().lock();
        try {
            StripSnapshotScheduler.PlayerWindow window = playerWindows.get(player.getUuid());
            if (window == null || !window.preview() || !window.eligible()) return;
            long started = System.nanoTime();
            List<SurfaceChunkBatchS2CPacket.ChunkEntry> entries = new ArrayList<>();
            for (SurfaceChunkSnapshot value : batch) {
                ChunkKey key = new ChunkKey(value.getDimensionId(), value.getChunkX(), value.getChunkZ());
                if (!canSend(window, key) || surfaceChunkCache.get(key.dimensionId(), key.chunkX(), key.chunkZ()) != value) continue;
                entries.add(new SurfaceChunkBatchS2CPacket.ChunkEntry(value.getChunkX(), value.getChunkZ(),
                        value.topHeightsView(), value.surfaceTypesView()));
                if (entries.size() == SurfaceChunkBatchS2CPacket.MAX_CHUNKS_PER_BATCH) break;
            }
            StepTimings.record(StepTimings.Step.PACKET_BUILD, started);
            if (!entries.isEmpty()) {
                started = System.nanoTime();
                try {
                    ServerPlayNetworking.send(player, new SurfaceChunkBatchS2CPacket(window.dimension(),
                            entries.toArray(SurfaceChunkBatchS2CPacket.ChunkEntry[]::new)));
                } catch (RuntimeException disconnected) {
                    LOGGER.debug("[Myelin] Preview batch could not be sent to {}", player.getUuid(), disconnected);
                }
                StepTimings.record(StepTimings.Step.SEND, started);
            }
        } finally { publicationGate.readLock().unlock(); }
    }

    /**
     * Briefly collect streamed results into per-player batches.
     * Full batches send immediately; the sender loop also checks partial batches for expiry.
     */
    private static final long BATCH_LINGER_NANOS = 2_000_000L; // 2ms
    private final java.util.concurrent.ConcurrentHashMap<UUID, SurfaceBatch> surfaceBatches =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class SurfaceBatch {
        final java.util.List<SurfaceChunkSnapshot> chunks = new java.util.ArrayList<>(256);
        long firstAddNanos;
    }

    /** Drains ready previews, flushes aged batches, and parks when there is nothing to send. */
    private void previewSenderLoop() {
        while (!previewSenderShutdown && config.enabled) {
            drainCachedReplays();
            boolean hadWork = !readyToSend.isEmpty();
            if (hadWork) {
                sendReadySnapshotsFromSender();
            }

            // Flush partial batches even when no new results arrive.
            flushAgedBatches();

            if (hadWork) {
                if (productiveSweep) {
                    // Briefly yield between productive sweeps.
                    LockSupport.parkNanos(250_000L);
                } else {
                    // Back off when the sweep could neither send nor remove anything.
                    LockSupport.parkNanos(ORPHAN_BACKOFF_NANOS);
                }
            } else if (surfaceBatches.isEmpty() && cachedReplays.isEmpty()) {
                // Nothing queued, nothing aging: park until explicitly woken.
                LockSupport.park();
            } else {
                // Check aging partial batches again after a short wait.
                LockSupport.parkNanos(1_000_000L);
            }
        }
    }

    void ensureStarted() {
        if (closed.getAsBoolean()) return;
        if (!config.enabled) return;
        if (previewSenderThread == null) {
            Thread t = new Thread(this::previewSenderLoop, "myelin-preview-sender");
            t.setDaemon(true);
            t.start();
            previewSenderThread = t;
        }
    }

    void wakePreviewSender() {
        Thread t = previewSenderThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private int chebyshevDistance(int chunkX, int chunkZ, int otherChunkX, int otherChunkZ) {
        return Math.max(Math.abs(chunkX - otherChunkX), Math.abs(chunkZ - otherChunkZ));
    }

}
