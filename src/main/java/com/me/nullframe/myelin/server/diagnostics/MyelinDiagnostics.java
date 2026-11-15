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

package com.me.nullframe.myelin.server.diagnostics;

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.MyelinMod;
import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.chunk.SurfaceChunkCache;
import com.me.nullframe.myelin.server.scheduling.NativeWorkDispatcher;
import com.me.nullframe.myelin.server.scheduling.StripSnapshotScheduler;

import com.nullframe.core.StepTimings;
import com.nullframe.core.cubiomes.Cubiomes;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class MyelinDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);
    private final MyelinConfig config;
    private final Cubiomes cubiomes;
    private final NativeWorkDispatcher cubiomesExecutor;
    private final StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler;
    private final SurfaceChunkCache surfaceChunkCache;
    private final SurfaceCacheTrace surfaceCacheTrace;
    private final BooleanSupplier closed;
    private final IntSupplier workerCount;

    public static final class CacheCounters {
        public final LongAdder hits = new LongAdder();
        public final LongAdder readyPending = new LongAdder();
        public final LongAdder unfinishedPending = new LongAdder();
        public final LongAdder noUsablePending = new LongAdder();
        public final LongAdder syncCalls = new LongAdder();
        public final LongAdder syncNanos = new LongAdder();
        public final LongAdder syncFailures = new LongAdder();
        public final LongAdder unusableCompletedResults = new LongAdder();
        public final LongAdder placeholders = new LongAdder();
        public final LongAdder asyncWaits = new LongAdder();
        public final LongAdder asyncCompleted = new LongAdder();
        public final LongAdder asyncWaitNanos = new LongAdder();
        public final LongAdder demandJobs = new LongAdder();
        public final LongAdder demandNativeCalls = new LongAdder();
        public final LongAdder demandNativeNanos = new LongAdder();
        public final LongAdder demandFailures = new LongAdder();
    }

    private volatile CacheCounters cacheCounters = new CacheCounters();
    // Run explicit /myelin bench requests on a separate coordinator thread.
    // Only one benchmark may run at a time.
    private final ExecutorService benchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "myelin-bench");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean benchInProgress = new AtomicBoolean(false);

    public MyelinDiagnostics(MyelinConfig config, Cubiomes cubiomes,
                      NativeWorkDispatcher cubiomesExecutor,
                      StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler,
                      SurfaceChunkCache surfaceChunkCache, SurfaceCacheTrace surfaceCacheTrace,
                      BooleanSupplier closed, IntSupplier workerCount) {
        this.config = config;
        this.cubiomes = cubiomes;
        this.cubiomesExecutor = cubiomesExecutor;
        this.stripScheduler = stripScheduler;
        this.surfaceChunkCache = surfaceChunkCache;
        this.surfaceCacheTrace = surfaceCacheTrace;
        this.closed = closed;
        this.workerCount = workerCount;
    }

    public CacheCounters counters() { return cacheCounters; }

    /**
     * Benchmarks terrain snapshots and Java decoding, without vegetation or block-section construction.
     * Minecraft installation, lighting, networking and rendering are excluded.
     */
    public void benchmarkGeneration(ServerPlayerEntity player, int chunks, int step) {
        benchmarkGeneration(player != null ? (ServerWorld) player.getWorld() : null,
                player, chunks, step, false);
    }

    /** Marks automatic requests separately in the output. All requests use an exclusive native phase. */
    public void benchmarkGeneration(ServerPlayerEntity player, int chunks, int step, boolean auto) {
        benchmarkGeneration(player != null ? (ServerWorld) player.getWorld() : null,
                player, chunks, step, auto);
    }

    /** Accepts a world explicitly so the server console can run a benchmark without a player. */
    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto) {
        benchmarkGeneration(world, player, chunks, step, auto, false);
    }

    /**
     * Measures terrain snapshots and Java decoding, excluding vegetation and block-section construction.
     * Runs after ordinary admitted work has finished.
     * New ordinary work waits until the benchmark ends.
     *
     * @param region use 64x64-chunk region calls instead of individual chunk calls;
     *               each worker rounds up to full regions, so report the actual generated count.
     */
    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto, boolean region) {
        benchmarkGeneration(world, player, chunks, step, auto, region, 0);
    }

    /** A zero worker limit uses the whole pool; a positive limit selects benchmark job count. */
    public void benchmarkGeneration(ServerWorld world, ServerPlayerEntity player,
                                    int chunks, int step, boolean auto, boolean region, int requestedThreads) {
        if (closed.getAsBoolean() || !config.enabled) return;
        int availableThreads = workerCount.getAsInt();
        if (availableThreads == 0) {
            sendBenchResult(player, "§c[Myelin-BENCH] native workers are disabled");
            return;
        }
        if (requestedThreads < 0 || requestedThreads > availableThreads) {
            sendBenchResult(player, "§c[Myelin-BENCH] worker count exceeds the available pool");
            return;
        }
        if (!Cubiomes.isNativeAvailable()) {
            sendBenchResult(player, "§c[Myelin-BENCH] native Cubiomes unavailable — can't benchmark");
            return;
        }
        if (chunks < 1) chunks = 1;
        // Longer runs can reduce the effect of startup and scheduling noise.
        if (!benchInProgress.compareAndSet(false, true)) {
            sendBenchResult(player, "§e[Myelin-BENCH] benchmark already running — wait for it to finish");
            return;
        }
        if (world == null) {
            benchInProgress.set(false);
            sendBenchResult(player, "§c[Myelin-BENCH] no world available for benchmark");
            return;
        }
        long seed = world.getSeed();
        int dimId = DimensionIds.fromWorld(world);
        final int fChunks = chunks;
        final int fStep = step;
        final int threads = Math.min(fChunks, requestedThreads == 0 ? availableThreads : requestedThreads);
        final boolean autoTriggered = auto;
        final boolean regionMode = region;
        try {
            benchExecutor.submit(() -> {
                int perThread = fChunks / threads;
                AtomicInteger generated = new AtomicInteger();
                int baseX = 1_000_000;
                int baseZ = 2_000_000;
                long[] elapsed = new long[1];
                try {
                sendBenchResult(player, "§e[Myelin-BENCH] exclusive native phase: draining admitted work; new requests will wait");
                cubiomesExecutor.runExclusive("benchmark", () -> {
                    long start = System.nanoTime();
                    List<CompletableFuture<Void>> jobs = new ArrayList<>(threads);
                    for (int t = 0; t < threads; t++) {
                        final int tIdx = t;
                        jobs.add(cubiomesExecutor.submit(NativeWorkDispatcher.Priority.DEMAND, () -> {
                        int from = tIdx * perThread;
                        int to = (tIdx == threads - 1) ? fChunks : from + perThread;
                        if (regionMode) {
                            // Sweep 64x64-chunk regions along X, using a separate row for each worker.
                            // This benchmark shape is larger than the strips used during gameplay.
                            final int regionSize = 64;
                            final int chunksPerRegion = regionSize * regionSize;
                            int rx = baseX;
                            int rz = baseZ + tIdx * regionSize;
                            // Count the whole region, even when it exceeds the remaining requested chunks.
                            int needed = to - from;
                            while (needed > 0) {
                                if (closed.getAsBoolean()) throw new java.util.concurrent.CancellationException("Myelin closed");
                                SurfaceChunkSnapshot[] snaps = cubiomes.getSurfaceRegionSnapshots(
                                        seed, dimId, rx, rz, regionSize, regionSize, fStep);
                                if (snaps != null) {
                                    for (SurfaceChunkSnapshot s : snaps) {
                                        if (s != null) generated.incrementAndGet();
                                    }
                                }
                                needed -= chunksPerRegion;
                                rx += regionSize;
                            }
                        } else {
                            for (int i = from; i < to; i++) {
                                if (closed.getAsBoolean()) throw new java.util.concurrent.CancellationException("Myelin closed");
                                int cx = baseX + (i % 64);
                                int cz = baseZ + (i / 64);
                                SurfaceChunkSnapshot s = cubiomes.getSurfaceChunkSnapshotDirect(
                                        seed, dimId, cx, cz, fStep);
                                if (s != null) generated.incrementAndGet();
                            }
                        }
                        }));
                    }
                    CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
                    elapsed[0] = System.nanoTime() - start;
                });
                long elapsedMs = elapsed[0] / 1_000_000;
                double perSec = elapsedMs > 0 ? generated.get() * 1000.0 / elapsedMs : 0.0;
                String modeName = regionMode ? "region" : "per-chunk";
                String msg = String.format("§b[Myelin-BENCH] %d chunk snapshots @ 1:%d (%s) in %dms → %.0f snapshots/sec (%d workers, terrain snapshots + Java decode; excludes vegetation and block-section construction, exclusive%s)",
                        generated.get(), fStep, modeName, elapsedMs, perSec, threads,
                        autoTriggered ? ", auto-triggered" : "");
                LOGGER.info("[Myelin-BENCH] chunks={} step={} mode={} ms={} chunksPerSec={} workers={} workload=terrain-snapshots+java-decode vegetation=excluded blockSections=excluded phase=exclusive",
                        generated.get(), fStep, modeName, elapsedMs, perSec, threads);
                sendBenchResult(player, msg);
            } catch (RuntimeException error) {
                LOGGER.warn("[Myelin-BENCH] exclusive benchmark failed or was stopped", error);
                sendBenchResult(player, "§c[Myelin-BENCH] benchmark failed or server stopped; no result recorded");
            } finally {
                benchInProgress.set(false);
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            benchInProgress.set(false);
            sendBenchResult(player, "§c[Myelin-BENCH] server is stopping; benchmark not started");
        }
    }

    private void sendBenchResult(ServerPlayerEntity player, String msg) {
        if (closed.getAsBoolean()) return;
        if (player == null) {
            // The result has already been written to the server log.
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.execute(() -> player.sendMessage(Text.literal(msg), false));
        }
    }

    /** Reports timings for each recorded step since the last reset. */
    public String stepTimingsSummary() {
        return StepTimings.summary("steps") + "\n" + stripScheduler.summary() + "\n" + cubiomesExecutor.statistics();
    }

    /** Resets shared step timings, including client timings in an integrated-server JVM. */
    public void resetStepTimings() {
        StepTimings.reset();
        stripScheduler.resetMetrics();
    }

    public String cacheDiagnosticsSummary(int clientBiomeStep, int clientCorrectionAmp, String activeRadii) {
        CacheCounters diagnostics = this.cacheCounters;
        long hits = diagnostics.hits.sum();
        long ready = diagnostics.readyPending.sum();
        long unfinished = diagnostics.unfinishedPending.sum();
        long missing = diagnostics.noUsablePending.sum();
        long total = hits + ready + unfinished + missing;
        double percent = total == 0 ? 0.0 : 100.0 / total;
        long calls = diagnostics.syncCalls.sum();
        double milliseconds = diagnostics.syncNanos.sum() / 1_000_000.0;
        long completedWaits = diagnostics.asyncCompleted.sum();
        double waitMilliseconds = diagnostics.asyncWaitNanos.sum() / 1_000_000.0;
        return "Snapshot miss mode: ASYNC demand | Unified native terrain + vegetation\n"
                + "Server settings: Terrain 1:" + config.samplingStep + " | Biome " + clientBiomeStep
                + " | Correction " + (config.samplingStep >= 16 && clientCorrectionAmp > 0 ? "ON (4)" : "OFF") + " | Region " + config.regionPathMode
                + " | Vegetation " + (config.addVegetation ? "ON" : "OFF")
                + " | Workers " + workerCount.getAsInt()
                + " | Surface depth " + config.surfaceChunkDepth
                + " | Cache-stage colors " + (config.debugColorChunksCacheStage ? "ON" : "OFF") + "\n"
                + "Directional prefetch margin: " + config.snapshotPrefetchMarginChunks
                + " chunks | Maximum reach (RD + margin): " + activeRadii + "\n"
                + surfaceChunkCache.diagnosticsPolicy() + "\n"
                + String.format(Locale.ROOT,
                "Myelin terrain cache: %,d construction requests (server-wide)\n"
                        + "Normal: %,d (%.1f%%) | Blue: %,d (%.1f%%)\n"
                        + "Orange: %,d (%.1f%%) | Pink: %,d (%.1f%%)\n"
                        + "Sync native calls: %,d | Total work: %.2f ms | Average: %.3f ms/call\n"
                        + "Sync failures: %,d | Unusable completed jobs: %,d | Flat placeholders: %,d",
                total, hits, hits * percent, ready, ready * percent,
                unfinished, unfinished * percent, missing, missing * percent,
                calls, milliseconds, calls == 0 ? 0.0 : milliseconds / calls,
                diagnostics.syncFailures.sum(), diagnostics.unusableCompletedResults.sum(),
                diagnostics.placeholders.sum()) + "\n" + String.format(Locale.ROOT,
                "Async waits: %,d | Completed: %,d | Mean snapshot wait: %.3f ms\n"
                        + "Demand jobs: %,d | Native calls: %,d | Native work: %.2f ms | Failures: %,d",
                diagnostics.asyncWaits.sum(), completedWaits,
                completedWaits == 0 ? 0.0 : waitMilliseconds / completedWaits,
                diagnostics.demandJobs.sum(), diagnostics.demandNativeCalls.sum(),
                diagnostics.demandNativeNanos.sum() / 1_000_000.0, diagnostics.demandFailures.sum())
                + "\n" + surfaceCacheTrace.summary() + "\n" + stripScheduler.summary() + "\n" + cubiomesExecutor.statistics();
    }

    public void resetCacheDiagnostics() {
        this.cacheCounters = new CacheCounters();
        this.surfaceCacheTrace.resetCounters();
        stripScheduler.resetMetrics();
    }

    public void shutdownNow() { benchExecutor.shutdownNow(); }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return benchExecutor.awaitTermination(timeout, unit);
    }
}
