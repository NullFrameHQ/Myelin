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

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Keeps a bounded recent history to help explain missing terrain snapshots. */
public final class SurfaceCacheTrace {
    public enum Removal { TTL, CAPACITY, SENDER, EXPLICIT }
    private enum Missing {
        NEVER_SCHEDULED, SCHEDULED_NO_RESULT, COMPLETED_NOT_CACHED, TTL, CAPACITY, SENDER, EXPLICIT,
        QUALITY_CLEAR, UNEXPLAINED, HISTORY_LIMIT
    }
    private record Key(int dimension, int x, int z) {}
    private static final int MAX_HISTORY = 65_536;

    private static final class History {
        boolean scheduled;
        boolean completed;
        long lastPut;
        long lastRemoval;
        Removal removal;
    }

    private static final class Counters {
        final LongAdder[] removed = adders(Removal.values().length);
        final LongAdder[] missing = adders(Missing.values().length);
        final LongAdder clears = new LongAdder();
        final LongAdder expiredAgeMillis = new LongAdder();
        final LongAdder evictedAgeMillis = new LongAdder();
    }

    private final ConcurrentHashMap<Key, History> histories = new ConcurrentHashMap<>();
    private final AtomicInteger historySize = new AtomicInteger();
    private volatile Counters counters = new Counters();
    private volatile long lastQualityClear;

    private static LongAdder[] adders(int length) {
        LongAdder[] result = new LongAdder[length];
        for (int i = 0; i < length; i++) result[i] = new LongAdder();
        return result;
    }

    private History history(int dimension, int x, int z) {
        Key key = new Key(dimension, x, z);
        History existing = histories.get(key);
        if (existing != null) return existing;
        if (historySize.incrementAndGet() > MAX_HISTORY) {
            historySize.decrementAndGet();
            return null;
        }
        History created = new History();
        existing = histories.putIfAbsent(key, created);
        if (existing != null) historySize.decrementAndGet();
        return existing != null ? existing : created;
    }

    public void scheduled(int dimension, int x, int z) {
        History history = history(dimension, x, z);
        if (history == null) return;
        synchronized (history) { history.scheduled = true; }
    }

    public void completed(int dimension, int x, int z) {
        History history = history(dimension, x, z);
        if (history == null) return;
        synchronized (history) { history.completed = true; }
    }

    public void put(int dimension, int x, int z) {
        History history = history(dimension, x, z);
        if (history == null) return;
        synchronized (history) { history.lastPut = System.nanoTime(); }
    }

    public void removed(int dimension, int x, int z, Removal reason, long ageMillis) {
        Counters current = counters;
        current.removed[reason.ordinal()].increment();
        if (reason == Removal.TTL) current.expiredAgeMillis.add(Math.max(0, ageMillis));
        if (reason == Removal.CAPACITY) current.evictedAgeMillis.add(Math.max(0, ageMillis));
        History history = history(dimension, x, z);
        if (history == null) return;
        synchronized (history) {
            history.lastRemoval = System.nanoTime();
            history.removal = reason;
        }
    }

    public void qualityClear() {
        lastQualityClear = System.nanoTime();
        counters.clears.increment();
    }

    public void missing(int dimension, int x, int z) {
        History history = history(dimension, x, z);
        Missing reason;
        if (history == null) {
            reason = Missing.HISTORY_LIMIT;
        } else {
            synchronized (history) {
                if (history.lastPut != 0 && lastQualityClear > history.lastPut
                        && lastQualityClear >= history.lastRemoval) {
                    reason = Missing.QUALITY_CLEAR;
                } else if (history.lastRemoval != 0 && history.lastRemoval >= history.lastPut) {
                    reason = switch (history.removal) {
                        case TTL -> Missing.TTL;
                        case CAPACITY -> Missing.CAPACITY;
                        case SENDER -> Missing.SENDER;
                        case EXPLICIT -> Missing.EXPLICIT;
                    };
                } else if (history.lastPut != 0) {
                    reason = Missing.UNEXPLAINED;
                } else if (!history.scheduled && !history.completed) {
                    reason = Missing.NEVER_SCHEDULED;
                } else if (history.completed) {
                    reason = Missing.COMPLETED_NOT_CACHED;
                } else {
                    reason = Missing.SCHEDULED_NO_RESULT;
                }
            }
        }
        counters.missing[reason.ordinal()].increment();
    }

    public void resetCounters() {
        // Preserve history: a result removed before reset can explain a later miss.
        counters = new Counters();
    }

    public String summary() {
        Counters current = counters;
        long ttl = current.removed[Removal.TTL.ordinal()].sum();
        long capacity = current.removed[Removal.CAPACITY.ordinal()].sum();
        return String.format(Locale.ROOT,
                "Cache removals: TTL %,d | Capacity %,d | Sender %,d | Explicit %,d | Quality clears %,d\n"
                        + "Mean removal age: TTL %.2f s | Capacity %.2f s\n"
                        + "Pink history: Never scheduled %,d | Scheduled/no result %,d | Completed/not cached %,d\n"
                        + "Pink history: TTL %,d | Capacity %,d | Sender %,d | Explicit %,d | Quality clear %,d\n"
                        + "Pink history: Unexplained %,d | History limit %,d (tracking %,d/%,d positions)",
                ttl, capacity, current.removed[Removal.SENDER.ordinal()].sum(),
                current.removed[Removal.EXPLICIT.ordinal()].sum(), current.clears.sum(),
                ttl == 0 ? 0.0 : current.expiredAgeMillis.sum() / (1000.0 * ttl),
                capacity == 0 ? 0.0 : current.evictedAgeMillis.sum() / (1000.0 * capacity),
                current.missing[Missing.NEVER_SCHEDULED.ordinal()].sum(),
                current.missing[Missing.SCHEDULED_NO_RESULT.ordinal()].sum(),
                current.missing[Missing.COMPLETED_NOT_CACHED.ordinal()].sum(),
                current.missing[Missing.TTL.ordinal()].sum(),
                current.missing[Missing.CAPACITY.ordinal()].sum(),
                current.missing[Missing.SENDER.ordinal()].sum(),
                current.missing[Missing.EXPLICIT.ordinal()].sum(),
                current.missing[Missing.QUALITY_CLEAR.ordinal()].sum(),
                current.missing[Missing.UNEXPLAINED.ordinal()].sum(),
                current.missing[Missing.HISTORY_LIMIT.ordinal()].sum(), histories.size(), MAX_HISTORY);
    }
}
