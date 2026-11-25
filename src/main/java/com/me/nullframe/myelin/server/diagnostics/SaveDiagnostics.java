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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts chunk save attempts so that unexpected writes to the world's region
 * files can be found. Only chunks that vanilla would actually write
 * (needsSaving was true) are counted. Reported by /myelin saves.
 */
public final class SaveDiagnostics {
    private static final LongAdder skippedTracked = new LongAdder();
    private static final LongAdder skippedPartial = new LongAdder();
    private static final LongAdder writtenCount = new LongAdder();
    private static final LongAdder writtenNanos = new LongAdder();
    private static final Map<String, LongAdder> writtenByStatus = new ConcurrentHashMap<>();

    private SaveDiagnostics() {
    }

    /** A chunk Myelin tracks as temporary top-only was kept out of the save. */
    public static void recordSkippedTracked() {
        skippedTracked.increment();
    }

    /** An unfinished chunk was kept out of the save because a player is in Flight Mode. */
    public static void recordSkippedPartial() {
        skippedPartial.increment();
    }

    /** A chunk is about to be written by vanilla. */
    public static void recordWrite(String status) {
        writtenCount.increment();
        writtenByStatus.computeIfAbsent(status, key -> new LongAdder()).increment();
    }

    /** Time vanilla spent inside save(Chunk), serialization included. */
    public static void recordWriteNanos(long nanos) {
        writtenNanos.add(nanos);
    }

    public static void reset() {
        skippedTracked.reset();
        skippedPartial.reset();
        writtenCount.reset();
        writtenNanos.reset();
        writtenByStatus.clear();
    }

    public static String summary() {
        long written = writtenCount.sum();
        double millis = writtenNanos.sum() / 1_000_000.0;
        StringBuilder text = new StringBuilder();
        text.append("[Myelin saves] written to disk: ").append(written)
                .append(" chunks, ").append(String.format("%.1f", millis)).append(" ms in save()");
        if (written > 0) {
            text.append(" (").append(String.format("%.3f", millis / written)).append(" ms each)");
        }
        text.append("; skipped tracked: ").append(skippedTracked.sum())
                .append(", skipped unfinished: ").append(skippedPartial.sum());
        if (!writtenByStatus.isEmpty()) {
            Map<String, Long> sorted = new TreeMap<>();
            writtenByStatus.forEach((status, count) -> sorted.put(status, count.sum()));
            text.append("\n[Myelin saves] written by status: ").append(sorted);
        }
        return text.toString();
    }
}
