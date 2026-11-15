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

package com.me.nullframe.myelin.server.chunk;

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.server.MyelinChunkManager;
import com.me.nullframe.myelin.server.scheduling.SnapshotWorkRegistry;
import com.me.nullframe.myelin.server.scheduling.StripSnapshotScheduler;

import com.me.nullframe.myelin.server.MyelinChunkManager.ChunkKey;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class SurfaceChunkLifecycle {
    private final StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler;
    private final ReentrantReadWriteLock publicationGate;
    private final LongSupplier surfaceCacheGeneration;
    private final BooleanSupplier closed;
    private final BooleanSupplier surfaceModeActive;

    private static final class TrackedTemporaryTopOnlyChunk extends WeakReference<Chunk> {
        private final long generation;
        private TrackedTemporaryTopOnlyChunk(Chunk chunk, long generation, ReferenceQueue<Chunk> queue) {
            super(chunk, queue);
            this.generation = generation;
        }
    }

    private static final class ResidentChunkToken extends WeakReference<Chunk> {
        final ChunkKey position;
        final long epoch;
        ResidentChunkToken(ChunkKey position, Chunk chunk, long epoch, ReferenceQueue<Chunk> queue) {
            super(chunk, queue); this.position = position; this.epoch = epoch;
        }
    }
    private final Map<ChunkKey, ResidentChunkToken> residentChunks = new ConcurrentHashMap<>();
    private final ReferenceQueue<Chunk> residentChunkQueue = new ReferenceQueue<>();
    private final Map<ChunkKey, TrackedTemporaryTopOnlyChunk> temporaryTopOnlyChunks = new ConcurrentHashMap<>();
    private final ReferenceQueue<Chunk> temporaryTopOnlyChunkQueue = new ReferenceQueue<>();
    private final Set<ChunkKey> topOnlyChunkKeys = ConcurrentHashMap.newKeySet();
    // Unload events precede vanilla's final save. Keep protection for the old
    // instance until GC; a replacement chunk at the same position may be real.
    private final Set<Chunk> discardedTemporaryTopOnlyChunks = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    public SurfaceChunkLifecycle(StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler,
                          ReentrantReadWriteLock publicationGate, LongSupplier surfaceCacheGeneration,
                          BooleanSupplier closed, BooleanSupplier surfaceModeActive) {
        this.stripScheduler = stripScheduler;
        this.publicationGate = publicationGate;
        this.surfaceCacheGeneration = surfaceCacheGeneration;
        this.closed = closed;
        this.surfaceModeActive = surfaceModeActive;
    }

    public Set<ChunkKey> trackedChunkKeys() { return topOnlyChunkKeys; }

    public void clearResidency() { residentChunks.clear(); }

    public boolean isTemporaryTopOnlyChunk(ServerWorld world, int chunkX, int chunkZ) {
        // Ignore old position tracking when no player is eligible for Surface generation.
        if (!surfaceModeActive.getAsBoolean()) {
            return false;
        }
        ChunkKey key = new ChunkKey(DimensionIds.fromWorld(world), chunkX, chunkZ);
        return MyelinChunkTracking.isTemporaryTopOnlyChunk(world, chunkX, chunkZ)
                || MyelinChunkTracking.shouldUseTopOnlyGeneration(world, chunkX, chunkZ)
                || topOnlyChunkKeys.contains(key)
                || getTrackedTemporaryTopOnlyChunk(key) != null;
    }

    public boolean shouldSkipSavingTemporaryTopOnlyChunk(ServerWorld world, Chunk chunk) {
        if (chunk == null) return false;
        if (discardedTemporaryTopOnlyChunks.contains(chunk)) return true;

        ChunkKey key = new ChunkKey(DimensionIds.fromWorld(world), chunk.getPos().x, chunk.getPos().z);
        if (topOnlyChunkKeys.contains(key)
                || MyelinChunkTracking.shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z)
                || MyelinChunkTracking.isTemporaryTopOnlyChunk(world, chunk.getPos().x, chunk.getPos().z)) {
            return true;
        }
        TrackedTemporaryTopOnlyChunk trackedChunk = getTrackedTemporaryTopOnlyChunk(key);
        return trackedChunk != null && trackedChunk.get() == chunk;
    }

    /**
     * Checks position tracking for a Surface chunk without needing the chunk object.
     * Used by the unload mixin.
     */
    public boolean isFakeChunk(ServerWorld world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(DimensionIds.fromWorld(world), chunkX, chunkZ);
        if (topOnlyChunkKeys.contains(key)) return true;
        if (MyelinChunkTracking.shouldUseTopOnlyGeneration(world, chunkX, chunkZ)) return true;
        if (MyelinChunkTracking.isTemporaryTopOnlyChunk(world, chunkX, chunkZ)) return true;
        return false;
    }

    public void markResident(ServerWorld world, Chunk chunk, long epoch) {
        ChunkKey position = new ChunkKey(DimensionIds.fromWorld(world), chunk.getPos().x, chunk.getPos().z);
        ResidentChunkToken token = new ResidentChunkToken(position, chunk, epoch, residentChunkQueue);
        residentChunks.put(position, token);
        stripScheduler.markResident(new SnapshotWorkRegistry.Key(epoch, position.dimensionId(),
                position.chunkX(), position.chunkZ()), token);
    }

    public void onChunkLoad(ServerWorld world, Chunk chunk) {
        if (closed.getAsBoolean()) return;
        ChunkKey position = new ChunkKey(DimensionIds.fromWorld(world), chunk.getPos().x, chunk.getPos().z);
        TrackedTemporaryTopOnlyChunk temporary = temporaryTopOnlyChunks.get(position);
        long generation = temporary == null ? surfaceCacheGeneration.getAsLong() : temporary.generation;
        if (temporary != null) {
            // A WorldChunk replaces its ProtoChunk; preserve save identity and the
            // quality that actually built it, rather than assigning current quality.
            temporaryTopOnlyChunks.replace(position, temporary,
                    new TrackedTemporaryTopOnlyChunk(chunk, generation, temporaryTopOnlyChunkQueue));
        }
        markResident(world, chunk, generation);
    }

    public void onChunkUnload(ServerWorld world, Chunk chunk) {
        if (world == null || chunk == null) return;
        ChunkKey key = new ChunkKey(DimensionIds.fromWorld(world), chunk.getPos().x, chunk.getPos().z);
        ResidentChunkToken resident = residentChunks.get(key);
        if (resident != null && resident.get() != null && resident.get() != chunk) return;
        if (resident != null && residentChunks.remove(key, resident)) {
            stripScheduler.unload(new SnapshotWorkRegistry.Key(resident.epoch, key.dimensionId(), key.chunkX(), key.chunkZ()), resident);
        }
        TrackedTemporaryTopOnlyChunk temporary = temporaryTopOnlyChunks.get(key);
        if (temporary != null && temporary.get() != null && temporary.get() != chunk) return;
        if (shouldSkipSavingTemporaryTopOnlyChunk(world, chunk)) {
            discardedTemporaryTopOnlyChunks.add(chunk);
            chunk.setNeedsSaving(false);
        }
        topOnlyChunkKeys.remove(key);
        if (temporary != null) temporaryTopOnlyChunks.remove(key, temporary);
        MyelinChunkTracking.clearMyelinTrackingForChunk(world, key.chunkX(), key.chunkZ());
    }

    public void markTemporaryTopOnlyChunk(ServerWorld world, Chunk chunk, long generation) {
        publicationGate.readLock().lock();
        try {
            if (closed.getAsBoolean()) throw new java.util.concurrent.CancellationException("Myelin closed");
            ChunkKey key = new ChunkKey(DimensionIds.fromWorld(world), chunk.getPos().x, chunk.getPos().z);
            topOnlyChunkKeys.add(key);
            MyelinChunkTracking.registerTopOnlyChunk(world, chunk.getPos().x, chunk.getPos().z);
            MyelinChunkTracking.registerTemporaryTopOnlyChunk(world, chunk.getPos().x, chunk.getPos().z);
            temporaryTopOnlyChunks.put(key,
                    new TrackedTemporaryTopOnlyChunk(chunk, generation, temporaryTopOnlyChunkQueue));
        } finally { publicationGate.readLock().unlock(); }
    }

    public void purgeCollectedTemporaryTopOnlyChunks() {
        // Server-thread housekeeping is bounded even after a large collection.
        for (int checked = 0; checked < 512; checked++) {
            ResidentChunkToken resident = (ResidentChunkToken) residentChunkQueue.poll();
            if (resident == null) break;
            if (residentChunks.remove(resident.position, resident))
                stripScheduler.unload(new SnapshotWorkRegistry.Key(resident.epoch, resident.position.dimensionId(),
                        resident.position.chunkX(), resident.position.chunkZ()), resident);
        }
        for (int checked = 0; checked < 512; checked++) {
            TrackedTemporaryTopOnlyChunk collected = (TrackedTemporaryTopOnlyChunk) temporaryTopOnlyChunkQueue.poll();
            if (collected == null) break;
            // Save identity and its quality survive ProtoChunk collection: its
            // replacement WorldChunk may not have fired CHUNK_LOAD yet. Only
            // residency is released by GC; actual unload releases save tracking.
        }
    }

    private TrackedTemporaryTopOnlyChunk getTrackedTemporaryTopOnlyChunk(ChunkKey key) {
        // The server tick handles reference-queue cleanup; this lookup only checks the entry.
        TrackedTemporaryTopOnlyChunk trackedChunk = temporaryTopOnlyChunks.get(key);
        if (trackedChunk == null) return null;
        if (trackedChunk.get() != null) return trackedChunk;
        return null;
    }

}
