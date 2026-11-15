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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Tracks ownership of snapshot requests and whether their results are cached or resident. */
public final class SnapshotWorkRegistry<V> {
    public record Key(long epoch, int dimension, int x, int z) { }

    enum State { MISSING, RESERVED, QUEUED, RUNNING, READY, RESIDENT }

    static final class Lease<V> {
        private final Key key;
        private final CompletableFuture<V> result = new CompletableFuture<>();
        private final long createdNanos = System.nanoTime();
        private State state = State.RESERVED;
        private volatile boolean demand;

        private Lease(Key key) { this.key = key; }
        Key key() { return key; }
        long createdNanos() { return createdNanos; }
        boolean hasDemand() { return demand; }
        CompletableFuture<V> future() { return result.thenApply(value -> value); }
    }

    record Reservation<V>(Lease<V> lease, boolean owner) {
        CompletableFuture<V> future() { return lease.future(); }
    }

    private static final class Entry<V> {
        Lease<V> lease;
        V ready;
        Object cacheToken;
        long expiresAtMillis;
        Object residentToken;
    }

    private final Map<Key, Entry<V>> entries = new HashMap<>();
    private long epoch;
    private boolean closed;

    public SnapshotWorkRegistry(long initialEpoch) { epoch = initialEpoch; }

    synchronized long epoch() { return epoch; }
    synchronized int size() { return entries.size(); }

    synchronized State state(Key key, long nowMillis) {
        if (closed || key.epoch != epoch) return State.MISSING;
        Entry<V> entry = entries.get(key);
        if (entry == null) return State.MISSING;
        expire(entry, nowMillis);
        if (entry.lease != null) return entry.lease.state;
        if (entry.ready != null) return State.READY;
        if (entry.residentToken != null) return State.RESIDENT;
        entries.remove(key);
        return State.MISSING;
    }

    public boolean isCovered(Key key, long nowMillis) { return state(key, nowMillis) != State.MISSING; }

    synchronized boolean isPending(Key key) {
        Entry<V> entry = entries.get(key);
        return !closed && key.epoch == epoch && entry != null && entry.lease != null;
    }

    synchronized Reservation<V> reserve(Key key) {
        return reserveInternal(key, false, System.currentTimeMillis());
    }

    synchronized Reservation<V> reservePrefetch(Key key) {
        return reserveInternal(key, true, System.currentTimeMillis());
    }

    private Reservation<V> reserveInternal(Key key, boolean prefetch, long nowMillis) {
        Objects.requireNonNull(key, "key");
        if (closed || key.epoch != epoch) throw new IllegalStateException("Obsolete snapshot session or quality");
        Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>());
        expire(entry, nowMillis);
        if (entry.lease != null) {
            if (!prefetch) entry.lease.demand = true;
            return new Reservation<>(entry.lease, false);
        }
        if (entry.ready != null) {
            if (prefetch) return null;
            Lease<V> completed = new Lease<>(key);
            completed.result.complete(entry.ready);
            return new Reservation<>(completed, false);
        }
        if (prefetch && entry.residentToken != null) return null;
        Lease<V> lease = new Lease<>(key);
        lease.demand = !prefetch;
        entry.lease = lease;
        return new Reservation<>(lease, true);
    }

    synchronized boolean current(Lease<V> lease) {
        Entry<V> entry = entries.get(lease.key);
        return !closed && lease.key.epoch == epoch && entry != null && entry.lease == lease;
    }

    synchronized boolean markQueued(Lease<V> lease) {
        if (!current(lease)) return false;
        if (lease.state == State.RESERVED) lease.state = State.QUEUED;
        return true;
    }

    synchronized boolean markRunning(Lease<V> lease) {
        if (!current(lease)) return false;
        lease.state = State.RUNNING;
        return true;
    }

    /** Locks cache publication against invalidation, then completes futures outside the registry lock. */
    boolean publish(Lease<V> lease, V value, long expiresAtMillis, Consumer<V> cacheInstaller) {
        Objects.requireNonNull(value, "snapshot");
        Objects.requireNonNull(cacheInstaller, "cacheInstaller");
        Throwable failure = null;
        synchronized (this) {
            if (!current(lease)) return false;
            Entry<V> entry = entries.get(lease.key);
            entry.ready = value;
            entry.cacheToken = value;
            entry.expiresAtMillis = expiresAtMillis;
            try {
                cacheInstaller.accept(value);
            } catch (Throwable error) {
                if (entry.ready == value) clearReady(entry);
                failure = error;
            }
        }
        // Keep the lease installed until resolution, allowing invalidation to settle it too.
        if (failure == null) lease.result.complete(value);
        else lease.result.completeExceptionally(failure);
        synchronized (this) {
            Entry<V> entry = entries.get(lease.key);
            if (entry != null && entry.lease == lease) {
                entry.lease = null;
                removeIfEmpty(lease.key, entry);
            }
        }
        return failure == null;
    }

    boolean complete(Lease<V> lease, V value) {
        return publish(lease, value, Long.MAX_VALUE, ignored -> { });
    }

    boolean fail(Lease<V> lease, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (this) {
            if (!current(lease)) return false;
            Entry<V> entry = entries.get(lease.key);
            entry.lease = null;
            removeIfEmpty(lease.key, entry);
        }
        lease.result.completeExceptionally(cause);
        return true;
    }

    /** Drops obsolete prefetch work under the same lock that accepts new demand. */
    boolean failIfUnrequested(Lease<V> lease, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (this) {
            if (!current(lease) || lease.demand) return false;
            Entry<V> entry = entries.get(lease.key);
            entry.lease = null;
            removeIfEmpty(lease.key, entry);
        }
        lease.result.completeExceptionally(cause);
        return true;
    }

    synchronized void ready(Key key, V value, long expiresAtMillis, Object cacheToken) {
        if (closed || key.epoch != epoch) return;
        Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>());
        entry.ready = Objects.requireNonNull(value, "snapshot");
        entry.cacheToken = Objects.requireNonNull(cacheToken, "cacheToken");
        entry.expiresAtMillis = expiresAtMillis;
    }

    synchronized void removeReady(Key key, Object cacheToken) {
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.cacheToken == cacheToken) {
            clearReady(entry);
            removeIfEmpty(key, entry);
        }
    }

    synchronized void markResident(Key key, Object chunkToken) {
        if (closed || key.epoch != epoch) return;
        entries.computeIfAbsent(key, ignored -> new Entry<>()).residentToken = Objects.requireNonNull(chunkToken);
    }

    synchronized void unload(Key key, Object chunkToken) {
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.residentToken == chunkToken) {
            entry.residentToken = null;
            removeIfEmpty(key, entry);
        }
    }

    void invalidate(long newEpoch, Throwable cause) {
        ArrayList<Lease<V>> outstanding;
        synchronized (this) {
            epoch = newEpoch;
            outstanding = outstanding();
            entries.clear();
        }
        outstanding.forEach(lease -> lease.result.completeExceptionally(cause));
    }

    void close(Throwable cause) {
        ArrayList<Lease<V>> outstanding;
        synchronized (this) {
            closed = true;
            outstanding = outstanding();
            entries.clear();
        }
        outstanding.forEach(lease -> lease.result.completeExceptionally(cause));
    }

    synchronized void prune(long nowMillis) {
        entries.entrySet().removeIf(item -> {
            Entry<V> entry = item.getValue();
            expire(entry, nowMillis);
            return entry.lease == null && entry.ready == null && entry.residentToken == null;
        });
    }

    private ArrayList<Lease<V>> outstanding() {
        ArrayList<Lease<V>> result = new ArrayList<>();
        for (Entry<V> entry : entries.values()) if (entry.lease != null) result.add(entry.lease);
        return result;
    }

    private void removeIfEmpty(Key key, Entry<V> entry) {
        if (entry.lease == null && entry.ready == null && entry.residentToken == null) entries.remove(key);
    }

    private static void expire(Entry<?> entry, long nowMillis) {
        if (entry.ready != null && nowMillis >= entry.expiresAtMillis) clearReady(entry);
    }

    private static void clearReady(Entry<?> entry) {
        entry.ready = null;
        entry.cacheToken = null;
        entry.expiresAtMillis = 0;
    }
}
