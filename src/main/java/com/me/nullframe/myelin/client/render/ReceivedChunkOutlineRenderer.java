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

import com.me.nullframe.myelin.config.MyelinClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Debug overlay for "Pre-render received chunks".
 *
 * <p>Draws a red outline around every chunk whose chunk-data packet the client has received,
 * independent of whether the chunk has been meshed or drawn yet. Comparing it with the visible
 * terrain shows whether the delay is before the client (server or network) or after it
 * (installing, lighting, meshing).</p>
 *
 * <p>Both recording and rendering run on the client main thread, so the set needs no locking and
 * keeps packet order relative to world-change packets.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReceivedChunkOutlineRenderer {
    private static final LongOpenHashSet RECEIVED = new LongOpenHashSet();
    private static ClientWorld trackedWorld;

    private ReceivedChunkOutlineRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ReceivedChunkOutlineRenderer::render);
    }

    /** Client main thread, just before vanilla handles a chunk-data packet. */
    public static void onChunkReceived(int chunkX, int chunkZ) {
        if (!MyelinClientConfig.getInstance().showReceivedChunks) return;
        MinecraftClient client = MinecraftClient.getInstance();
        syncWorld(client.world);
        if (trackedWorld != null) RECEIVED.add(ChunkPos.toLong(chunkX, chunkZ));
    }

    /** Client main thread, just before vanilla handles an unload packet. */
    public static void onChunkUnloaded(int chunkX, int chunkZ) {
        if (RECEIVED.isEmpty()) return;
        RECEIVED.remove(ChunkPos.toLong(chunkX, chunkZ));
    }

    public static void clear() {
        RECEIVED.clear();
        trackedWorld = null;
    }

    /** A different world object means a new dimension or session, so old positions no longer apply. */
    private static void syncWorld(ClientWorld world) {
        if (world != trackedWorld) {
            RECEIVED.clear();
            trackedWorld = world;
        }
    }

    private static void render(WorldRenderContext context) {
        if (!MyelinClientConfig.getInstance().showReceivedChunks) {
            if (!RECEIVED.isEmpty() || trackedWorld != null) clear();
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            clear();
            return;
        }
        syncWorld(client.world);
        if (RECEIVED.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) return;
        Vec3d cameraPos = context.camera().getPos();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Positions are made camera-relative in double precision before narrowing to float,
        // so far-away outlines do not jitter.
        double y = client.world.getSeaLevel() + 0.1 - cameraPos.y;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        LongIterator it = RECEIVED.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            double x0 = (ChunkPos.getPackedX(packed) << 4) - cameraPos.x;
            double z0 = (ChunkPos.getPackedZ(packed) << 4) - cameraPos.z;
            line(buffer, matrix, x0, y, z0, x0 + 16, y, z0);
            line(buffer, matrix, x0 + 16, y, z0, x0 + 16, y, z0 + 16);
            line(buffer, matrix, x0 + 16, y, z0 + 16, x0, y, z0 + 16);
            line(buffer, matrix, x0, y, z0 + 16, x0, y, z0);
        }

        // Always visible: no depth test, so it shows through terrain and over unrendered gaps.
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(2.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableDepthTest();
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2) {
        buffer.vertex(matrix, (float) x1, (float) y1, (float) z1).color(255, 0, 0, 255);
        buffer.vertex(matrix, (float) x2, (float) y2, (float) z2).color(255, 0, 0, 255);
    }
}
