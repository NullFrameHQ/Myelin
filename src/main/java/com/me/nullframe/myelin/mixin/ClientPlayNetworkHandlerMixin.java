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

package com.me.nullframe.myelin.mixin;

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.client.MyelinClient;
import com.me.nullframe.myelin.config.MyelinClientConfig;
import com.me.nullframe.myelin.client.render.ReceivedChunkOutlineRenderer;
import com.me.nullframe.myelin.client.render.SurfaceChunkRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = true)
    private void myelin$hideHudMessages(GameMessageS2CPacket packet, CallbackInfo ci) {
        // Filter only Myelin action-bar notifications; regular chat and other overlays pass through.
        if (packet.overlay()) {
            NetworkThreadUtils.forceMainThread(packet, (ClientPlayNetworkHandler) (Object) this, MinecraftClient.getInstance());
            if (MyelinClientConfig.getInstance().shouldHideHudMessage(packet.content().getString())) ci.cancel();
        }
    }

    /**
     * Debug overlay hook. The handler first runs on the network thread and is then re-run on the
     * client thread, so record only on the client thread: once per packet, in packet order.
     */
    @Inject(method = "onChunkData", at = @At("HEAD"))
    private void myelin$recordReceivedChunk(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) {
            ReceivedChunkOutlineRenderer.onChunkReceived(packet.getChunkX(), packet.getChunkZ());
        }
    }

    @Inject(method = "onUnloadChunk", at = @At("HEAD"))
    private void myelin$forgetReceivedChunk(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().isOnThread()) {
            ReceivedChunkOutlineRenderer.onChunkUnloaded(packet.pos().x, packet.pos().z);
        }
    }

    @Inject(method = "onChunkData", at = @At("TAIL"))
    private void myelin$onChunkData(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (!MyelinClient.isAvailable()) return;
        MyelinClient.incrementWorldChunksReceived();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            int dimensionId = DimensionIds.fromWorld(client.world);
            SurfaceChunkRenderer.beginRealChunkHandoff(dimensionId, packet.getChunkX(), packet.getChunkZ());
        }
    }

    @Inject(method = "onUnloadChunk", at = @At("HEAD"))
    private void myelin$onChunkUnload(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        if (!MyelinClient.isAvailable()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            int dimensionId = DimensionIds.fromWorld(client.world);
            SurfaceChunkRenderer.onRealChunkUnloaded(dimensionId, packet.pos().x, packet.pos().z);
        }
    }
}
