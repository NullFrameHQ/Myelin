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

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.client.MyelinClient;
import com.me.nullframe.myelin.config.MyelinClientConfig;

import com.nullframe.core.StepTimings;
import com.nullframe.core.cubiomes.SurfaceColumnType;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public final class CachedTerrainPreviewRenderer {
    private static final int SEA_LEVEL = 63;
    private static final int MISSING_HEIGHT = Integer.MIN_VALUE;
    private static final int EXTRA_KEEP_RADIUS = 12;
    private static final int MIN_KEEP_RADIUS = 20;
    // Keep a minimum preview radius while the nearby Minecraft chunks catch up.
    private static final int SERVER_PREVIEW_WINDOW_FLOOR = 40;
    private static final int PREVIEW_OVERLAP_CHUNKS = 1;
    private static final int CELL_SIZE = 2;
    private static final int CELLS_PER_AXIS = 16 / CELL_SIZE;
    private static final int CELL_GRID_SIZE = CELLS_PER_AXIS + 2;
    private static final int HEIGHT_STEP = 1;
    private static final float COLUMN_BODY_DEPTH = 12.0f;
    private static final float SOLID_ALPHA = 0.96f;
    private static final float WATER_ALPHA = 0.34f;
    private static final float WATER_SURFACE_Y = SEA_LEVEL - 1.0f / 9.0f;
    private static final float[] WATER_COLOR = hex(0x4B88D9);

    private static final Map<MeshKey, CachedPreviewMesh> MESH_CACHE = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LogManager.getLogger("myelin-preview");

    private CachedTerrainPreviewRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CachedTerrainPreviewRenderer::render);
    }

    public static void clearCache() {
        MESH_CACHE.clear();
    }

    private static void render(WorldRenderContext context) {
        if (!MyelinClient.isAvailable()) return;
        if (!MyelinClientConfig.getInstance().usePreviewRenderer) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            clearCache();
            return;
        }

        int dimensionId = DimensionIds.fromWorld(client.world);
        int playerChunkX = client.player.getBlockX() >> 4;
        int playerChunkZ = client.player.getBlockZ() >> 4;
        int vanillaRadius = client.options.getViewDistance().getValue();
        int loadedChunkRadius = detectLoadedChunkRadius(client, playerChunkX, playerChunkZ, vanillaRadius);
        int renderStartRadius = Math.max(0, loadedChunkRadius - PREVIEW_OVERLAP_CHUNKS);
        // Retain the requested preview reach and extra room around loaded chunks.
        int lodReach = MyelinClientConfig.getInstance().lodRenderDistanceChunks;
        int keepRadius = Math.max(
                Math.max(lodReach, loadedChunkRadius + (EXTRA_KEEP_RADIUS + 4)),
                Math.max(MIN_KEEP_RADIUS, SERVER_PREVIEW_WINDOW_FLOOR));
        float dynamicDepth = (float) Math.max(12.0f, Math.min(80.0f, client.player.getY() * 0.4f));

        SurfaceChunkRenderer.pruneSurfacePreviews(
                dimensionId,
                playerChunkX,
                playerChunkZ,
                keepRadius
        );

        Collection<SurfaceChunkRenderer.PreviewChunk> previews =
                SurfaceChunkRenderer.snapshotSurfacePreviews(dimensionId);
        if (previews.isEmpty()) {
            clearCache();
            return;
        }

        Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup = new HashMap<>(previews.size());
        for (SurfaceChunkRenderer.PreviewChunk preview : previews) {
            previewLookup.put(new MeshKey(preview.dimensionId(), preview.chunkX(), preview.chunkZ()), preview);
        }

        Set<MeshKey> liveKeys = new HashSet<>();
        List<VisibleMesh> visibleMeshes = new ArrayList<>();

        for (SurfaceChunkRenderer.PreviewChunk preview : previews) {
            int chunkDistance = Math.max(
                    Math.abs(preview.chunkX() - playerChunkX),
                    Math.abs(preview.chunkZ() - playerChunkZ)
            );
            if (chunkDistance > keepRadius) {
                continue;
            }
            boolean realChunkLoaded = isRealChunkLoaded(client, preview.chunkX(), preview.chunkZ());
            boolean handoffActive = SurfaceChunkRenderer.isInRealChunkHandoff(
                    preview.dimensionId(),
                    preview.chunkX(),
                    preview.chunkZ()
            );
            // Hide the preview once its Minecraft chunk is loaded and the handoff fade has ended.
            if (realChunkLoaded && !handoffActive) {
                continue;
            }

            MeshKey key = new MeshKey(preview.dimensionId(), preview.chunkX(), preview.chunkZ());
            liveKeys.add(key);
            long meshSignature = meshSignature(preview, previewLookup);

            CachedPreviewMesh mesh = MESH_CACHE.get(key);
            if (mesh == null || mesh.sourceSignature != meshSignature) {
                mesh = buildMesh(preview, previewLookup, meshSignature, dynamicDepth);
                MESH_CACHE.put(key, mesh);
            }
            float handoffFade = SurfaceChunkRenderer.getRealChunkHandoffFade(
                    preview.dimensionId(),
                    preview.chunkX(),
                    preview.chunkZ()
            );
            if (handoffFade <= 0.0f) {
                continue;
            }
            visibleMeshes.add(new VisibleMesh(mesh, handoffFade));
        }

        MESH_CACHE.keySet().removeIf(key -> !liveKeys.contains(key));
        if (visibleMeshes.isEmpty()) {
            return;
        }

        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            matrices = new MatrixStack();
        }
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        try {
            Tessellator tessellator = Tessellator.getInstance();
            renderSolidMeshes(tessellator, matrix, visibleMeshes, playerChunkX, playerChunkZ, renderStartRadius, keepRadius);
            renderWaterMeshes(tessellator, matrix, visibleMeshes, playerChunkX, playerChunkZ, renderStartRadius, keepRadius);
        } finally {
            matrices.pop();
        }
    }

    private static void renderSolidMeshes(Tessellator tessellator,
                                          Matrix4f matrix,
                                          List<VisibleMesh> meshes,
                                          int playerChunkX,
                                          int playerChunkZ,
                                          int renderStartRadius,
                                          int keepRadius) {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        boolean drewAny = false;

        for (VisibleMesh visibleMesh : meshes) {
            CachedPreviewMesh mesh = visibleMesh.mesh();
            if (mesh.solidVertices.length == 0) {
                continue;
            }

            float alpha = SOLID_ALPHA * distanceFade(
                    mesh.chunkX,
                    mesh.chunkZ,
                    playerChunkX,
                    playerChunkZ,
                    renderStartRadius,
                    keepRadius
            ) * visibleMesh.handoffFade();
            appendVertices(buffer, matrix, mesh.solidVertices, alpha);
            drewAny = true;
        }

        if (!drewAny) {
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.enableCull();
    }

    private static void renderWaterMeshes(Tessellator tessellator,
                                          Matrix4f matrix,
                                          List<VisibleMesh> meshes,
                                          int playerChunkX,
                                          int playerChunkZ,
                                          int renderStartRadius,
                                          int keepRadius) {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        boolean drewAny = false;

        for (VisibleMesh visibleMesh : meshes) {
            CachedPreviewMesh mesh = visibleMesh.mesh();
            if (mesh.waterVertices.length == 0) {
                continue;
            }

            float alpha = WATER_ALPHA * distanceFade(
                    mesh.chunkX,
                    mesh.chunkZ,
                    playerChunkX,
                    playerChunkZ,
                    renderStartRadius,
                    keepRadius
            ) * visibleMesh.handoffFade();
            appendVertices(buffer, matrix, mesh.waterVertices, alpha);
            drewAny = true;
        }

        if (!drewAny) {
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    private static CachedPreviewMesh buildMesh(SurfaceChunkRenderer.PreviewChunk preview,
                                               Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup,
                                               long meshSignature,
                                               float columnDepth) {
        long tMesh = System.nanoTime();
        VertexCollector solid = new VertexCollector(24_000);
        VertexCollector water = new VertexCollector(8_000);
        CellGrid cellGrid = buildCellGrid(preview, previewLookup);

        int baseX = preview.chunkX() << 4;
        int baseZ = preview.chunkZ() << 4;

        for (int cellZ = 0; cellZ < CELLS_PER_AXIS; cellZ++) {
            int localZ = cellZ * CELL_SIZE;
            for (int cellX = 0; cellX < CELLS_PER_AXIS; cellX++) {
                int localX = cellX * CELL_SIZE;
                CellSample cell = cellGrid.get(cellX, cellZ);
                if (cell == null) {
                    continue;
                }
                int topY = cell.height();
                byte surfaceType = cell.surfaceType();

                float x0 = baseX + localX;
                float x1 = x0 + CELL_SIZE;
                float z0 = baseZ + localZ;
                float z1 = z0 + CELL_SIZE;

                if (SurfaceColumnType.isWater(surfaceType)) {
                    addTopFace(water, x0, x1, z0, z1, WATER_SURFACE_Y, WATER_COLOR);
                    continue;
                }

                CellSample east = cellGrid.get(cellX + 1, cellZ);
                CellSample west = cellGrid.get(cellX - 1, cellZ);
                CellSample south = cellGrid.get(cellX, cellZ + 1);
                CellSample north = cellGrid.get(cellX, cellZ - 1);
                int slope = computeSlope(
                        topY,
                        neighborHeightOrSelf(east, topY),
                        neighborHeightOrSelf(west, topY),
                        neighborHeightOrSelf(south, topY),
                        neighborHeightOrSelf(north, topY)
                );
                SurfacePalette palette = pickPalette(baseX + localX, baseZ + localZ, topY, slope, surfaceType);
                float topFaceY = topY + 1.0f;
                float bodyBottomY = topFaceY - columnDepth;

                addTopFace(solid, x0, x1, z0, z1, topFaceY, palette.topColor);

                float eastVisibleBottom = visibleSideBottom(bodyBottomY, topFaceY, east);
                if (eastVisibleBottom < topFaceY) {
                    addEastFace(solid, x1, z0, z1, topFaceY, eastVisibleBottom, darken(palette.sideColor, 1f)); 
                }
                float westVisibleBottom = visibleSideBottom(bodyBottomY, topFaceY, west);
                if (westVisibleBottom < topFaceY) {
                    addWestFace(solid, x0, z0, z1, topFaceY, westVisibleBottom, darken(palette.sideColor, 1f)); 
                }
                float southVisibleBottom = visibleSideBottom(bodyBottomY, topFaceY, south);
                if (southVisibleBottom < topFaceY) {
                    addSouthFace(solid, x0, x1, z1, topFaceY, southVisibleBottom, darken(palette.sideColor, 1f)); 
                }
                float northVisibleBottom = visibleSideBottom(bodyBottomY, topFaceY, north);
                if (northVisibleBottom < topFaceY) {
                    addNorthFace(solid, x0, x1, z0, topFaceY, northVisibleBottom, darken(palette.sideColor, 1f)); 
                }
            }
        }

        StepTimings.record(StepTimings.Step.CLIENT_MESH, tMesh);
        return new CachedPreviewMesh(
                preview.chunkX(),
                preview.chunkZ(),
                meshSignature,
                solid.toArray(),
                water.toArray()
        );
    }

    private static CellGrid buildCellGrid(SurfaceChunkRenderer.PreviewChunk preview,
                                          Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup) {
        CellSample[] samples = new CellSample[CELL_GRID_SIZE * CELL_GRID_SIZE];
        int writeIndex = 0;
        for (int cellZ = -1; cellZ <= CELLS_PER_AXIS; cellZ++) {
            int localZ = cellZ * CELL_SIZE;
            for (int cellX = -1; cellX <= CELLS_PER_AXIS; cellX++) {
                int localX = cellX * CELL_SIZE;
                samples[writeIndex++] = sampleCell(preview, previewLookup, localX, localZ);
            }
        }
        return new CellGrid(samples);
    }

    private static void appendVertices(BufferBuilder buffer, Matrix4f matrix, float[] vertices, float alpha) {
        for (int i = 0; i < vertices.length; i += 6) {
            buffer.vertex(matrix, vertices[i], vertices[i + 1], vertices[i + 2])
                    .color(vertices[i + 3], vertices[i + 4], vertices[i + 5], alpha);
        }
    }

    private static float distanceFade(int chunkX,
                                      int chunkZ,
                                      int playerChunkX,
                                      int playerChunkZ,
                                      int renderStartRadius,
                                      int keepRadius) {
        int distance = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
        if (distance <= renderStartRadius) {
            return 1.0f;
        }

        float t = (distance - renderStartRadius) / (float) Math.max(1, keepRadius - renderStartRadius);
        // Keep distant previews at least half opaque.
        return clamp(1.0f - t * 0.50f, 0.50f, 1.0f);
    }

    private static boolean isRealChunkLoaded(MinecraftClient client, int chunkX, int chunkZ) {
        if (client.world == null) {
            return false;
        }
        return client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
    }

    private static int detectLoadedChunkRadius(MinecraftClient client,
                                               int centerChunkX,
                                               int centerChunkZ,
                                               int maxRadius) {
        int loadedRadius = 0;
        for (int radius = 0; radius <= maxRadius; radius++) {
            if (!isChunkPerimeterLoaded(client, centerChunkX, centerChunkZ, radius)) {
                break;
            }
            loadedRadius = radius;
        }
        return loadedRadius;
    }

    private static boolean isChunkPerimeterLoaded(MinecraftClient client,
                                                  int centerChunkX,
                                                  int centerChunkZ,
                                                  int radius) {
        if (radius == 0) {
            return isRealChunkLoaded(client, centerChunkX, centerChunkZ);
        }

        for (int offset = -radius; offset <= radius; offset++) {
            if (!isRealChunkLoaded(client, centerChunkX + offset, centerChunkZ - radius)
                    || !isRealChunkLoaded(client, centerChunkX + offset, centerChunkZ + radius)
                    || !isRealChunkLoaded(client, centerChunkX - radius, centerChunkZ + offset)
                    || !isRealChunkLoaded(client, centerChunkX + radius, centerChunkZ + offset)) {
                return false;
            }
        }
        return true;
    }

    private static SurfacePalette pickPalette(int worldX, int worldZ, int y, int slope, byte surfaceType) {
        float variation = (hashNoise(worldX, worldZ) - 0.5f) * 0.08f;

        if (surfaceType == SurfaceColumnType.SAND) {
            return new SurfacePalette(
                    shade(hex(0xD7C088), 0.90f + variation),
                    shade(hex(0xA58B57), 0.80f + variation)
            );
        }

        if (surfaceType == SurfaceColumnType.SNOW || y >= 150) {
            return new SurfacePalette(
                    shade(hex(0xF3F6FA), 0.90f + variation),
                    shade(hex(0xC9CFD6), 0.78f + variation)
            );
        }

        if (surfaceType == SurfaceColumnType.STONE || y >= 110 || slope >= 7) {
            return new SurfacePalette(
                    shade(hex(0x8D939A), 0.86f + variation),
                    shade(hex(0x646A72), 0.72f + variation)
            );
        }

        if (slope >= 4) {
            return new SurfacePalette(
                    shade(hex(0x7D8560), 0.86f + variation),
                    shade(hex(0x5C503D), 0.76f + variation)
            );
        }

        return new SurfacePalette(
                shade(hex(0x5E8741), 0.82f + variation),
                shade(hex(0x5A432E), 0.70f + variation)
        );
    }

    private static int computeSlope(int topY, int eastY, int westY, int southY, int northY) {
        return Math.max(
                Math.max(Math.abs(topY - eastY), Math.abs(topY - westY)),
                Math.max(Math.abs(topY - southY), Math.abs(topY - northY))
        );
    }

    private static int neighborHeightOrSelf(CellSample neighbor, int selfHeight) {
        return neighbor == null ? selfHeight : neighbor.height();
    }

    private static CellSample sampleCell(SurfaceChunkRenderer.PreviewChunk preview,
                                         Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup,
                                         int startLocalX,
                                         int startLocalZ) {
        int landHeightSum = 0;
        int landCount = 0;
        int waterCount = 0;
        int grassCount = 0;
        int sandCount = 0;
        int stoneCount = 0;
        int snowCount = 0;
        int knownColumns = 0;

        for (int dz = 0; dz < CELL_SIZE; dz++) {
            for (int dx = 0; dx < CELL_SIZE; dx++) {
                int localX = startLocalX + dx;
                int localZ = startLocalZ + dz;
                int height = sampleHeight(preview, previewLookup, localX, localZ, MISSING_HEIGHT);
                if (height == MISSING_HEIGHT) {
                    continue;
                }
                knownColumns++;
                byte type = sampleSurfaceType(preview, previewLookup, localX, localZ, SurfaceColumnType.GRASS);

                if (preview.dimensionId() == 0 ? height < SEA_LEVEL - 1 : SurfaceColumnType.isWater(type)) {
                    waterCount++;
                    continue;
                }
                // Treat water-classified columns above sea level as exposed sand.
                if (type == SurfaceColumnType.WATER) type = SurfaceColumnType.SAND;

                landHeightSum += height;
                landCount++;

                switch (type) {
                    case SurfaceColumnType.SAND -> sandCount++;
                    case SurfaceColumnType.STONE -> stoneCount++;
                    case SurfaceColumnType.SNOW -> snowCount++;
                    default -> grassCount++;
                }
            }
        }

        if (knownColumns == 0) {
            return null;
        }
        if (waterCount == CELL_SIZE * CELL_SIZE) {
            return new CellSample(SEA_LEVEL - 1, SurfaceColumnType.WATER);
        }

        if (landCount == 0) {
            return new CellSample(SEA_LEVEL - 1, SurfaceColumnType.WATER);
        }

        int averageHeight = Math.round(landHeightSum / (float) landCount);
        byte resolvedType = SurfaceColumnType.GRASS;
        int bestCount = -1;
        if (grassCount > bestCount) {
            bestCount = grassCount;
            resolvedType = SurfaceColumnType.GRASS;
        }
        if (sandCount > bestCount) {
            bestCount = sandCount;
            resolvedType = SurfaceColumnType.SAND;
        }
        if (stoneCount > bestCount) {
            bestCount = stoneCount;
            resolvedType = SurfaceColumnType.STONE;
        }
        if (snowCount > bestCount) {
            resolvedType = SurfaceColumnType.SNOW;
        }

        return new CellSample(quantizeHeight(averageHeight), resolvedType);
    }

    private static int sampleHeight(SurfaceChunkRenderer.PreviewChunk preview,
                                    Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup,
                                    int localX,
                                    int localZ,
                                    int fallback) {
        if (localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16) {
            return preview.heightmapView()[index(localX, localZ)];
        }

        int chunkOffsetX = Math.floorDiv(localX, 16);
        int chunkOffsetZ = Math.floorDiv(localZ, 16);
        int wrappedX = Math.floorMod(localX, 16);
        int wrappedZ = Math.floorMod(localZ, 16);
        SurfaceChunkRenderer.PreviewChunk neighbor = previewLookup.get(
                new MeshKey(preview.dimensionId(), preview.chunkX() + chunkOffsetX, preview.chunkZ() + chunkOffsetZ)
        );
        if (neighbor == null) {
            return sampleLoadedWorldHeight(preview, localX, localZ, fallback);
        }
        return neighbor.heightmapView()[index(wrappedX, wrappedZ)];
    }

    private static byte sampleSurfaceType(SurfaceChunkRenderer.PreviewChunk preview,
                                          Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup,
                                          int localX,
                                          int localZ,
                                          byte fallback) {
        if (localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16) {
            return preview.surfaceTypesView()[index(localX, localZ)];
        }

        int chunkOffsetX = Math.floorDiv(localX, 16);
        int chunkOffsetZ = Math.floorDiv(localZ, 16);
        int wrappedX = Math.floorMod(localX, 16);
        int wrappedZ = Math.floorMod(localZ, 16);
        SurfaceChunkRenderer.PreviewChunk neighbor = previewLookup.get(
                new MeshKey(preview.dimensionId(), preview.chunkX() + chunkOffsetX, preview.chunkZ() + chunkOffsetZ)
        );
        if (neighbor == null) {
            return sampleLoadedWorldSurfaceType(preview, localX, localZ, fallback);
        }
        return neighbor.surfaceTypesView()[index(wrappedX, wrappedZ)];
    }

    private static int sampleLoadedWorldHeight(SurfaceChunkRenderer.PreviewChunk preview,
                                               int localX,
                                               int localZ,
                                               int fallback) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return fallback;
        }

        int worldX = (preview.chunkX() << 4) + localX;
        int worldZ = (preview.chunkZ() << 4) + localZ;
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        if (!client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
            return fallback;
        }

        return client.world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) - 1;
    }

    private static byte sampleLoadedWorldSurfaceType(SurfaceChunkRenderer.PreviewChunk preview,
                                                     int localX,
                                                     int localZ,
                                                     byte fallback) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return fallback;
        }

        int worldX = (preview.chunkX() << 4) + localX;
        int worldZ = (preview.chunkZ() << 4) + localZ;
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        if (!client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
            return fallback;
        }

        int topY = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) - 1;
        if (topY < client.world.getBottomY()) {
            return fallback;
        }

        BlockState state = client.world.getBlockState(new BlockPos(worldX, topY, worldZ));
        return classifyBlockState(state, fallback);
    }

    private static byte classifyBlockState(BlockState state, byte fallback) {
        if (state == null || state.isAir()) {
            return fallback;
        }
        if (!state.getFluidState().isEmpty() || state.isOf(Blocks.WATER)) {
            return SurfaceColumnType.WATER;
        }
        if (state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.RED_SANDSTONE)) {
            return SurfaceColumnType.SAND;
        }
        if (state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.ICE)
                || state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.BLUE_ICE)) {
            return SurfaceColumnType.SNOW;
        }
        if (state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.TUFF)) {
            return SurfaceColumnType.STONE;
        }
        return SurfaceColumnType.GRASS;
    }

    private static float visibleSideBottom(float bodyBottomY, float topFaceY, CellSample neighbor) {
        if (neighbor == null) {
            // Without neighbor data, stop the side face at its own top height.
            return topFaceY;
        }
        return Math.max(bodyBottomY, neighbor.height() - 4.0f);
    }

    private static int quantizeHeight(int height) {
        int quotient = Math.round(height / (float) HEIGHT_STEP);
        return quotient * HEIGHT_STEP;
    }

    private static void addTopFace(VertexCollector collector,
                                   float x0,
                                   float x1,
                                   float z0,
                                   float z1,
                                   float y,
                                   float[] color) {
        collector.vertex(x0, y, z0, color);
        collector.vertex(x0, y, z1, color);
        collector.vertex(x1, y, z1, color);
        collector.vertex(x1, y, z0, color);
    }

    private static void addEastFace(VertexCollector collector,
                                    float x,
                                    float z0,
                                    float z1,
                                    float topY,
                                    float bottomY,
                                    float[] color) {
        collector.vertex(x, topY, z0, color);
        collector.vertex(x, topY, z1, color);
        collector.vertex(x, bottomY, z1, color);
        collector.vertex(x, bottomY, z0, color);
    }

    private static void addWestFace(VertexCollector collector,
                                    float x,
                                    float z0,
                                    float z1,
                                    float topY,
                                    float bottomY,
                                    float[] color) {
        collector.vertex(x, topY, z1, color);
        collector.vertex(x, topY, z0, color);
        collector.vertex(x, bottomY, z0, color);
        collector.vertex(x, bottomY, z1, color);
    }

    private static void addSouthFace(VertexCollector collector,
                                     float x0,
                                     float x1,
                                     float z,
                                     float topY,
                                     float bottomY,
                                     float[] color) {
        collector.vertex(x1, topY, z, color);
        collector.vertex(x0, topY, z, color);
        collector.vertex(x0, bottomY, z, color);
        collector.vertex(x1, bottomY, z, color);
    }

    private static void addNorthFace(VertexCollector collector,
                                     float x0,
                                     float x1,
                                     float z,
                                     float topY,
                                     float bottomY,
                                     float[] color) {
        collector.vertex(x0, topY, z, color);
        collector.vertex(x1, topY, z, color);
        collector.vertex(x1, bottomY, z, color);
        collector.vertex(x0, bottomY, z, color);
    }

    private static int index(int x, int z) {
        return (z << 4) | x;
    }

    private static float[] hex(int rgb) {
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255.0f,
                ((rgb >> 8) & 0xFF) / 255.0f,
                (rgb & 0xFF) / 255.0f
        };
    }

    private static float[] shade(float[] color, float factor) {
        return new float[]{
                clamp(color[0] * factor, 0.0f, 1.0f),
                clamp(color[1] * factor, 0.0f, 1.0f),
                clamp(color[2] * factor, 0.0f, 1.0f)
        };
    }

    private static float[] darken(float[] color, float factor) {
        return shade(color, factor);
    }

    private static float hashNoise(int x, int z) {
        int value = x * 73428767 ^ z * 912367;
        value ^= value >>> 13;
        value *= 1274126177;
        return (value & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record MeshKey(int dimensionId, int chunkX, int chunkZ) {
    }

    private record VisibleMesh(CachedPreviewMesh mesh, float handoffFade) {
    }

    private record SurfacePalette(float[] topColor, float[] sideColor) {
    }

    private record CellSample(int height, byte surfaceType) {
    }

    private static final class CellGrid {
        private final CellSample[] samples;

        private CellGrid(CellSample[] samples) {
            this.samples = samples;
        }

        private CellSample get(int cellX, int cellZ) {
            return samples[(cellZ + 1) * CELL_GRID_SIZE + (cellX + 1)];
        }
    }

    private static final class CachedPreviewMesh {
        private final int chunkX;
        private final int chunkZ;
        private final long sourceSignature;
        private final float[] solidVertices;
        private final float[] waterVertices;

        private CachedPreviewMesh(int chunkX,
                                  int chunkZ,
                                  long sourceSignature,
                                  float[] solidVertices,
                                  float[] waterVertices) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sourceSignature = sourceSignature;
            this.solidVertices = solidVertices;
            this.waterVertices = waterVertices;
        }
    }

    private static final class VertexCollector {
        private float[] data;
        private int size;

        private VertexCollector(int initialCapacity) {
            this.data = new float[Math.max(initialCapacity, 24)];
        }

        private void vertex(float x, float y, float z, float[] color) {
            ensureCapacity(6);
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = color[0];
            data[size++] = color[1];
            data[size++] = color[2];
        }

        private float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }

        private void ensureCapacity(int additional) {
            int needed = size + additional;
            if (needed <= data.length) {
                return;
            }

            int newLength = Math.max(needed, data.length * 2);
            float[] expanded = new float[newLength];
            System.arraycopy(data, 0, expanded, 0, size);
            data = expanded;
        }
    }

    private static long meshSignature(SurfaceChunkRenderer.PreviewChunk preview,
                                      Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup) {
        long signature = preview.receivedAtMillis();
        signature = mixSignature(signature, timestampOf(previewLookup, preview.dimensionId(), preview.chunkX() + 1, preview.chunkZ()));
        signature = mixSignature(signature, timestampOf(previewLookup, preview.dimensionId(), preview.chunkX() - 1, preview.chunkZ()));
        signature = mixSignature(signature, timestampOf(previewLookup, preview.dimensionId(), preview.chunkX(), preview.chunkZ() + 1));
        signature = mixSignature(signature, timestampOf(previewLookup, preview.dimensionId(), preview.chunkX(), preview.chunkZ() - 1));
        return signature;
    }

    private static long timestampOf(Map<MeshKey, SurfaceChunkRenderer.PreviewChunk> previewLookup,
                                    int dimensionId,
                                    int chunkX,
                                    int chunkZ) {
        SurfaceChunkRenderer.PreviewChunk preview = previewLookup.get(new MeshKey(dimensionId, chunkX, chunkZ));
        return preview == null ? 0L : preview.receivedAtMillis();
    }

    private static long mixSignature(long current, long next) {
        return current * 31L + next;
    }
}
