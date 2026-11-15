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

import com.me.nullframe.myelin.server.diagnostics.SurfaceCacheTrace;

import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/** Allows concurrent reads and locks mutations so capacity checks and eviction stay consistent. */
public class SurfaceChunkCache {
    private record Key(int dimensionId, int chunkX, int chunkZ) { }

    public enum RemovalReason { TTL, CAPACITY, SENDER, EXPLICIT, REPLACED, CLEAR }

    /** Identifies one cache insertion. Replacing a snapshot creates a new token, even for the same object. */
    public static final class EntryToken {
        private final Key key;
        private final SurfaceChunkSnapshot snapshot;
        private final long insertionTimeMillis;
        private final long expiresAtMillis;

        private EntryToken(Key key, SurfaceChunkSnapshot snapshot, long inserted, long ttlMillis) {
            this.key = key;
            this.snapshot = snapshot;
            insertionTimeMillis = inserted;
            expiresAtMillis = ttlMillis > Long.MAX_VALUE - inserted ? Long.MAX_VALUE : inserted + ttlMillis;
        }

        public SurfaceChunkSnapshot snapshot() { return snapshot; }
        public long expiresAtMillis() { return expiresAtMillis; }
    }

    public interface Listener {
        default void inserted(EntryToken token) { }
        default void removed(EntryToken token, RemovalReason reason) { }
    }

    private record RemovalEvent(EntryToken token, RemovalReason reason) { }

    private final int maxEntries;
    private final long ttlMillis;
    private final SurfaceCacheTrace trace;
    private final ConcurrentHashMap<Key, EntryToken> cache = new ConcurrentHashMap<>();
    private final ReentrantLock mutationLock = new ReentrantLock();
    private volatile long estimatedBytes;
    private volatile Listener listener;
    private volatile Predicate<SurfaceChunkSnapshot> evictionPreference = ignored -> false;

    public SurfaceChunkCache(int maxEntries, long ttlMillis) {
        this(maxEntries, ttlMillis, null);
    }

    public SurfaceChunkCache(int maxEntries, long ttlMillis, SurfaceCacheTrace trace) {
        if (maxEntries < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        if (ttlMillis < 0) throw new IllegalArgumentException("Cache TTL cannot be negative");
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        this.trace = trace;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /** Selects snapshots to evict first. This predicate must not call into the work registry. */
    public void setEvictionPreference(Predicate<SurfaceChunkSnapshot> preference) {
        evictionPreference = Objects.requireNonNull(preference);
    }

    public int maxEntries() { return maxEntries; }
    public long ttlMillis() { return ttlMillis; }
    public int remainingCapacity() { return Math.max(0, maxEntries - cache.size()); }

    /** Counts unwanted entries within a scan limit; it may miss some entries beyond that limit. */
    public int countUnwanted(int limit) {
        if (limit <= 0) return 0;
        Predicate<SurfaceChunkSnapshot> preferred = evictionPreference;
        int found = 0;
        int checked = 0;
        int budget = (int) Math.min(8192L, Math.max(256L, (long) limit * 16L));
        for (EntryToken candidate : cache.values()) {
            if (preferred.test(candidate.snapshot) && ++found >= limit) break;
            if (++checked >= budget) break;
        }
        return found;
    }

    /**
     * Makes room for prefetch work by removing expired or unwanted snapshots.
     * The scan is bounded, so a request may need to wait even if more entries could be evicted.
     */
    public boolean prepareCapacity(int additional) {
        if (additional <= 0) return true;
        if (additional > maxEntries) return false;
        List<RemovalEvent> removals = new ArrayList<>();
        boolean enough;
        mutationLock.lock();
        try {
            int deficit = additional - Math.max(0, maxEntries - cache.size());
            if (deficit <= 0) return true;
            long now = System.currentTimeMillis();
            Predicate<SurfaceChunkSnapshot> preferred = evictionPreference;
            int checked = 0;
            int budget = (int) Math.min(8192L, Math.max(256L, (long) additional * 16L));
            for (EntryToken candidate : cache.values()) {
                RemovalReason reason = now >= candidate.expiresAtMillis ? RemovalReason.TTL
                        : preferred.test(candidate.snapshot) ? RemovalReason.CAPACITY : null;
                if (reason != null && cache.remove(candidate.key, candidate)) {
                    recordRemoval(candidate, reason);
                    removals.add(new RemovalEvent(candidate, reason));
                    if (--deficit <= 0) break;
                }
                if (++checked >= budget) break;
            }
            enough = maxEntries - cache.size() >= additional;
        } finally {
            mutationLock.unlock();
        }
        for (RemovalEvent removal : removals) notifyRemoved(removal.token, removal.reason);
        return enough;
    }

    private static Key key(int dimensionId, int chunkX, int chunkZ) {
        return new Key(dimensionId, chunkX, chunkZ);
    }

    public boolean contains(int dimensionId, int chunkX, int chunkZ) {
        return get(dimensionId, chunkX, chunkZ) != null;
    }

    public SurfaceChunkSnapshot get(int dimensionId, int chunkX, int chunkZ) {
        Key key = key(dimensionId, chunkX, chunkZ);
        EntryToken entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() < entry.expiresAtMillis) return entry.snapshot;
        boolean removed;
        mutationLock.lock();
        try {
            removed = cache.remove(key, entry);
            if (removed) recordRemoval(entry, RemovalReason.TTL);
        } finally {
            mutationLock.unlock();
        }
        if (removed) notifyRemoved(entry, RemovalReason.TTL);
        // A refresh raced the expired read: return its current result rather than a false miss.
        EntryToken replacement = cache.get(key);
        return replacement != null && System.currentTimeMillis() < replacement.expiresAtMillis
                ? replacement.snapshot : null;
    }

    public void put(SurfaceChunkSnapshot snapshot) { putWithToken(snapshot); }

    public EntryToken putWithToken(SurfaceChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Key key = key(snapshot.getDimensionId(), snapshot.getChunkX(), snapshot.getChunkZ());
        EntryToken entry = new EntryToken(key, snapshot, System.currentTimeMillis(), ttlMillis);
        List<RemovalEvent> removals = new ArrayList<>(2);
        mutationLock.lock();
        try {
            EntryToken previous = cache.put(key, entry);
            estimatedBytes += snapshot.estimatedBytes() - (previous == null ? 0 : previous.snapshot.estimatedBytes());
            if (trace != null) trace.put(key.dimensionId, key.chunkX, key.chunkZ);
            if (previous != null) removals.add(new RemovalEvent(previous, RemovalReason.REPLACED));
            evictExcess(removals);
        } finally {
            mutationLock.unlock();
        }
        // Never enter registry/scheduler code while holding the cache mutation lock.
        for (RemovalEvent removal : removals) notifyRemoved(removal.token, removal.reason);
        Listener current = listener;
        if (current != null) current.inserted(entry);
        return entry;
    }

    public boolean isCurrent(EntryToken token) { return cache.get(token.key) == token; }

    public EntryToken currentToken(int dimensionId, int chunkX, int chunkZ) {
        EntryToken entry = cache.get(key(dimensionId, chunkX, chunkZ));
        return entry != null && System.currentTimeMillis() < entry.expiresAtMillis ? entry : null;
    }

    /** Keep eviction under the mutation lock so concurrent puts do not evict more entries than needed. */
    private void evictExcess(List<RemovalEvent> removals) {
        if (cache.size() <= maxEntries) return;
        Predicate<SurfaceChunkSnapshot> preferred = evictionPreference;
        int checked = 0;
        for (EntryToken candidate : cache.values()) {
            if (cache.size() <= maxEntries) return;
            if (preferred.test(candidate.snapshot)) evict(candidate, removals);
            if (++checked == 64) break;
        }
        for (EntryToken candidate : cache.values()) {
            if (cache.size() <= maxEntries) return;
            evict(candidate, removals);
        }
    }

    private void evict(EntryToken candidate, List<RemovalEvent> removals) {
        if (cache.remove(candidate.key, candidate)) {
            recordRemoval(candidate, RemovalReason.CAPACITY);
            removals.add(new RemovalEvent(candidate, RemovalReason.CAPACITY));
        }
    }

    public int size() { return cache.size(); }
    public long estimatedBytes() { return estimatedBytes; }

    public String diagnosticsPolicy() {
        return "Snapshot cache: " + cache.size() + "/" + maxEntries
                + " entries | approx " + estimatedBytes / (1024L * 1024L) + " MiB owned data"
                + " | TTL " + ttlMillis / 1000L + " s since insertion";
    }

    public void clear() {
        List<EntryToken> removed;
        mutationLock.lock();
        try {
            removed = new ArrayList<>(cache.values());
            cache.clear();
            estimatedBytes = 0;
            if (trace != null) trace.qualityClear();
        } finally {
            mutationLock.unlock();
        }
        for (EntryToken token : removed) notifyRemoved(token, RemovalReason.CLEAR);
    }

    public void remove(int dimensionId, int chunkX, int chunkZ) {
        remove(dimensionId, chunkX, chunkZ, SurfaceCacheTrace.Removal.EXPLICIT);
    }

    void remove(int dimensionId, int chunkX, int chunkZ, SurfaceCacheTrace.Removal reason) {
        RemovalReason mapped = switch (reason) {
            case TTL -> RemovalReason.TTL;
            case CAPACITY -> RemovalReason.CAPACITY;
            case SENDER -> RemovalReason.SENDER;
            case EXPLICIT -> RemovalReason.EXPLICIT;
        };
        EntryToken removed;
        mutationLock.lock();
        try {
            removed = cache.remove(key(dimensionId, chunkX, chunkZ));
            if (removed != null) recordRemoval(removed, mapped);
        } finally {
            mutationLock.unlock();
        }
        if (removed != null) notifyRemoved(removed, mapped);
    }

    private void recordRemoval(EntryToken token, RemovalReason reason) {
        estimatedBytes -= token.snapshot.estimatedBytes();
        if (trace == null) return;
        SurfaceCacheTrace.Removal mapped = switch (reason) {
            case TTL -> SurfaceCacheTrace.Removal.TTL;
            case CAPACITY -> SurfaceCacheTrace.Removal.CAPACITY;
            case SENDER -> SurfaceCacheTrace.Removal.SENDER;
            default -> SurfaceCacheTrace.Removal.EXPLICIT;
        };
        trace.removed(token.key.dimensionId, token.key.chunkX, token.key.chunkZ, mapped,
                Math.max(0L, System.currentTimeMillis() - token.insertionTimeMillis));
    }

    private void notifyRemoved(EntryToken token, RemovalReason reason) {
        Listener current = listener;
        if (current != null) current.removed(token, reason);
    }
}
