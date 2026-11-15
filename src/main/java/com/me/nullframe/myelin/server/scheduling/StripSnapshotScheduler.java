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
package com.me.nullframe.myelin.server.scheduling;

import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Plans snapshot work on a dedicated thread, receiving updates through a queue.
 * The registry tracks shared requests and results.
 */
public final class StripSnapshotScheduler<V> implements AutoCloseable {
    static final int STRIP_WIDTH = StripGeometry.WIDTH;
    static final int STRIP_HEIGHT = StripGeometry.HEIGHT;
    private static final long AUDIT_INTERVAL = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long AUDIT_BUDGET = TimeUnit.MILLISECONDS.toNanos(1);

    public enum LookupKind { CACHE_HIT, READY_PENDING, UNFINISHED_PENDING, NO_USABLE_PENDING }
    public record Lookup<V>(LookupKind kind, CompletableFuture<V> future) { }
    public record PlayerWindow(UUID playerId, int dimension, long seed, int chunkX, int chunkZ,
                        int viewDistance, int margin, PrefetchDirection direction, boolean eligible, boolean preview,
                        long epoch, Object settings) {
        public PlayerWindow {
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(direction);
            viewDistance = Math.max(0, viewDistance);
            margin = Math.max(0, margin);
        }
        public StripGeometry.Range range() { return StripGeometry.window(chunkX, chunkZ, viewDistance, margin, direction); }
    }
    public record Strip(int dimension, int gridX, int gridZ) {
        public int x() { return gridX * STRIP_WIDTH; }
        public int z() { return gridZ * STRIP_HEIGHT; }
        static Strip at(int dimension, int x, int z) {
            return new Strip(dimension, Math.floorDiv(x, STRIP_WIDTH), Math.floorDiv(z, STRIP_HEIGHT));
        }
    }
    public record Batch(int dimension, long seed, int x, int z, int width, int height, byte[] mask,
                 long epoch, Object settings, boolean demand) { }

    public interface Backend<V> {
        V cached(SnapshotWorkRegistry.Key key);
        void publish(SnapshotWorkRegistry.Key key, V value);
        /** Publishes each finished chunk on the generating worker, before the rest of its batch is done. */
        void generate(Batch batch, java.util.function.BiConsumer<Integer, V> completed) throws Exception;
        default void subscribe(UUID player, SnapshotWorkRegistry.Key key, boolean preview) { }
        default void unsubscribe(UUID player, SnapshotWorkRegistry.Key key) { }
        default void subscribeStrip(UUID player, Strip strip, long epoch, boolean preview) {
            if (preview) forEachPosition(strip, epoch, key -> subscribe(player, key, true));
        }
        default void unsubscribeStrip(UUID player, Strip strip, long epoch) {
            forEachPosition(strip, epoch, key -> unsubscribe(player, key));
        }
        default void maintenance() { }
        default void backgroundWork() { }
        default boolean hasCacheRoom(int additional) { return true; }
        default long expiresAtMillis() { return Long.MAX_VALUE; }
        default long expiresAtMillis(SnapshotWorkRegistry.Key key, V value) { return expiresAtMillis(); }
        default void scheduled(SnapshotWorkRegistry.Key key) { }
        default void jobSubmitted(Batch batch) { }
        default void jobFinished(Batch batch, Throwable failure) { }
        default void planningTime(long elapsedNanos) { }
    }

    private static final class Interest {
        final Set<UUID> players = new HashSet<>();
        boolean dirty = true;
        int auditOffset;
        int auditChecked;
    }

    private final class Job {
        final Batch batch;
        final List<SnapshotWorkRegistry.Lease<V>> leases;
        final List<SnapshotWorkRegistry.Key> keys;
        long queuedAt = System.nanoTime();
        int attempt;
        long retryAt;
        volatile boolean urgent;
        boolean submitted;
        CompletableFuture<Void> execution;
        Job(Batch batch, List<SnapshotWorkRegistry.Lease<V>> leases) {
            this.batch = batch;
            this.leases = leases;
            keys = leases.stream().map(SnapshotWorkRegistry.Lease::key).toList();
            urgent = batch.demand;
        }
    }

    private record WindowUpdate(PlayerWindow window) { }
    private record GenerationContext(long seed, long epoch, Object settings) { }
    private record Expiry(SnapshotWorkRegistry.Key key, WeakReference<Object> value, long at) { }
    private record DeferredDemand<V>(int dimension, long seed, int x, int z, long previousEpoch,
                                     Object settings, CompletableFuture<V> result) { }

    private final long session;
    private final SnapshotWorkRegistry<V> registry;
    private final NativeWorkDispatcher dispatcher;
    private final Backend<V> backend;
    private final Thread thread;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean accepting = true;
    private volatile boolean running;
    private final ConcurrentHashMap<UUID, WindowUpdate> mailbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, GenerationContext> contexts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
    private final Set<CompletableFuture<V>> consumers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerWindow> windows = new HashMap<>();
    private final Map<Strip, Interest> wanted = new HashMap<>();
    private final Set<Job> jobs = new HashSet<>();
    private final Map<SnapshotWorkRegistry.Key, Job> jobsByKey = new HashMap<>();
    private final Set<SnapshotWorkRegistry.Key> urgent = ConcurrentHashMap.newKeySet();
    private final Set<SnapshotWorkRegistry.Key> exhausted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger reservedPositions = new AtomicInteger();
    private final List<DeferredDemand<V>> rebinds = new ArrayList<>();
    private final PriorityQueue<Expiry> expiry = new PriorityQueue<>(Comparator.comparingLong(Expiry::at));
    private final Map<SnapshotWorkRegistry.Key, Expiry> expiryIndex = new HashMap<>();
    private List<Strip> auditOrder = List.of();
    private int auditIndex;
    private long nextAudit;
    private long nextMaintenance;
    private volatile List<PlayerWindow> publishedWindows = List.of();
    private volatile String status = "not started";
    private volatile SchedulerMetrics metrics = new SchedulerMetrics();

    public StripSnapshotScheduler(long session, SnapshotWorkRegistry<V> registry,
                           NativeWorkDispatcher dispatcher, Backend<V> backend) {
        this.session = session;
        this.registry = Objects.requireNonNull(registry);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.backend = Objects.requireNonNull(backend);
        thread = new Thread(this::run, "Myelin-StripPlanner-" + session);
        thread.setDaemon(true);
    }

    public void start() {
        if (!accepting || !started.compareAndSet(false, true)) return;
        running = true;
        dispatcher.setWakeup(this::wakeup);
        thread.start();
    }

    public void publishWindow(PlayerWindow window) {
        if (!accepting) return;
        updateContext(window.dimension, window.seed, window.epoch, window.settings);
        mailbox.put(window.playerId, new WindowUpdate(window));
        wakeup();
    }

    public void removePlayer(UUID player) {
        if (!accepting) return;
        mailbox.put(player, new WindowUpdate(null));
        wakeup();
    }

    public List<PlayerWindow> windows() { return publishedWindows; }

    /** Updates generation settings even for dimensions without an eligible player. */
    public void updateContext(int dimension, long seed, long epoch, Object settings) {
        if (!accepting) return;
        contexts.compute(dimension, (ignored, previous) -> previous == null || previous.epoch <= epoch
                ? new GenerationContext(seed, epoch, settings) : previous);
        wakeup();
    }

    public boolean wanted(int dimension, int x, int z) {
        Strip strip = Strip.at(dimension, x, z);
        for (PlayerWindow window : publishedWindows) {
            if (window.eligible && window.epoch == registry.epoch() && window.dimension == dimension
                    && window.range().contains(strip.gridX, strip.gridZ)) return true;
        }
        return false;
    }

    public Lookup<V> request(int dimension, long seed, int x, int z, long epoch, Object settings) {
        if (!accepting) return new Lookup<>(LookupKind.NO_USABLE_PENDING,
                CompletableFuture.failedFuture(new CancellationException("Myelin session closed")));
        SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(epoch, dimension, x, z);
        updateContext(dimension, seed, epoch, settings);
        V cached = epoch == registry.epoch() ? backend.cached(key) : null;
        if (cached != null) {
            adoptReady(key, cached);
            return new Lookup<>(LookupKind.CACHE_HIT, CompletableFuture.completedFuture(cached));
        }
        boolean pending = registry.isPending(key);
        CompletableFuture<V> result = new CompletableFuture<>();
        consumers.add(result);
        long requestedAt = System.nanoTime();
        attachDemand(dimension, seed, x, z, epoch, settings, result);
        LookupKind kind = pending ? (result.isDone() && !result.isCompletedExceptionally()
                ? LookupKind.READY_PENDING : LookupKind.UNFINISHED_PENDING) : LookupKind.NO_USABLE_PENDING;
        result.whenComplete((value, failure) -> {
            consumers.remove(result);
            metrics.demand.record(System.nanoTime() - requestedAt);
        });
        return new Lookup<>(kind, result.thenApply(value -> value));
    }

    private void attachDemand(int dimension, long seed, int x, int z, long epoch, Object settings,
                              CompletableFuture<V> result) {
        if (result.isDone()) return;
        if (!accepting) {
            result.completeExceptionally(new CancellationException("Myelin session closed"));
            return;
        }
        SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(epoch, dimension, x, z);
        SnapshotWorkRegistry.Reservation<V> reservation;
        try { reservation = registry.reserve(key); }
        catch (IllegalStateException obsolete) {
            enqueue(() -> rebinds.add(new DeferredDemand<>(dimension, seed, x, z, epoch, settings, result)));
            return;
        }
        if (reservation.owner()) {
            exhausted.remove(key);
            urgent.add(key);
            trackReservation(reservation.lease());
            metrics.reservations.increment();
            backend.scheduled(key);
            enqueue(() -> {
                Job job = new Job(new Batch(dimension, seed, x, z, 1, 1, new byte[]{1},
                        epoch, settings, true), new ArrayList<>(List.of(reservation.lease())));
                jobs.add(job);
                jobsByKey.put(key, job);
                urgent.add(key);
            });
        } else {
            metrics.reused.increment();
            urgent.add(key);
            enqueue(() -> {
                urgent.add(key);
                Job job = jobsByKey.get(key);
                if (job != null) {
                    job.urgent = true;
                    if (job.execution != null) dispatcher.promote(job.execution, NativeWorkDispatcher.Priority.DEMAND);
                }
            });
        }
        reservation.future().whenComplete((value, failure) -> {
            if (accepting && registry.epoch() != epoch) {
                enqueue(() -> rebinds.add(new DeferredDemand<>(dimension, seed, x, z, epoch, settings, result)));
            } else if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
            enqueue(() -> urgent.remove(key));
        });
    }

    public void availabilityLost(SnapshotWorkRegistry.Key key, V value) {
        enqueue(() -> {
            expiryIndex.remove(key);
            V current = backend.cached(key);
            if (current != null) {
                adoptReady(key, current);
                return;
            }
            registry.removeReady(key, value);
            markDirty(key);
        });
    }

    public void markResident(SnapshotWorkRegistry.Key key, Object chunkToken) { registry.markResident(key, chunkToken); }

    public void unload(SnapshotWorkRegistry.Key key, Object chunkToken) {
        registry.unload(key, chunkToken);
        enqueue(() -> markDirty(key));
    }

    public void invalidate(long epoch) {
        registry.invalidate(epoch, new IllegalStateException("Myelin quality changed"));
        enqueue(() -> {
            for (PlayerWindow window : new ArrayList<>(windows.values())) {
                if (window.epoch != epoch) updateWindow(window.playerId, null);
            }
            expiry.removeIf(item -> item.key.epoch() != epoch);
            expiryIndex.keySet().removeIf(key -> key.epoch() != epoch);
            urgent.removeIf(key -> key.epoch() != epoch);
            exhausted.removeIf(key -> key.epoch() != epoch);
        });
    }

    public void wakeup() { LockSupport.unpark(thread); }

    private void enqueue(Runnable event) {
        if (!accepting) return;
        events.add(event);
        wakeup();
    }

    private void run() {
        nextAudit = nextMaintenance = System.nanoTime();
        try {
            while (running) {
                long planningAt = System.nanoTime();
                boolean activity = drainMailbox();
                Runnable event;
                while ((event = events.poll()) != null) {
                    event.run();
                    activity = true;
                }
                rebindDemands();
                expireReady();
                pruneJobs();
                while (dispatchPrepared()) activity = true;
                while (dispatcher.availableSlots() > 0 && prepareAndDispatch()) activity = true;
                if (dispatcher.availableSlots() > 0) backend.backgroundWork();
                long now = System.nanoTime();
                if (now >= nextAudit) {
                    if (dispatcher.queuedCount(NativeWorkDispatcher.Priority.DEMAND) == 0) audit();
                    nextAudit = now + AUDIT_INTERVAL;
                    activity |= !wanted.isEmpty();
                }
                if (now >= nextMaintenance) {
                    backend.maintenance();
                    nextMaintenance = now + TimeUnit.SECONDS.toNanos(1);
                    activity = true;
                }
                updateStatus();
                if (activity) {
                    long elapsed = System.nanoTime() - planningAt;
                    metrics.planning.record(elapsed);
                    backend.planningTime(elapsed);
                }
                long wait = Math.min(nextAudit, nextMaintenance) - System.nanoTime();
                if (!activity && mailbox.isEmpty() && events.isEmpty()) LockSupport.parkNanos(this, Math.max(1, wait));
            }
        } catch (Throwable failure) {
            accepting = false;
            registry.close(failure);
            consumers.forEach(demand -> demand.completeExceptionally(failure));
            status = "planner failed: " + failure;
        } finally {
            running = false;
        }
    }

    private boolean drainMailbox() {
        boolean changed = false;
        for (Map.Entry<UUID, WindowUpdate> item : mailbox.entrySet()) {
            if (!mailbox.remove(item.getKey(), item.getValue())) continue;
            updateWindow(item.getKey(), item.getValue().window);
            changed = true;
        }
        return changed;
    }

    private void updateWindow(UUID player, PlayerWindow next) {
        PlayerWindow old = windows.get(player);
        if (next != null && next.epoch != registry.epoch()) next = null;
        if (Objects.equals(old, next)) return;
        boolean oldActive = old != null && old.eligible;
        boolean newActive = next != null && next.eligible;
        boolean compatible = oldActive && newActive && old.dimension == next.dimension && old.epoch == next.epoch;
        StripGeometry.Range oldRange = oldActive ? old.range() : null;
        StripGeometry.Range newRange = newActive ? next.range() : null;
        if (oldActive) {
            for (StripGeometry.Range leaving : compatible ? oldRange.outside(newRange) : List.of(oldRange)) {
                PlayerWindow previous = old;
                leaving.forEach((x, z) -> removeInterest(previous, new Strip(previous.dimension, x, z)));
            }
        }
        if (next == null) windows.remove(player); else windows.put(player, next);
        if (newActive) {
            for (StripGeometry.Range entering : compatible ? newRange.outside(oldRange) : List.of(newRange)) {
                PlayerWindow current = next;
                entering.forEach((x, z) -> addInterest(current, new Strip(current.dimension, x, z)));
            }
        }
        if (compatible && old.preview != next.preview) {
            PlayerWindow current = next;
            newRange.forEach((x, z) -> {
                if (oldRange.contains(x, z)) {
                    Strip strip = new Strip(current.dimension, x, z);
                    if (old.preview) backend.unsubscribeStrip(player, strip, old.epoch);
                    if (current.preview) backend.subscribeStrip(player, strip, current.epoch, true);
                }
            });
        }
        publishedWindows = List.copyOf(windows.values());
        auditOrder = List.copyOf(wanted.keySet());
        if (!auditOrder.isEmpty()) auditIndex %= auditOrder.size(); else auditIndex = 0;
    }

    private void addInterest(PlayerWindow window, Strip strip) {
        wanted.computeIfAbsent(strip, ignored -> new Interest()).players.add(window.playerId);
        if (window.preview) backend.subscribeStrip(window.playerId, strip, window.epoch, true);
    }

    private void removeInterest(PlayerWindow window, Strip strip) {
        Interest interest = wanted.get(strip);
        if (interest != null) {
            interest.players.remove(window.playerId);
            if (interest.players.isEmpty()) {
                wanted.remove(strip);
                if (!exhausted.isEmpty()) forEachPosition(strip, window.epoch, exhausted::remove);
            }
        }
        if (window.preview) backend.unsubscribeStrip(window.playerId, strip, window.epoch);
    }

    private static void forEachPosition(Strip strip, long epoch, java.util.function.Consumer<SnapshotWorkRegistry.Key> action) {
        for (int z = 0; z < STRIP_HEIGHT; z++) for (int x = 0; x < STRIP_WIDTH; x++) {
            action.accept(new SnapshotWorkRegistry.Key(epoch, strip.dimension, strip.x() + x, strip.z() + z));
        }
    }

    private PlayerWindow context(Strip strip) {
        Interest interest = wanted.get(strip);
        if (interest == null) return null;
        for (UUID player : interest.players) {
            PlayerWindow window = windows.get(player);
            if (window != null && window.eligible && window.epoch == registry.epoch()) return window;
        }
        return null;
    }

    private boolean visible(Strip strip) {
        for (PlayerWindow window : windows.values()) {
            if (window.eligible && window.dimension == strip.dimension && window.epoch == registry.epoch()
                    && StripGeometry.intersectsVisible(strip.gridX, strip.gridZ,
                    window.chunkX, window.chunkZ, window.viewDistance)) return true;
        }
        return false;
    }

    private long distance(Strip strip) {
        long best = Long.MAX_VALUE;
        for (PlayerWindow window : windows.values()) {
            if (window.eligible && window.dimension == strip.dimension) {
                best = Math.min(best, Math.abs((long) strip.x() + STRIP_WIDTH / 2 - window.chunkX)
                        + Math.abs((long) strip.z() + STRIP_HEIGHT / 2 - window.chunkZ));
            }
        }
        return best;
    }

    private boolean prepareAndDispatch() {
        Strip best = null;
        boolean bestVisible = false;
        long nearest = Long.MAX_VALUE;
        for (Map.Entry<Strip, Interest> item : wanted.entrySet()) {
            if (!item.getValue().dirty) continue;
            Strip strip = item.getKey();
            boolean isVisible = visible(strip);
            long distance = distance(strip);
            if (best == null || isVisible && !bestVisible || isVisible == bestVisible && distance < nearest) {
                best = strip;
                bestVisible = isVisible;
                nearest = distance;
            }
        }
        if (best == null) return false;
        PlayerWindow window = context(best);
        Interest interest = wanted.get(best);
        if (window == null) { interest.dirty = false; return true; }
        if (!hasMissing(best, window.epoch)) { interest.dirty = false; return true; }
        if (!backend.hasCacheRoom(STRIP_WIDTH * STRIP_HEIGHT + reservedPositions.get())) {
            int missing = missingCount(best, window.epoch);
            if (missing == 0) { interest.dirty = false; return true; }
            if (!backend.hasCacheRoom(missing + reservedPositions.get())) return false;
        }
        Strip second = null;
        if (!bestVisible) {
            Strip candidate = new Strip(best.dimension, best.gridX, best.gridZ + 1);
            Interest adjacent = wanted.get(candidate);
            if (adjacent != null && adjacent.dirty && !visible(candidate)
                    && backend.hasCacheRoom(STRIP_WIDTH * STRIP_HEIGHT * 2 + reservedPositions.get())) second = candidate;
        }
        Job job = reserveBatch(best, second, window);
        interest.dirty = false;
        if (second != null) wanted.get(second).dirty = false;
        if (job == null) return true;
        jobs.add(job);
        for (SnapshotWorkRegistry.Lease<V> lease : job.leases) jobsByKey.put(lease.key(), job);
        submit(job);
        return true;
    }

    private boolean hasMissing(Strip strip, long epoch) {
        int start = ThreadLocalRandom.current().nextInt(STRIP_WIDTH * STRIP_HEIGHT);
        long now = System.currentTimeMillis();
        for (int n = 0; n < STRIP_WIDTH * STRIP_HEIGHT; n++) {
            int offset = (start + n) % (STRIP_WIDTH * STRIP_HEIGHT);
            SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(epoch, strip.dimension,
                    strip.x() + offset % STRIP_WIDTH, strip.z() + offset / STRIP_WIDTH);
            if (!exhausted.contains(key) && !covered(key, now)) return true;
        }
        return false;
    }

    private int missingCount(Strip strip, long epoch) {
        int missing = 0;
        long now = System.currentTimeMillis();
        for (int z = 0; z < STRIP_HEIGHT; z++) for (int x = 0; x < STRIP_WIDTH; x++) {
            SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(epoch, strip.dimension, strip.x() + x, strip.z() + z);
            if (!exhausted.contains(key) && !covered(key, now)) missing++;
        }
        return missing;
    }

    private boolean covered(SnapshotWorkRegistry.Key key, long now) {
        if (registry.isCovered(key, now)) return true;
        V cached = backend.cached(key);
        if (cached == null) return false;
        adoptReady(key, cached);
        return true;
    }

    private void trackReservation(SnapshotWorkRegistry.Lease<V> lease) {
        reservedPositions.incrementAndGet();
        lease.future().whenComplete((unused, failure) -> reservedPositions.decrementAndGet());
    }

    private void adoptReady(SnapshotWorkRegistry.Key key, V value) {
        long deadline = backend.expiresAtMillis(key, value);
        registry.ready(key, value, deadline, value);
        enqueue(() -> trackExpiry(key, value, deadline));
    }

    private void trackExpiry(SnapshotWorkRegistry.Key key, V value, long deadline) {
        if (key.epoch() != registry.epoch() || backend.cached(key) != value) return;
        if (deadline == Long.MAX_VALUE) { expiryIndex.remove(key); return; }
        Expiry previous = expiryIndex.get(key);
        if (previous != null && previous.value.get() == value && previous.at == deadline) return;
        Expiry next = new Expiry(key, new WeakReference<>(value), deadline);
        expiryIndex.put(key, next);
        expiry.add(next);
        if (expiry.size() > Math.max(1_024, expiryIndex.size() * 2)) {
            expiry.clear();
            expiry.addAll(expiryIndex.values());
        }
    }

    private Job reserveBatch(Strip first, Strip second, PlayerWindow window) {
        int height = second == null ? STRIP_HEIGHT : STRIP_HEIGHT * 2;
        byte[] mask = new byte[STRIP_WIDTH * height];
        List<SnapshotWorkRegistry.Lease<V>> leases = new ArrayList<>();
        for (int z = 0; z < height; z++) for (int x = 0; x < STRIP_WIDTH; x++) {
            SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(window.epoch, first.dimension,
                    first.x() + x, first.z() + z);
            if (exhausted.contains(key) || covered(key, System.currentTimeMillis())) continue;
            SnapshotWorkRegistry.Reservation<V> reservation;
            try { reservation = registry.reservePrefetch(key); }
            catch (IllegalStateException obsolete) { continue; }
            if (reservation == null || !reservation.owner()) continue;
            leases.add(reservation.lease());
            trackReservation(reservation.lease());
            mask[z * STRIP_WIDTH + x] = 1;
            metrics.reservations.increment();
            backend.scheduled(key);
        }
        if (leases.isEmpty()) return null;
        return new Job(new Batch(first.dimension, window.seed, first.x(), first.z(), STRIP_WIDTH,
                height, mask, window.epoch, window.settings, false), leases);
    }

    private boolean dispatchPrepared() {
        splitPromotedGroups();
        Job best = null;
        int priority = Integer.MAX_VALUE;
        long now = System.nanoTime();
        for (Job job : jobs) {
            if (job.submitted || job.retryAt > now || job.leases.isEmpty()) continue;
            int rank = priority(job).ordinal();
            if (rank < priority) { best = job; priority = rank; }
        }
        return best != null && submit(best);
    }

    private void splitPromotedGroups() {
        for (Job job : new ArrayList<>(jobs)) {
            if (job.submitted || job.batch.height != STRIP_HEIGHT * 2
                    || priority(job) == NativeWorkDispatcher.Priority.PREFETCH) continue;
            jobs.remove(job);
            for (int half = 0; half < 2; half++) {
                int originZ = job.batch.z + half * STRIP_HEIGHT;
                List<SnapshotWorkRegistry.Lease<V>> leases = new ArrayList<>();
                byte[] mask = new byte[STRIP_WIDTH * STRIP_HEIGHT];
                for (SnapshotWorkRegistry.Lease<V> lease : job.leases) {
                    int z = lease.key().z() - originZ;
                    if (z < 0 || z >= STRIP_HEIGHT) continue;
                    leases.add(lease);
                    mask[z * STRIP_WIDTH + lease.key().x() - job.batch.x] = 1;
                }
                if (leases.isEmpty()) continue;
                Job split = new Job(new Batch(job.batch.dimension, job.batch.seed, job.batch.x,
                        originZ, STRIP_WIDTH, STRIP_HEIGHT, mask, job.batch.epoch, job.batch.settings, false), leases);
                split.urgent = leases.stream().anyMatch(lease -> urgent.contains(lease.key()));
                split.attempt = job.attempt;
                split.retryAt = job.retryAt;
                jobs.add(split);
                for (SnapshotWorkRegistry.Lease<V> lease : leases) jobsByKey.put(lease.key(), split);
            }
        }
    }

    private NativeWorkDispatcher.Priority priority(Job job) {
        if (job.leases.stream().anyMatch(SnapshotWorkRegistry.Lease::hasDemand)) job.urgent = true;
        if (job.urgent || job.batch.demand) return NativeWorkDispatcher.Priority.DEMAND;
        int endX = job.batch.x + job.batch.width - 1, endZ = job.batch.z + job.batch.height - 1;
        for (PlayerWindow window : windows.values()) {
            if (window.eligible && window.dimension == job.batch.dimension && window.epoch == job.batch.epoch
                    && job.batch.x <= window.chunkX + window.viewDistance && endX >= window.chunkX - window.viewDistance
                    && job.batch.z <= window.chunkZ + window.viewDistance && endZ >= window.chunkZ - window.viewDistance) {
                return NativeWorkDispatcher.Priority.VISIBLE;
            }
        }
        return NativeWorkDispatcher.Priority.PREFETCH;
    }

    private boolean submit(Job job) {
        NativeWorkDispatcher.Priority priority = priority(job);
        job.queuedAt = System.nanoTime();
        CompletableFuture<Void> execution = priority == NativeWorkDispatcher.Priority.DEMAND
                ? dispatcher.submit(priority, () -> execute(job))
                : dispatcher.trySubmitSpeculative(priority, () -> execute(job));
        if (execution == null) return false;
        job.submitted = true;
        job.execution = execution;
        for (SnapshotWorkRegistry.Lease<V> lease : job.leases) registry.markQueued(lease);
        backend.jobSubmitted(job.batch);
        execution.whenComplete((unused, failure) -> enqueue(() -> {
            if (job.execution == execution) finish(job, failure);
        }));
        return true;
    }

    private void execute(Job job) {
        metrics.queue.record(System.nanoTime() - job.queuedAt);
        List<SnapshotWorkRegistry.Lease<V>> active = new ArrayList<>();
        byte[] mask = new byte[job.batch.width * job.batch.height];
        for (SnapshotWorkRegistry.Lease<V> lease : job.leases) {
            if (!job.batch.demand && !wanted(lease.key().dimension(), lease.key().x(), lease.key().z())
                    && registry.failIfUnrequested(lease, new CancellationException("Speculative position no longer wanted"))) {
                metrics.obsolete.increment();
                continue;
            }
            if (!registry.markRunning(lease)) continue;
            active.add(lease);
            int index = (lease.key().z() - job.batch.z) * job.batch.width + lease.key().x() - job.batch.x;
            mask[index] = 1;
        }
        if (active.isEmpty()) { metrics.obsolete.increment(); return; }
        Batch batch = new Batch(job.batch.dimension, job.batch.seed, job.batch.x, job.batch.z,
                job.batch.width, job.batch.height, mask, job.batch.epoch, job.batch.settings, job.urgent);
        long startedAt = System.nanoTime();
        long[] publicationNanos = {0};
        long[] previousReady = {startedAt};
        boolean[] first = {true};
        Map<Integer, SnapshotWorkRegistry.Lease<V>> remaining = new HashMap<>();
        for (SnapshotWorkRegistry.Lease<V> lease : active) {
            remaining.put((lease.key().z() - batch.z) * batch.width + lease.key().x() - batch.x, lease);
        }
        Throwable failure = null;
        try {
            try {
                backend.generate(batch, (index, value) -> {
                    long readyAt = System.nanoTime();
                    metrics.chunkReady.record(readyAt - previousReady[0]);
                    if (first[0]) {
                        metrics.firstResult.record(readyAt - startedAt);
                        first[0] = false;
                    }
                    SnapshotWorkRegistry.Lease<V> lease = remaining.remove(index);
                    if (lease == null || value == null)
                        throw new IllegalStateException("Unexpected or duplicate streamed chunk");
                    long publicationAt = System.nanoTime();
                    try {
                        long expiresAt = backend.expiresAtMillis();
                        if (registry.publish(lease, value, expiresAt, snapshot -> backend.publish(lease.key(), snapshot))) {
                            enqueue(() -> trackExpiry(lease.key(), value, backend.expiresAtMillis(lease.key(), value)));
                        } else metrics.obsolete.increment();
                    } finally {
                        long finishedAt = System.nanoTime();
                        long elapsed = finishedAt - publicationAt;
                        metrics.publication.record(elapsed);
                        publicationNanos[0] += elapsed;
                        previousReady[0] = finishedAt;
                    }
                });
            } finally {
                metrics.nativeWork.record(System.nanoTime() - startedAt - publicationNanos[0]);
            }
            if (active.stream().anyMatch(registry::current)) throw new IllegalStateException("Native batch left missing snapshots");
        } catch (Throwable error) {
            failure = error;
            throw error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
        } finally {
            metrics.batches.increment();
            backend.jobFinished(batch, failure);
        }
    }

    private void finish(Job job, Throwable failure) {
        job.leases.removeIf(lease -> !registry.current(lease));
        if (failure != null && !job.leases.isEmpty() && job.attempt < 3 && accepting) {
            job.retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50L << job.attempt++);
            job.submitted = false;
            job.execution = null;
            return;
        }
        for (SnapshotWorkRegistry.Lease<V> lease : job.leases) {
            if (wanted.containsKey(Strip.at(lease.key().dimension(), lease.key().x(), lease.key().z()))) {
                exhausted.add(lease.key());
            }
            registry.fail(lease, failure == null ? new IllegalStateException("Missing snapshot") : failure);
        }
        forgetJob(job);
    }

    private void forgetJob(Job job) {
        jobs.remove(job);
        for (SnapshotWorkRegistry.Key key : job.keys) jobsByKey.remove(key, job);
    }

    private void pruneJobs() {
        for (Job job : new ArrayList<>(jobs)) {
            if (job.submitted) {
                boolean stillNeeded = job.batch.epoch == registry.epoch() && job.leases.stream().anyMatch(lease ->
                        lease.hasDemand() || wanted.containsKey(Strip.at(lease.key().dimension(), lease.key().x(), lease.key().z())));
                if (!stillNeeded && dispatcher.cancelQueued(job.execution)) {
                    job.submitted = false;
                    job.execution = null;
                    continue;
                }
                if (job.batch.height == STRIP_HEIGHT * 2
                        && priority(job) != NativeWorkDispatcher.Priority.PREFETCH
                        && dispatcher.cancelQueued(job.execution)) {
                    job.submitted = false;
                    job.execution = null;
                    continue;
                }
                if (job.execution != null) dispatcher.reprioritize(job.execution, priority(job));
                continue;
            }
            job.leases.removeIf(lease -> {
                SnapshotWorkRegistry.Key key = lease.key();
                if (urgent.contains(key) || wanted.containsKey(Strip.at(key.dimension(), key.x(), key.z()))) return false;
                if (!registry.failIfUnrequested(lease, new CancellationException("Speculative position no longer wanted"))) return false;
                jobsByKey.remove(key, job);
                metrics.obsolete.increment();
                return true;
            });
            if (job.leases.isEmpty()) {
                forgetJob(job);
                continue;
            }
            boolean requested = job.urgent || job.leases.stream().anyMatch(lease -> urgent.contains(lease.key()));
            if (requested) { job.urgent = true; continue; }
            boolean needed = job.leases.stream().anyMatch(lease -> wanted.containsKey(
                    Strip.at(lease.key().dimension(), lease.key().x(), lease.key().z())));
            if (job.batch.epoch != registry.epoch() || !needed) {
                metrics.obsolete.increment();
                for (SnapshotWorkRegistry.Lease<V> lease : job.leases) {
                    registry.fail(lease, new CancellationException("Speculative strip no longer wanted"));
                }
                forgetJob(job);
            }
        }
    }

    private void markDirty(SnapshotWorkRegistry.Key key) {
        if (key.epoch() != registry.epoch()) return;
        Interest interest = wanted.get(Strip.at(key.dimension(), key.x(), key.z()));
        if (interest != null && !registry.isCovered(key, System.currentTimeMillis())) {
            interest.dirty = true;
            metrics.repairs.increment();
        }
    }

    private void rebindDemands() {
        for (Iterator<DeferredDemand<V>> iterator = rebinds.iterator(); iterator.hasNext();) {
            DeferredDemand<V> demand = iterator.next();
            if (demand.result.isDone()) { iterator.remove(); continue; }
            GenerationContext context = contexts.get(demand.dimension);
            if (context == null || context.epoch != registry.epoch()) continue;
            iterator.remove();
            attachDemand(demand.dimension, context.seed, demand.x, demand.z, context.epoch,
                    context.settings, demand.result);
        }
    }

    private void expireReady() {
        long now = System.currentTimeMillis();
        while (!expiry.isEmpty() && expiry.peek().at <= now) {
            Expiry item = expiry.poll();
            if (!expiryIndex.remove(item.key, item)) continue;
            Object value = item.value.get();
            if (value == null) continue;
            V cached = backend.cached(item.key);
            if (cached != null && backend.expiresAtMillis(item.key, cached) > now) {
                adoptReady(item.key, cached);
                continue;
            }
            registry.removeReady(item.key, value);
            markDirty(item.key);
        }
    }

    private void audit() {
        if (auditOrder.isEmpty()) return;
        long deadline = System.nanoTime() + AUDIT_BUDGET;
        int completedStrips = 0;
        while (completedStrips < Math.min(2, auditOrder.size()) && System.nanoTime() < deadline) {
            Strip strip = auditOrder.get(auditIndex);
            Interest interest = wanted.get(strip);
            PlayerWindow window = context(strip);
            if (interest == null || window == null) {
                auditIndex = (auditIndex + 1) % auditOrder.size();
                completedStrips++;
                continue;
            }
            if (interest.auditChecked == 0) interest.auditOffset = ThreadLocalRandom.current().nextInt(STRIP_WIDTH * STRIP_HEIGHT);
            while (interest.auditChecked < STRIP_WIDTH * STRIP_HEIGHT && System.nanoTime() < deadline) {
                int offset = (interest.auditOffset + interest.auditChecked++) % (STRIP_WIDTH * STRIP_HEIGHT);
                SnapshotWorkRegistry.Key key = new SnapshotWorkRegistry.Key(window.epoch, strip.dimension,
                        strip.x() + offset % STRIP_WIDTH, strip.z() + offset / STRIP_WIDTH);
                metrics.auditPositions.increment();
                if (!exhausted.contains(key) && !covered(key, System.currentTimeMillis())) {
                    interest.dirty = true;
                    interest.auditChecked = STRIP_WIDTH * STRIP_HEIGHT;
                }
            }
            if (interest.auditChecked == STRIP_WIDTH * STRIP_HEIGHT) {
                interest.auditChecked = 0;
                completedStrips++;
                auditIndex = (auditIndex + 1) % auditOrder.size();
            }
        }
    }

    private void updateStatus() {
        long visible = wanted.keySet().stream().filter(this::visible).count();
        long queued = jobs.stream().filter(job -> !job.submitted).count();
        status = "session " + session + " | strips " + wanted.size() + " (visible " + visible
                + ") | aligned coverage " + ((long) wanted.size() * STRIP_WIDTH * STRIP_HEIGHT)
                + " chunks | planner descriptors " + queued + " | native active " + dispatcher.activeCount()
                + "/" + dispatcher.workerCount() + " | demand queue "
                + dispatcher.queuedCount(NativeWorkDispatcher.Priority.DEMAND);
    }

    public String summary() {
        SchedulerMetrics sample = metrics;
        return "Strip scheduler: 8x16; background up to 8x32; per-chunk direct delivery; directional prefetch | " + status
                + "\nReservations " + sample.reservations.sum() + " | reused " + sample.reused.sum()
                + " | repairs " + sample.repairs.sum() + " | obsolete " + sample.obsolete.sum()
                + " | batches " + sample.batches.sum() + " | audit positions " + sample.auditPositions.sum()
                + "\nPlanner: " + sample.planning.summary() + "\nNative queue: " + sample.queue.summary()
                + "\nStrip work (excluding publication): " + sample.nativeWork.summary()
                + "\nFirst chunk in strip: " + sample.firstResult.summary()
                + "\nChunk ready interval: " + sample.chunkReady.summary()
                + "\nPer-chunk publication: " + sample.publication.summary()
                + "\nRequest to snapshot (misses): " + sample.demand.summary();
    }

    public void resetMetrics() { metrics = new SchedulerMetrics(); }

    @Override public void close() {
        if (!accepting && !running) return;
        accepting = false;
        running = false;
        registry.close(new CancellationException("Myelin session closed"));
        wakeup();
        if (Thread.currentThread() != thread && started.get()) {
            try { thread.join(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        CancellationException closed = new CancellationException("Myelin session closed");
        consumers.forEach(demand -> demand.completeExceptionally(closed));
        mailbox.clear();
        events.clear();
        status = "closed";
    }
}
