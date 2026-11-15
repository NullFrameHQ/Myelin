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

package com.me.nullframe.myelin.client.render;

import com.nullframe.core.StepTimings;

import com.me.nullframe.myelin.network.SurfaceChunkS2CPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public final class SurfaceChunkRenderer {
    private static final Logger LOGGER = LogManager.getLogger("myelin-renderer");
    private static final long REAL_CHUNK_HANDOFF_MILLIS = 600L;
    private static final long FOREIGN_DIMENSION_PREVIEW_TTL_MILLIS = 15_000L;
    // Keep newly received previews briefly, even outside the current retention radius.
    // Nearby Minecraft chunks may still be catching up after movement or a teleport.
    private static final long RECENT_PREVIEW_GRACE_MILLIS = 3_000L;

    public static final class PreviewChunk {
        private final int dimensionId;
        private final int chunkX;
        private final int chunkZ;
        private final int[] heightmap;
        private final byte[] surfaceTypes;
        private final long receivedAtMillis;

        private PreviewChunk(int dimensionId,
                             int chunkX,
                             int chunkZ,
                             int[] heightmap,
                             byte[] surfaceTypes,
                             long receivedAtMillis) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.heightmap = heightmap.clone();
            this.surfaceTypes = surfaceTypes.clone();
            this.receivedAtMillis = receivedAtMillis;
        }

        public int dimensionId() {
            return dimensionId;
        }

        public int chunkX() {
            return chunkX;
        }

        public int chunkZ() {
            return chunkZ;
        }

        public long receivedAtMillis() {
            return receivedAtMillis;
        }

        int[] heightmapView() {
            return heightmap;
        }

        public int[] copyHeightmap() {
            return heightmap.clone();
        }

        byte[] surfaceTypesView() {
            return surfaceTypes;
        }

        public byte[] copySurfaceTypes() {
            return surfaceTypes.clone();
        }
    }

    private static final Map<String, PreviewChunk> SURFACE_PREVIEWS = new ConcurrentHashMap<>();
    private static final Map<String, Long> REAL_CHUNK_HANDOFFS = new ConcurrentHashMap<>();

    private SurfaceChunkRenderer() {
    }

    public static void handleSurfaceChunk(SurfaceChunkS2CPacket payload, ClientPlayNetworkHandler handler) {
        storeSurfacePreview(
                payload.dimensionId(),
                payload.chunkX(),
                payload.chunkZ(),
                payload.copyHeightmap(),
                payload.copySurfaceTypes()
        );
    }

    /** Stores one cached preview from either a single-chunk packet or a batch entry. */
    public static void storeSurfacePreview(int dimensionId, int chunkX, int chunkZ,
                                           int[] heightmap, byte[] surfaceTypes) {
        long tStore = System.nanoTime();
        String key = key(dimensionId, chunkX, chunkZ);
        REAL_CHUNK_HANDOFFS.remove(key);
        SURFACE_PREVIEWS.put(
                key,
                new PreviewChunk(
                        dimensionId,
                        chunkX,
                        chunkZ,
                        heightmap,
                        surfaceTypes,
                        System.currentTimeMillis()
                )
        );
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Stored surface preview for dim={} chunk [{},{}] total={}",
                    dimensionId, chunkX, chunkZ, SURFACE_PREVIEWS.size()
            );
        }
        StepTimings.record(StepTimings.Step.CLIENT_STORE, tStore);
    }

    public static int previewCount() {
        return SURFACE_PREVIEWS.size();
    }

    public static void clearSurfaceOnlyFlag(int dimensionId, int chunkX, int chunkZ) {
        String key = key(dimensionId, chunkX, chunkZ);
        SURFACE_PREVIEWS.remove(key);
        REAL_CHUNK_HANDOFFS.remove(key);
    }

    /** Clears previews and handoff fades inside the square around these chunk coordinates. */
    public static void clearPreviewsChebyshev(int dimensionId,
                                              int centerChunkX,
                                              int centerChunkZ,
                                              int radiusInclusive) {
        if (radiusInclusive < 0) {
            clearAll();
            return;
        }
        SURFACE_PREVIEWS.keySet().removeIf(k ->
                keyWithinChebyshev(k, dimensionId, centerChunkX, centerChunkZ, radiusInclusive));
        REAL_CHUNK_HANDOFFS.keySet().removeIf(k ->
                keyWithinChebyshev(k, dimensionId, centerChunkX, centerChunkZ, radiusInclusive));
    }

    private static boolean keyWithinChebyshev(String key,
                                             int dimensionId,
                                             int centerChunkX,
                                             int centerChunkZ,
                                             int radiusInclusive) {
        int colon = key.indexOf(':');
        int comma = key.indexOf(',');
        if (colon <= 0 || comma <= colon + 1) {
            return false;
        }
        try {
            int dim = Integer.parseInt(key.substring(0, colon));
            if (dim != dimensionId) {
                return false;
            }
            int chunkX = Integer.parseInt(key.substring(colon + 1, comma));
            int chunkZ = Integer.parseInt(key.substring(comma + 1));
            int d = Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
            return d <= radiusInclusive;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static void beginRealChunkHandoff(int dimensionId, int chunkX, int chunkZ) {
        String key = key(dimensionId, chunkX, chunkZ);
        if (!SURFACE_PREVIEWS.containsKey(key)) {
            return;
        }
        REAL_CHUNK_HANDOFFS.put(key, System.currentTimeMillis() + REAL_CHUNK_HANDOFF_MILLIS);
    }

    public static boolean isSurfaceOnly(int dimensionId, int chunkX, int chunkZ) {
        return SURFACE_PREVIEWS.containsKey(key(dimensionId, chunkX, chunkZ));
    }

    public static Collection<PreviewChunk> snapshotSurfacePreviews(int dimensionId) {
        pruneOrphanedHandoffs(System.currentTimeMillis());
        ArrayList<PreviewChunk> snapshot = new ArrayList<>();
        for (PreviewChunk preview : SURFACE_PREVIEWS.values()) {
            if (expireFinishedHandoff(preview.dimensionId(), preview.chunkX(), preview.chunkZ())) {
                continue;
            }
            if (preview.dimensionId() == dimensionId) {
                snapshot.add(preview);
            }
        }
        return snapshot;
    }

    public static void pruneSurfacePreviews(int dimensionId,
                                            int centerChunkX,
                                            int centerChunkZ,
                                            int keepRadius) {
        long now = System.currentTimeMillis();
        SURFACE_PREVIEWS.entrySet().removeIf(entry -> shouldPrune(
                entry.getKey(),
                entry.getValue(),
                dimensionId,
                centerChunkX,
                centerChunkZ,
                keepRadius,
                now
        ));
        pruneOrphanedHandoffs(now);
    }
    public static void onRealChunkUnloaded(int dimensionId, int chunkX, int chunkZ) {
        // End the handoff when the Minecraft chunk unloads, so any retained preview can show again.
        String key = key(dimensionId, chunkX, chunkZ);
        REAL_CHUNK_HANDOFFS.remove(key);
    }

    public static void clearAll() {
        SURFACE_PREVIEWS.clear();
        REAL_CHUNK_HANDOFFS.clear();
    }

    public static boolean isInRealChunkHandoff(int dimensionId, int chunkX, int chunkZ) {
        return getRealChunkHandoffFade(dimensionId, chunkX, chunkZ) < 1.0f;
    }

    public static float getRealChunkHandoffFade(int dimensionId, int chunkX, int chunkZ) {
        String key = key(dimensionId, chunkX, chunkZ);
        Long until = REAL_CHUNK_HANDOFFS.get(key);
        if (until == null) {
            return 1.0f;
        }

        long remainingMillis = until - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            SURFACE_PREVIEWS.remove(key);
            REAL_CHUNK_HANDOFFS.remove(key);
            return 0.0f;
        }

        return Math.max(0.0f, Math.min(1.0f, remainingMillis / (float) REAL_CHUNK_HANDOFF_MILLIS));
    }

    private static boolean shouldPrune(String key,
                                       PreviewChunk preview,
                                       int dimensionId,
                                       int centerChunkX,
                                       int centerChunkZ,
                                       int keepRadius,
                                       long now) {
        if (expireFinishedHandoff(preview.dimensionId(), preview.chunkX(), preview.chunkZ())) {
            return false;
        }

        if (preview.dimensionId() != dimensionId) {
            boolean staleForeignPreview = now - preview.receivedAtMillis() > FOREIGN_DIMENSION_PREVIEW_TTL_MILLIS;
            if (staleForeignPreview) {
                REAL_CHUNK_HANDOFFS.remove(key);
            }
            return staleForeignPreview;
        }

        int distance = Math.max(
                Math.abs(preview.chunkX() - centerChunkX),
                Math.abs(preview.chunkZ() - centerChunkZ)
        );
        boolean outsideKeepRadius = distance > keepRadius;
        if (outsideKeepRadius) {
            if (now - preview.receivedAtMillis() < RECENT_PREVIEW_GRACE_MILLIS) {
                return false;
            }
            REAL_CHUNK_HANDOFFS.remove(key);
        }
        return outsideKeepRadius;
    }

    private static String key(int dimensionId, int chunkX, int chunkZ) {
        return dimensionId + ":" + chunkX + "," + chunkZ;
    }

    private static boolean expireFinishedHandoff(int dimensionId, int chunkX, int chunkZ) {
        String key = key(dimensionId, chunkX, chunkZ);
        Long until = REAL_CHUNK_HANDOFFS.get(key);
        if (until == null || until > System.currentTimeMillis()) {
            return false;
        }

        SURFACE_PREVIEWS.remove(key);
        REAL_CHUNK_HANDOFFS.remove(key);
        return true;
    }

    private static void pruneOrphanedHandoffs(long now) {
        REAL_CHUNK_HANDOFFS.entrySet().removeIf(entry ->
                entry.getValue() <= now || !SURFACE_PREVIEWS.containsKey(entry.getKey()));
    }
}
