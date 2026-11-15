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

/** Prefetch directions chosen from movement, or facing direction while stationary. */
public enum PrefetchDirection {
    NORTH(0, -1), NORTH_EAST(1, -1), EAST(1, 0), SOUTH_EAST(1, 1),
    SOUTH(0, 1), SOUTH_WEST(-1, 1), WEST(-1, 0), NORTH_WEST(-1, -1);

    final int x;
    final int z;
    private final double unitX;
    private final double unitZ;

    PrefetchDirection(int x, int z) {
        this.x = x;
        this.z = z;
        double length = Math.sqrt(x * x + z * z);
        unitX = x / length;
        unitZ = z / length;
    }

    /** Tracks movement on the server thread; only the chosen direction is sent to the planner. */
    public static final class Tracker {
        private static final double MIN_BLOCKS_PER_TICK = 0.01;
        private static final double TELEPORT_BLOCKS_PER_TICK = 256.0;
        // Keep the current direction for five degrees beyond its usual sector boundary.
        private static final double KEEP_HEADING_DOT = Math.cos(Math.toRadians(27.5));
        private boolean initialized;
        private int dimension;
        private long sampledTick;
        private double lastX;
        private double lastZ;
        private double movementX;
        private double movementZ;
        private PrefetchDirection heading;

        public PrefetchDirection sample(int dimension, long tick, double x, double z, float yaw) {
            if (!initialized || this.dimension != dimension || tick < sampledTick) {
                initialized = true;
                this.dimension = dimension;
                sampledTick = tick;
                lastX = x;
                lastZ = z;
                movementX = movementZ = 0;
                heading = null;
            } else if (tick != sampledTick) {
                long elapsed = tick - sampledTick;
                double dx = (x - lastX) / elapsed, dz = (z - lastZ) / elapsed;
                double distance = Math.hypot(dx, dz);
                boolean moving = distance >= MIN_BLOCKS_PER_TICK && distance <= TELEPORT_BLOCKS_PER_TICK;
                movementX = moving ? dx / distance : 0;
                movementZ = moving ? dz / distance : 0;
                if (distance > TELEPORT_BLOCKS_PER_TICK) heading = null;
                sampledTick = tick;
                lastX = x;
                lastZ = z;
            }

            double directionX = movementX, directionZ = movementZ;
            if (directionX == 0 && directionZ == 0) {
                double radians = Math.toRadians(yaw);
                directionX = -Math.sin(radians);
                directionZ = Math.cos(radians);
            }
            if (heading != null && directionX * heading.unitX + directionZ * heading.unitZ >= KEEP_HEADING_DOT)
                return heading;

            double bestDot = -Double.MAX_VALUE;
            for (PrefetchDirection candidate : values()) {
                double dot = directionX * candidate.unitX + directionZ * candidate.unitZ;
                if (dot > bestDot) {
                    bestDot = dot;
                    heading = candidate;
                }
            }
            return heading;
        }
    }
}
