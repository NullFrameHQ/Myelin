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
import java.util.List;
import java.util.function.BiConsumer;

/** Shared geometry for 8x16-chunk strips. */
public final class StripGeometry {
    public static final int WIDTH = 8;
    public static final int HEIGHT = 16;

    public record Range(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        long size() { return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1); }

        void forEach(BiConsumer<Integer, Integer> consumer) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) consumer.accept(x, z);
            }
        }

        /** Returns up to four rectangles covering the part outside the previous range. */
        List<Range> outside(Range previous) {
            if (previous == null || previous.maxX < minX || previous.minX > maxX
                    || previous.maxZ < minZ || previous.minZ > maxZ) return List.of(this);
            int left = Math.max(minX, previous.minX), right = Math.min(maxX, previous.maxX);
            int top = Math.max(minZ, previous.minZ), bottom = Math.min(maxZ, previous.maxZ);
            List<Range> result = new ArrayList<>(4);
            if (minX < left) result.add(new Range(minX, minZ, left - 1, maxZ));
            if (right < maxX) result.add(new Range(right + 1, minZ, maxX, maxZ));
            if (minZ < top) result.add(new Range(left, minZ, right, top - 1));
            if (bottom < maxZ) result.add(new Range(left, bottom + 1, right, maxZ));
            return result;
        }
    }

    static Range window(int x, int z, int radius) {
        return window(x, z, radius, 0, PrefetchDirection.SOUTH);
    }

    /** Keeps the visible square and adds the prefetch margin only in the selected direction. */
    static Range window(int x, int z, int radius, int margin, PrefetchDirection direction) {
        int safeRadius = Math.max(0, radius);
        int extra = Math.max(0, margin);
        int minX = x - safeRadius - (direction.x < 0 ? extra : 0);
        int maxX = x + safeRadius + (direction.x > 0 ? extra : 0);
        int minZ = z - safeRadius - (direction.z < 0 ? extra : 0);
        int maxZ = z + safeRadius + (direction.z > 0 ? extra : 0);
        return new Range(Math.floorDiv(minX, WIDTH), Math.floorDiv(minZ, HEIGHT),
                Math.floorDiv(maxX, WIDTH), Math.floorDiv(maxZ, HEIGHT));
    }

    static boolean intersectsVisible(int gridX, int gridZ, int x, int z, int radius) {
        int originX = gridX * WIDTH, originZ = gridZ * HEIGHT;
        return originX <= x + radius && originX + WIDTH - 1 >= x - radius
                && originZ <= z + radius && originZ + HEIGHT - 1 >= z - radius;
    }

    private StripGeometry() { }
}
