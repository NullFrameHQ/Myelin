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

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.MyelinMod;
import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.chunk.SurfaceChunkBuilder;
import com.me.nullframe.myelin.server.chunk.SurfaceChunkCache;
import com.me.nullframe.myelin.server.chunk.SurfaceChunkLifecycle;
import com.me.nullframe.myelin.server.diagnostics.MyelinDiagnostics;
import com.me.nullframe.myelin.server.diagnostics.SurfaceCacheTrace;
import com.me.nullframe.myelin.server.player.MyelinPlayerManager;
import com.me.nullframe.myelin.server.scheduling.NativeWorkDispatcher;
import com.me.nullframe.myelin.server.scheduling.SnapshotWorkRegistry;
import com.me.nullframe.myelin.server.scheduling.StripSnapshotScheduler;

import com.nullframe.core.NullFrameAPI;
import com.nullframe.core.StepTimings;
import com.nullframe.core.cubiomes.Cubiomes;
import com.nullframe.core.cubiomes.NativeChunkOptions;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import com.me.nullframe.myelin.mixin.ChunkAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MyelinChunkManager {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);

    public enum SnapshotLookupKind {
        CACHE_HIT, READY_PENDING, UNFINISHED_PENDING, NO_USABLE_PENDING
    }

    private record SnapshotLookup(SurfaceChunkSnapshot snapshot, SnapshotLookupKind kind,
                                  long epoch) {}
    private volatile int clientBiomeStep;
    private volatile int clientCorrectionAmp = 4;
    private record GenerationSettings(int samplingStep, int biomeStep, int surfaceChunkDepth,
                                      boolean regionMode, boolean addVegetation, boolean fillBottom, int bottomY, int topYExclusive) {}
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();
    private final long sessionId = SESSION_SEQUENCE.incrementAndGet();
    private volatile boolean closed;
    private final java.util.concurrent.locks.ReentrantReadWriteLock publicationGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    // Server-thread only; workers receive captured settings rather than live worlds.
    private final Map<Integer, ServerWorld> sessionWorlds = new HashMap<>();
    private final SnapshotWorkRegistry<SurfaceChunkSnapshot> positionRegistry;
    private final StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler;

    private final MyelinDiagnostics diagnostics;
    private final SurfaceCacheTrace surfaceCacheTrace = new SurfaceCacheTrace();
    private final AtomicLong surfaceCacheGeneration = new AtomicLong(sessionId << 32);

    private static final int cores = Runtime.getRuntime().availableProcessors();

    // Keep the configured worker count; strip geometry is independent of it.
    private static int SURFACE_SNAPSHOT_WORKERS = -1;
    private static void resolveWorkerCount(MyelinConfig config) {
        int cfg = (config != null) ? config.workerThreads : -1;
        if (cfg == -1) {
            SURFACE_SNAPSHOT_WORKERS = Math.max(2, Math.min(20, cores / 2 - 1));
        } else {
            SURFACE_SNAPSHOT_WORKERS = Math.max(0, Math.min(20, cfg));
        }
    }
    public record ChunkKey(int dimensionId, int chunkX, int chunkZ) {}

    private final SurfaceChunkLifecycle surfaceChunkLifecycle;
    private final SurfaceChunkBuilder surfaceChunkBuilder;

    private final MyelinPlayerManager myelinPlayerManager;
    private final CachedTerrainSender cachedTerrainSender;

    private final Cubiomes    cubiomes;
    private final NativeWorkDispatcher cubiomesExecutor;
    private final MyelinConfig  config;
    private final SurfaceChunkCache surfaceChunkCache;

    long tickCounter;

    public MyelinChunkManager(MyelinConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MyelinConfig cannot be null");
        }

        this.config = config;
        resolveWorkerCount(config);
        LOGGER.info("[Myelin] Worker threads: {} (cores: {})", SURFACE_SNAPSHOT_WORKERS, cores);
        this.cubiomes = NullFrameAPI.getInstance().cubiomes();
        this.surfaceChunkCache = new SurfaceChunkCache(config.cacheMaxEntries,
                config.cacheTtlSeconds * 1000L, this.surfaceCacheTrace);

        AtomicInteger workerId = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(() -> {
                try { r.run(); }
                finally { Cubiomes.releaseBuiltChunkBuffer(); }
            }, "myelin-cubiomes-worker-" + workerId.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.cubiomesExecutor = new NativeWorkDispatcher(SURFACE_SNAPSHOT_WORKERS, factory, () -> {});
        this.positionRegistry = new SnapshotWorkRegistry<>(surfaceCacheGeneration.get());
        this.stripScheduler = new StripSnapshotScheduler<>(sessionId, positionRegistry, cubiomesExecutor, new SchedulerBackend());
        this.cubiomesExecutor.setWakeup(stripScheduler::wakeup);
        this.surfaceChunkLifecycle = new SurfaceChunkLifecycle(stripScheduler,
                publicationGate, surfaceCacheGeneration::get, () -> closed, this::anyPlayerInSurfaceMode);
        this.myelinPlayerManager = new MyelinPlayerManager(config, stripScheduler,
                surfaceCacheGeneration::get, () -> tickCounter, () -> closed,
                () -> SURFACE_SNAPSHOT_WORKERS, this::settings);
        this.surfaceChunkCache.setEvictionPreference(snapshot -> !isWithinAnyPlayerWindow(
                snapshot.getDimensionId(), snapshot.getChunkX(), snapshot.getChunkZ()));
        this.surfaceChunkCache.setListener(new SurfaceChunkCache.Listener() {
            @Override public void inserted(SurfaceChunkCache.EntryToken entry) { }
            @Override public void removed(SurfaceChunkCache.EntryToken entry, SurfaceChunkCache.RemovalReason reason) {
                SurfaceChunkSnapshot value = entry.snapshot();
                stripScheduler.availabilityLost(registryKey(value.getDimensionId(), value.getChunkX(), value.getChunkZ()), value);
            }
        });
        this.diagnostics = new MyelinDiagnostics(config, cubiomes, cubiomesExecutor,
                stripScheduler, surfaceChunkCache, surfaceCacheTrace,
                () -> closed, () -> SURFACE_SNAPSHOT_WORKERS);
        this.cachedTerrainSender = new CachedTerrainSender(config, surfaceChunkCache,
                myelinPlayerManager.windows(), myelinPlayerManager.onlinePlayers(), publicationGate, surfaceCacheGeneration::get,
                () -> closed, key -> isWithinAnyPlayerWindow(key.dimensionId(), key.chunkX(), key.chunkZ()));
        this.surfaceChunkBuilder = new SurfaceChunkBuilder(config, surfaceChunkLifecycle,
                () -> closed, surfaceCacheGeneration::get);
    }

    /** Pre-warms each persistent native worker before ordinary generation admission. */
    public void prewarmCubiomesCache() {
        if (!config.enabled || SURFACE_SNAPSHOT_WORKERS <= 0 || !Cubiomes.isNativeAvailable()) return;

        LOGGER.info("[Myelin] Pre-warming TLS cache on {} worker threads...", SURFACE_SNAPSHOT_WORKERS);
        try {
            cubiomesExecutor.runExclusive("prewarm", () -> {
                int workers = cubiomesExecutor.workerCount();
                CountDownLatch warmed = new CountDownLatch(workers);
                List<CompletableFuture<Void>> jobs = new ArrayList<>(workers);
                for (int i = 0; i < workers; i++) {
                    jobs.add(cubiomesExecutor.submit(NativeWorkDispatcher.Priority.DEMAND, () -> {
                        try {
                            cubiomes.getTerrainHeight(0L, Cubiomes.DIMENSION_OVERWORLD, 0, 0);
                        } finally {
                            warmed.countDown();
                            // Keep each worker occupied until all have received their own TLS call.
                            // A bounded wait also releases active workers if shutdown cancels a peer.
                            try {
                                if (!warmed.await(2, TimeUnit.SECONDS))
                                    LOGGER.warn("[Myelin] TLS pre-warm worker rendezvous timed out.");
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }));
                }
                CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
            });
            LOGGER.info("[Myelin] TLS cache pre-warmed successfully.");
        } catch (RuntimeException error) {
            LOGGER.warn("[Myelin] TLS cache pre-warm did not complete; native jobs will initialize on demand.", error);
        }
    }

    public void onServerTick(MinecraftServer server) {
        if (closed || !config.enabled) return;
        cachedTerrainSender.ensureStarted();
        tickCounter++;
        surfaceChunkLifecycle.purgeCollectedTemporaryTopOnlyChunks();
    }

    public void startScheduling() {
        if (closed) return;
        stripScheduler.start();
        cachedTerrainSender.ensureStarted();
    }

    public void onServerTickStart(MinecraftServer server) {
        if (closed || !config.enabled) return;
        for (ServerWorld world : server.getWorlds()) {
            int dimension = DimensionIds.fromWorld(world);
            sessionWorlds.put(dimension, world);
            stripScheduler.updateContext(dimension, world.getSeed(), surfaceCacheGeneration.get(), settings(world));
            for (ServerPlayerEntity player : world.getPlayers()) myelinPlayerManager.publishPlayerWindow(player);
        }
    }

    /** Server-thread update; biome choice is runtime-only and never written into myelin.json. */
    public void applyClientSampling(int step, int biomeStep) {
        applyClientSampling(step, biomeStep, step >= 16 ? 4 : 0);
    }

    public void applyClientSampling(int step, int biomeStep, int correctionAmp) {
        int effectiveCorrection = step >= 16 && correctionAmp > 0 ? 4 : 0;
        boolean changed = config.samplingStep != step || clientBiomeStep != biomeStep
                || clientCorrectionAmp != effectiveCorrection;
        config.samplingStep = step;
        clientBiomeStep = biomeStep;
        clientCorrectionAmp = effectiveCorrection;
        Cubiomes.setBiomeStep(biomeStep);
        Cubiomes.setCorrectionAmp(effectiveCorrection);
        if (changed) invalidateSurfaceCaches();
    }

    private GenerationSettings settings(ServerWorld world) {
        return new GenerationSettings(config.samplingStep, clientBiomeStep, config.surfaceChunkDepth,
                config.regionPathEnabledFor(config.samplingStep), config.addVegetation, config.fillBottom, world.getBottomY(), world.getTopY());
    }

    private SnapshotWorkRegistry.Key registryKey(int dimension, int x, int z) {
        return new SnapshotWorkRegistry.Key(surfaceCacheGeneration.get(), dimension, x, z);
    }

    private final class SchedulerBackend implements StripSnapshotScheduler.Backend<SurfaceChunkSnapshot> {
        @Override public SurfaceChunkSnapshot cached(SnapshotWorkRegistry.Key key) {
            return !closed && key.epoch() == surfaceCacheGeneration.get()
                    ? surfaceChunkCache.get(key.dimension(), key.x(), key.z()) : null;
        }
        @Override public void publish(SnapshotWorkRegistry.Key key, SurfaceChunkSnapshot value) {
            publicationGate.readLock().lock();
            try {
                if (closed || key.epoch() != surfaceCacheGeneration.get())
                    throw new java.util.concurrent.CancellationException("Obsolete Myelin generation");
                surfaceChunkCache.put(value);
                surfaceCacheTrace.completed(key.dimension(), key.x(), key.z());
                ChunkKey position = chunkKey(key.dimension(), key.x(), key.z());
                cachedTerrainSender.snapshotReady(position, value);
            } finally { publicationGate.readLock().unlock(); }
        }
        @Override public void generate(StripSnapshotScheduler.Batch batch,
                java.util.function.BiConsumer<Integer, SurfaceChunkSnapshot> completed) {
            if (closed || batch.epoch() != surfaceCacheGeneration.get())
                throw new java.util.concurrent.CancellationException("Obsolete Myelin generation");
            if (!Cubiomes.isNativeAvailable()) throw new IllegalStateException("Native Cubiomes unavailable");
            GenerationSettings captured = (GenerationSettings) batch.settings();
            long started = System.nanoTime();
            long[] publicationNanos = {0};
            if (batch.demand()) diagnostics.counters().demandNativeCalls.increment();
            try {
                cubiomes.streamBuiltSurfaceRegion(batch.seed(), batch.dimension(), batch.x(), batch.z(),
                        batch.width(), batch.height(), captured.samplingStep(), batch.mask(), captured.regionMode(),
                        new NativeChunkOptions(captured.bottomY(), captured.topYExclusive(), captured.fillBottom(), captured.addVegetation(), captured.surfaceChunkDepth()),
                        (index, value) -> {
                            if (closed || batch.epoch() != surfaceCacheGeneration.get())
                                throw new java.util.concurrent.CancellationException("Obsolete Myelin generation");
                            if (value.nativeChunkData() == null)
                                throw new IllegalStateException("Missing complete native chunk");
                            long publishing = System.nanoTime();
                            try { completed.accept(index, value); }
                            finally { publicationNanos[0] += System.nanoTime() - publishing; }
                        });
            } finally {
                if (batch.demand()) diagnostics.counters().demandNativeNanos.add(
                        System.nanoTime() - started - publicationNanos[0]);
            }
        }
        @Override public void subscribeStrip(UUID player, StripSnapshotScheduler.Strip strip, long epoch, boolean preview) {
            cachedTerrainSender.subscribeStrip(player, strip, epoch, preview);
        }
        @Override public void unsubscribeStrip(UUID player, StripSnapshotScheduler.Strip strip, long epoch) {
            cachedTerrainSender.unsubscribeStrip(player, strip, epoch);
        }
        @Override public void maintenance() { cachedTerrainSender.cleanupMemoryLeaks(); }
        @Override public void planningTime(long nanos) {
            StepTimings.record(StepTimings.Step.SCHEDULE, System.nanoTime() - nanos);
        }
        @Override public boolean hasCacheRoom(int additional) {
            return surfaceChunkCache.prepareCapacity(additional);
        }
        @Override public long expiresAtMillis() { return System.currentTimeMillis() + surfaceChunkCache.ttlMillis(); }
        @Override public long expiresAtMillis(SnapshotWorkRegistry.Key key, SurfaceChunkSnapshot value) {
            SurfaceChunkCache.EntryToken token = surfaceChunkCache.currentToken(key.dimension(), key.x(), key.z());
            return token != null && token.snapshot() == value ? token.expiresAtMillis() : System.currentTimeMillis();
        }
        @Override public void scheduled(SnapshotWorkRegistry.Key key) {
            surfaceCacheTrace.scheduled(key.dimension(), key.x(), key.z());
        }
        @Override public void jobSubmitted(StripSnapshotScheduler.Batch batch) {
            if (batch.demand()) diagnostics.counters().demandJobs.increment();
        }
        @Override public void jobFinished(StripSnapshotScheduler.Batch batch, Throwable failure) {
            if (batch.demand() && failure != null) diagnostics.counters().demandFailures.increment();
        }
    }

    public boolean needsGeneration(ServerWorld world, int x, int z) {
        return !positionRegistry.isCovered(registryKey(DimensionIds.fromWorld(world), x, z), System.currentTimeMillis());
    }

    boolean generateSurfaceChunkIfNeeded(ServerWorld world, int x, int z) {
        stripScheduler.request(DimensionIds.fromWorld(world), world.getSeed(), x, z,
                surfaceCacheGeneration.get(), settings(world));
        return true;
    }

    public void benchmarkGeneration(ServerPlayerEntity player, int chunks, int step) {
        diagnostics.benchmarkGeneration(player, chunks, step);
    }

    public void benchmarkGeneration(ServerPlayerEntity player, int chunks, int step, boolean auto) {
        diagnostics.benchmarkGeneration(player, chunks, step, auto);
    }

    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto) {
        diagnostics.benchmarkGeneration(world, player, chunks, step, auto);
    }

    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto, boolean region) {
        diagnostics.benchmarkGeneration(world, player, chunks, step, auto, region);
    }

    public int getNativeWorkerCount() { return cubiomesExecutor.workerCount(); }

    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto, boolean region, int threads) {
        diagnostics.benchmarkGeneration(world, player, chunks, step, auto, region, threads);
    }

    public String stepTimingsSummary() { return diagnostics.stepTimingsSummary(); }

    public void resetStepTimings() { diagnostics.resetStepTimings(); }

    public String cacheDiagnosticsSummary() {
        return diagnostics.cacheDiagnosticsSummary(clientBiomeStep, clientCorrectionAmp, myelinPlayerManager.activeRadii());
    }

    public void resetCacheDiagnostics() { diagnostics.resetCacheDiagnostics(); }

    public boolean isPlayerInSurfaceMode(UUID playerId) {
        return myelinPlayerManager.isPlayerInSurfaceMode(playerId);
    }

    public SurfaceChunkSnapshot getSurfaceSnapshotForPreviewPacket(ServerPlayerEntity player, ServerWorld world, int chunkX, int chunkZ) {
        if (!config.enabled) return null;
        int dimensionId = DimensionIds.fromWorld(world);

        SurfaceChunkSnapshot cached = surfaceChunkCache.get(dimensionId, chunkX, chunkZ);
        if (cached != null) return cached;

        generateSurfaceChunkIfNeeded(world, chunkX, chunkZ);
        return null;
    }

    public boolean shouldUseTopOnlyGeneration(ServerWorld world, int chunkX, int chunkZ) {
        return myelinPlayerManager.shouldUseTopOnlyGeneration(world, chunkX, chunkZ);
    }

    /**
     * While a player is in Flight Mode, generation skips most steps, so unfinished
     * (proto) chunks around the player are only scaffolding. Vanilla would still
     * write them to the region files. They are rebuilt from the seed when needed,
     * so they are never saved. Finished chunks are left to the normal rules.
     */
    public boolean shouldSkipSavingUnfinishedChunk(ServerWorld world, Chunk chunk) {
        if (chunk == null) return false;
        if (chunk.getStatus().getChunkType() != net.minecraft.world.chunk.ChunkType.PROTOCHUNK) return false;
        return shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z);
    }

    /** Waits for a snapshot and builds the Surface chunk before returning. */
    public Chunk populateTopOnlyChunkSync(ServerWorld world, Chunk chunk) {
        SnapshotLookup lookup = getImmediateSurfaceSnapshot(world, chunk.getPos().x, chunk.getPos().z);
        return populateTopOnlyChunk(world, chunk, lookup.snapshot(), lookup.kind(), lookup.epoch());
    }

    /** Builds the Surface chunk on Minecraft's generation workers once its snapshot is ready. */
    public CompletableFuture<Chunk> populateTopOnlyChunkAsync(ServerWorld world, Chunk chunk) {
        return populateTopOnlyChunkAsync(world, chunk, null);
    }

    public CompletableFuture<Chunk> populateTopOnlyChunkAsync(ServerWorld world, Chunk chunk,
            java.util.function.Supplier<CompletableFuture<Chunk>> vanillaFallback) {
        CompletableFuture<SnapshotLookup> future = requestSurfaceSnapshotAsync(
                world, chunk.getPos().x, chunk.getPos().z);
        return future.thenComposeAsync(lookup -> {
            if (closed) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Myelin closed"));
            if (lookup.epoch() != surfaceCacheGeneration.get())
                return populateTopOnlyChunkAsync(world, chunk, vanillaFallback);
            if (vanillaFallback != null && !shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z)) {
                return vanillaFallback.get();
            }
            List<ChunkSection> locked = new ArrayList<>();
            try {
                for (ChunkSection section : ((ChunkAccessor) chunk).myelin$getSections()) {
                    if (section != null) {
                        section.lock();
                        locked.add(section);
                    }
                }
                return CompletableFuture.completedFuture(
                        populateTopOnlyChunk(world, chunk, lookup.snapshot(), lookup.kind(), lookup.epoch()));
            } finally {
                for (ChunkSection section : locked) section.unlock();
            }
        }, Util.getMainWorkerExecutor());
    }

    private CompletableFuture<SnapshotLookup> requestSurfaceSnapshotAsync(ServerWorld world, int x, int z) {
        if (closed) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Myelin closed"));
        int dimension = DimensionIds.fromWorld(world);
        long epoch = surfaceCacheGeneration.get();
        long seed = world.getSeed();
        GenerationSettings captured = settings(world);
        StripSnapshotScheduler.Lookup<SurfaceChunkSnapshot> lookup = stripScheduler.request(
                dimension, seed, x, z, epoch, captured);
        SnapshotLookupKind kind = SnapshotLookupKind.valueOf(lookup.kind().name());
        MyelinDiagnostics.CacheCounters counters = diagnostics.counters();
        switch (kind) {
            case CACHE_HIT -> counters.hits.increment();
            case READY_PENDING -> counters.readyPending.increment();
            case UNFINISHED_PENDING -> counters.unfinishedPending.increment();
            case NO_USABLE_PENDING -> { counters.noUsablePending.increment(); surfaceCacheTrace.missing(dimension, x, z); }
        }
        long started = System.nanoTime();
        if (kind != SnapshotLookupKind.CACHE_HIT) counters.asyncWaits.increment();
        return lookup.future().handle((value, failure) -> {
            if (closed) throw new java.util.concurrent.CancellationException("Myelin closed");
            if (kind != SnapshotLookupKind.CACHE_HIT) {
                counters.asyncCompleted.increment();
                counters.asyncWaitNanos.add(System.nanoTime() - started);
            }
            if (failure != null || value == null) {
                if (epoch != surfaceCacheGeneration.get()) return requestSurfaceSnapshotAsync(world, x, z);
                counters.placeholders.increment();
                throw new java.util.concurrent.CompletionException("Native chunk generation failed", failure);
            }
            if (epoch != surfaceCacheGeneration.get()) return requestSurfaceSnapshotAsync(world, x, z);
            if (value.nativeChunkData() == null)
                throw new IllegalStateException("Myelin requires complete native sections; check the NullFrame Core binary");
            return CompletableFuture.completedFuture(new SnapshotLookup(value, kind, epoch));
        }).thenCompose(value -> value).thenCompose(lookupValue -> lookupValue.epoch() == surfaceCacheGeneration.get()
                ? CompletableFuture.completedFuture(lookupValue) : requestSurfaceSnapshotAsync(world, x, z));
    }

    /**
     * Compatibility overload: waits for the scheduler's current result.
     * The supplied snapshot argument is unused.
     */
    public Chunk populateTopOnlyChunk(ServerWorld world, Chunk chunk, SurfaceChunkSnapshot snapshot) {
        SnapshotLookup prepared = requestSurfaceSnapshotAsync(world, chunk.getPos().x, chunk.getPos().z).join();
        return populateTopOnlyChunk(world, chunk, prepared.snapshot(), SnapshotLookupKind.CACHE_HIT, prepared.epoch());
    }

    static BlockState cacheStageColor(boolean enabled, SnapshotLookupKind kind) {
        return SurfaceChunkBuilder.cacheStageColor(enabled, kind);
    }

    private Chunk populateTopOnlyChunk(ServerWorld world, Chunk chunk, SurfaceChunkSnapshot snapshot,
                                      SnapshotLookupKind lookupKind, long generation) {
        return surfaceChunkBuilder.build(world, chunk, snapshot, lookupKind, generation);
    }

    static boolean canUseVanillaInitialLighting(Chunk chunk) {
        return SurfaceChunkBuilder.canUseVanillaInitialLighting(chunk);
    }

    public boolean isTemporaryTopOnlyChunk(ServerWorld world, int chunkX, int chunkZ) {
        return surfaceChunkLifecycle.isTemporaryTopOnlyChunk(world, chunkX, chunkZ);
    }

    public boolean anyPlayerInSurfaceMode() {
        return myelinPlayerManager.anyPlayerInSurfaceMode();
    }

    public boolean shouldSkipSavingTemporaryTopOnlyChunk(ServerWorld world, Chunk chunk) {
        return surfaceChunkLifecycle.shouldSkipSavingTemporaryTopOnlyChunk(world, chunk);
    }

    public boolean isFakeChunk(ServerWorld world, int chunkX, int chunkZ) {
        return surfaceChunkLifecycle.isFakeChunk(world, chunkX, chunkZ);
    }

    public void onChunkLoad(ServerWorld world, Chunk chunk) {
        surfaceChunkLifecycle.onChunkLoad(world, chunk);
    }

    public void onChunkUnload(ServerWorld world, Chunk chunk) {
        surfaceChunkLifecycle.onChunkUnload(world, chunk);
    }

    public void setPlayerNeedsSurfacePackets(UUID playerId, boolean needs) {
        myelinPlayerManager.setPlayerNeedsSurfacePackets(playerId, needs);
    }

    public boolean playerNeedsSurfacePackets(UUID playerId) {
        return myelinPlayerManager.playerNeedsSurfacePackets(playerId);
    }

    /**
     * Clears cached snapshots and queued previews when generation settings change.
     * Advances the generation and republishes player windows so pending work can use the new settings.
     */
    public void invalidateSurfaceCaches() {
        long epoch;
        publicationGate.writeLock().lock();
        try {
            epoch = surfaceCacheGeneration.incrementAndGet();
            surfaceChunkCache.clear();
            cachedTerrainSender.invalidate();
        } finally { publicationGate.writeLock().unlock(); }
        // Rebinding consumers may re-enter the registry; release the publication gate first.
        stripScheduler.invalidate(epoch);
        for (ServerWorld world : sessionWorlds.values())
            stripScheduler.updateContext(DimensionIds.fromWorld(world), world.getSeed(), epoch, settings(world));
        // Publish even stationary and ineligible contexts, so existing consumers can rebind.
        myelinPlayerManager.refreshWindows();
    }

    private boolean isWithinAnyPlayerWindow(int dimension, int x, int z) {
        return myelinPlayerManager.isWithinAnyPlayerWindow(dimension, x, z);
    }

    public void onPlayerDisconnect(UUID playerId) {
        myelinPlayerManager.onPlayerDisconnect(playerId);
    }

    public void toggleSurfaceMode(ServerPlayerEntity player) {
        myelinPlayerManager.toggleSurfaceMode(player);
    }

    public void convertTopOnlyToRealChunks(ServerPlayerEntity player) {
        myelinPlayerManager.convertTopOnlyToRealChunks(player);
    }

    public void startSurfaceHandoff(ServerPlayerEntity player, int trackedChunks) {
        myelinPlayerManager.convertTopOnlyToRealChunks(player);
    }

    /** Waits on the same snapshot request used by the asynchronous builder. */
    private SnapshotLookup getImmediateSurfaceSnapshot(ServerWorld world, int x, int z) {
        return requestSurfaceSnapshotAsync(world, x, z).join();
    }

    private void markTemporaryTopOnlyChunk(ServerWorld world, Chunk chunk, long generation) {
        surfaceChunkLifecycle.markTemporaryTopOnlyChunk(world, chunk, generation);
    }

    private ChunkKey chunkKey(int dimensionId, int chunkX, int chunkZ) {
        return new ChunkKey(dimensionId, chunkX, chunkZ);
    }

    public void close() {
        publicationGate.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            cachedTerrainSender.requestShutdown();
        } finally { publicationGate.writeLock().unlock(); }
        cachedTerrainSender.wakePreviewSender();
        stripScheduler.close();
        cubiomesExecutor.shutdownNow();
        diagnostics.shutdownNow();
        try {
            cachedTerrainSender.awaitTermination(1500);
            cubiomesExecutor.awaitTermination(2, TimeUnit.SECONDS);
            diagnostics.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        myelinPlayerManager.clearWindows();
        sessionWorlds.clear();
        surfaceChunkLifecycle.clearResidency();
        cachedTerrainSender.clear();
        myelinPlayerManager.clearOnlinePlayers();
        surfaceChunkCache.clear();
    }

}
