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

import com.me.nullframe.myelin.DimensionIds;

import net.minecraft.server.world.ServerWorld;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class MyelinChunkTracking {
    private static final Set<Long> topOnlyChunkKeys = ConcurrentHashMap.newKeySet();
    private static final Set<Long> temporaryTopOnlyChunks = ConcurrentHashMap.newKeySet();

    private MyelinChunkTracking() {
    }

    private static long chunkKey(ServerWorld world, int chunkX, int chunkZ) {
        int dimensionId = DimensionIds.fromWorld(world);
        return chunkKey(dimensionId, chunkX, chunkZ);
    }

    private static long chunkKey(int dimensionId, int chunkX, int chunkZ) {
        long hash = 0xcbf29ce484222325L;
        hash = (hash ^ dimensionId) * 0x100000001b3L;
        hash = (hash ^ chunkX) * 0x100000001b3L;
        hash = (hash ^ chunkZ) * 0x100000001b3L;
        return hash;
    }

    public static boolean shouldUseTopOnlyGeneration(ServerWorld world, int chunkX, int chunkZ) {
        return topOnlyChunkKeys.contains(chunkKey(world, chunkX, chunkZ));
    }

    public static boolean isTemporaryTopOnlyChunk(ServerWorld world, int chunkX, int chunkZ) {
        return temporaryTopOnlyChunks.contains(chunkKey(world, chunkX, chunkZ));
    }

    public static void clearMyelinTrackingForChunk(ServerWorld world, int chunkX, int chunkZ) {
        long k = chunkKey(world, chunkX, chunkZ);
        topOnlyChunkKeys.remove(k);
        temporaryTopOnlyChunks.remove(k);
    }

    /** Clears both position-tracking sets. */
    public static void clearAll() {
        topOnlyChunkKeys.clear();
        temporaryTopOnlyChunks.clear();
    }

    public static void registerTopOnlyChunk(ServerWorld world, int chunkX, int chunkZ) {
        long k = chunkKey(world, chunkX, chunkZ);
        topOnlyChunkKeys.add(k);
    }

    public static void registerTemporaryTopOnlyChunk(ServerWorld world, int chunkX, int chunkZ) {
        long k = chunkKey(world, chunkX, chunkZ);
        temporaryTopOnlyChunks.add(k);
    }

    public static int topOnlyKeyCount() {
        return topOnlyChunkKeys.size();
    }

    public static int temporaryTopOnlyCount() {
        return temporaryTopOnlyChunks.size();
    }

}
