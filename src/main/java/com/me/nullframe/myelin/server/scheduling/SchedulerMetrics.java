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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Records latency in a fixed set of buckets, measured in powers of two microseconds. */
final class SchedulerMetrics {
    static final class Latency {
        private final AtomicLongArray buckets = new AtomicLongArray(48);
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        void record(long elapsedNanos) {
            long duration = Math.max(0, elapsedNanos);
            long micros = Math.max(1, duration / 1_000);
            int bucket = Math.min(47, 64 - Long.numberOfLeadingZeros(micros - 1));
            buckets.incrementAndGet(bucket);
            nanos.add(duration);
            count.increment();
        }

        long count() { return count.sum(); }
        long totalNanos() { return nanos.sum(); }

        double percentile(double fraction) {
            long target = Math.max(1, (long) Math.ceil(count.sum() * fraction));
            long running = 0;
            for (int i = 0; i < buckets.length(); i++) {
                running += buckets.get(i);
                if (running >= target) return (1L << i) / 1000.0;
            }
            return 0;
        }

        String summary() {
            long samples = count.sum();
            return String.format(Locale.ROOT, "%d samples avg %.3f ms p50≤%.3f p95≤%.3f p99≤%.3f ms",
                    samples, samples == 0 ? 0 : nanos.sum() / (samples * 1_000_000.0),
                    percentile(.50), percentile(.95), percentile(.99));
        }
    }

    final LongAdder reservations = new LongAdder();
    final LongAdder reused = new LongAdder();
    final LongAdder repairs = new LongAdder();
    final LongAdder obsolete = new LongAdder();
    final LongAdder batches = new LongAdder();
    final LongAdder auditPositions = new LongAdder();
    final Latency planning = new Latency();
    final Latency queue = new Latency();
    final Latency nativeWork = new Latency();
    final Latency firstResult = new Latency();
    final Latency chunkReady = new Latency();
    final Latency publication = new Latency();
    final Latency demand = new Latency();
}
