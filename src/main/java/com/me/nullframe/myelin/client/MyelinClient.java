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

package com.me.nullframe.myelin.client;

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.MyelinMod;
import com.me.nullframe.myelin.client.gui.MyelinSettingsScreen;
import com.me.nullframe.myelin.client.render.CachedTerrainPreviewRenderer;
import com.me.nullframe.myelin.client.render.MyelinHudRenderer;
import com.me.nullframe.myelin.client.render.ReceivedChunkOutlineRenderer;
import com.me.nullframe.myelin.client.render.SurfaceChunkRenderer;
import com.me.nullframe.myelin.config.MyelinClientConfig;


import com.me.nullframe.myelin.network.MyelinHandshakeC2SPacket;
import com.me.nullframe.myelin.network.SamplingStepC2SPacket;
import com.me.nullframe.myelin.network.RegionModeC2SPacket;
import com.me.nullframe.myelin.network.PreviewRendererStateC2SPacket;
import com.me.nullframe.myelin.network.MyelinHandshakeS2CPacket;
import com.me.nullframe.myelin.network.MyelinServerStateS2CPacket;
import com.me.nullframe.myelin.network.SurfaceModeConfirmS2CPacket;
import com.me.nullframe.myelin.network.SurfaceModeToggleC2SPacket;
import com.me.nullframe.myelin.network.SurfaceChunkBatchS2CPacket;
import com.me.nullframe.myelin.network.SurfaceChunkS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

public class MyelinClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID + "-client");
    private static KeyBinding surfaceModeToggleKey;
    private static KeyBinding settingsKey;
    private static boolean surfaceModeEnabled;

    public static boolean isSurfaceModeEnabled() { return surfaceModeEnabled; }
    /** Server activation is independent of this client's own configuration. */
    private static boolean serverMyelinEnabled;
    private static boolean serverStateReceived;
    /**
     * Seed from the server handshake, available to client-side terrain bridges.
     * A value of 0 also serves as the unset value.
     */
    private static volatile long serverWorldSeed = 0L;

    // Preview and Minecraft chunk rates, counted on the client thread.
    private static volatile long chunksReceivedThisSecond = 0;
    private static volatile long worldChunksReceivedThisSecond = 0;
    private static volatile long lastSpeedResetTime = 0;
    private static volatile long chunksPerSecond = 0;
    private static volatile long worldChunksPerSecond = 0;

    /** Smoothed rate of cached preview chunks received per second. */
    public static long getChunksPerSecond() { return chunksPerSecond; }

    public static long getWorldChunksPerSecond() { return worldChunksPerSecond; }

    /** Counts preview chunks, including each entry in a batch. */
    public static void incrementChunksReceived(int count) {
        chunksReceivedThisSecond += count;
    }

    public static void incrementWorldChunksReceived() {
        worldChunksReceivedThisSecond++;
    }

    /** Returns the handshake seed, or 0 before one has arrived. */
    public static long getServerWorldSeed() { return serverWorldSeed; }

    public static boolean isAvailable() {
        return MyelinMod.isEnabled() && serverMyelinEnabled;
    }

    private static boolean savedPreferencesPending;

    public static void sendSamplingPreferences() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isAvailable() || client.player == null || client.getNetworkHandler() == null
                || !ClientPlayNetworking.canSend(SamplingStepC2SPacket.ID)) return;
        MyelinClientConfig preferences = MyelinClientConfig.getInstance();
        ClientPlayNetworking.send(new SamplingStepC2SPacket(
                preferences.effectiveSamplingStep(), preferences.effectiveBiomeStep(), preferences.effectiveCorrectionAmp()));
    }

    public static KeyBinding surfaceModeKeyBinding() { return surfaceModeToggleKey; }

    public static boolean canToggleSurfaceMode() {
        MinecraftClient client = MinecraftClient.getInstance();
        return isAvailable() && client.player != null && client.getNetworkHandler() != null
                && ClientPlayNetworking.canSend(SurfaceModeToggleC2SPacket.ID);
    }

    public static void requestSurfaceMode(boolean enabled) {
        if (canToggleSurfaceMode() && enabled != surfaceModeEnabled)
            ClientPlayNetworking.send(new SurfaceModeToggleC2SPacket());
    }

    public static void sendSavedPreferences() {
        savedPreferencesPending = true;
        sendSavedPreferencesIfReady();
    }

    private static void showHudMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!MyelinClientConfig.getInstance().disableHudMessages && client.inGameHud != null)
            client.inGameHud.setOverlayMessage(message, false);
    }

    /** Sends saved preferences once the player and all required packet channels are ready. */
    private static void sendSavedPreferencesIfReady() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!savedPreferencesPending || !isAvailable() || client.player == null || client.getNetworkHandler() == null
                || !ClientPlayNetworking.canSend(SamplingStepC2SPacket.ID)
                || !ClientPlayNetworking.canSend(RegionModeC2SPacket.ID)
                || !ClientPlayNetworking.canSend(PreviewRendererStateC2SPacket.ID)) return;
        MyelinClientConfig preferences = MyelinClientConfig.getInstance();
        sendSamplingPreferences();
        ClientPlayNetworking.send(new RegionModeC2SPacket(Math.max(0, Math.min(2, preferences.regionPathMode))));
        ClientPlayNetworking.send(new PreviewRendererStateC2SPacket(preferences.usePreviewRenderer));
        savedPreferencesPending = false;
    }

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            SurfaceChunkRenderer.clearAll();
            CachedTerrainPreviewRenderer.clearCache();
            ReceivedChunkOutlineRenderer.clear();
            surfaceModeEnabled = false;
            serverMyelinEnabled = false;
            serverStateReceived = false;
            savedPreferencesPending = false;
            serverWorldSeed = 0L;
            chunksReceivedThisSecond = 0;
            chunksPerSecond = 0;
            worldChunksReceivedThisSecond = 0;
            worldChunksPerSecond = 0;
            lastSpeedResetTime = 0;
        }));

        // Keep receivers registered so disabled clients can safely ignore payloads.
        registerPacketReceivers();
        if (!MyelinMod.isEnabled()) return;

        CachedTerrainPreviewRenderer.register();
        ReceivedChunkOutlineRenderer.register();
        HudRenderCallback.EVENT.register(new MyelinHudRenderer());

        // Keybinds
        surfaceModeToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.myelin.toggle_surface_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.myelin"
        ));

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.myelin.settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT, // Right Shift to open settings
                "category.myelin"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            sendSavedPreferencesIfReady();
            // Handle Surface Mode toggle
            if (surfaceModeToggleKey != null) {
                while (surfaceModeToggleKey.wasPressed()) {
                    if (client.player == null || client.getNetworkHandler() == null) {
                        continue;
                    }
                    if (!isAvailable()) {
                        showHudMessage(Text.literal(serverStateReceived && !serverMyelinEnabled
                                ? "Myelin is disabled on this server."
                                : "Myelin is not available on this server."));
                        continue;
                    }
                    if (!ClientPlayNetworking.canSend(SurfaceModeToggleC2SPacket.ID)) {
                        LOGGER.warn("[Myelin] Could not send surface mode toggle: channel not ready.");
                        continue;
                    }
                    ClientPlayNetworking.send(new SurfaceModeToggleC2SPacket());
                }
            }

            // Handle Settings screen
            if (settingsKey != null) {
                while (settingsKey.wasPressed()) {
                    if (isAvailable() && client.player != null) {
                        client.setScreen(MyelinSettingsScreen.createScreen(null));
                    }
                }
            }

            // Update both rates once a second, including quiet windows.
            // Use elapsed time so a slow client tick does not inflate the reading.
            if (!isAvailable()) return;
            long now = System.currentTimeMillis();
            long elapsed = now - lastSpeedResetTime;
            if (elapsed >= 1000) {
                long rate = Math.round(chunksReceivedThisSecond * 1000.0 / elapsed);
                // Give the latest rate twice the weight of the previous reading.
                chunksPerSecond = chunksPerSecond <= 0 ? rate : (chunksPerSecond + 2 * rate) / 3;
                long worldRate = Math.round(worldChunksReceivedThisSecond * 1000.0 / elapsed);
                worldChunksPerSecond = worldChunksPerSecond <= 0 ? worldRate : (worldChunksPerSecond + 2 * worldRate) / 3;
                chunksReceivedThisSecond = 0;
                worldChunksReceivedThisSecond = 0;
                lastSpeedResetTime = now;
            }

        });

    }

    private static void registerPacketReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(MyelinServerStateS2CPacket.ID,
                (payload, context) -> context.client().execute(() -> {
                    serverStateReceived = true;
                    serverMyelinEnabled = payload.enabled();
                    if (!isAvailable()) {
                        surfaceModeEnabled = false;
                        serverWorldSeed = 0L;
                        chunksReceivedThisSecond = 0;
                        chunksPerSecond = 0;
                        worldChunksReceivedThisSecond = 0;
                        worldChunksPerSecond = 0;
                        SurfaceChunkRenderer.clearAll();
                        CachedTerrainPreviewRenderer.clearCache();
                    }
                }));
        String modVersion = FabricLoader.getInstance()
                .getModContainer(MyelinMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        ClientPlayNetworking.registerGlobalReceiver(MyelinHandshakeS2CPacket.ID,
                (payload, context) -> {
                    if (!MyelinMod.isEnabled()) return;
                    LOGGER.debug("[Myelin] Handshake from server version {}, sending reply...",
                            payload.serverModVersion());
                    context.client().execute(() -> {
                        if (!MyelinMod.isEnabled()) return;
                        if (serverStateReceived && !serverMyelinEnabled) return;
                        // Older active servers send only the seed handshake.
                        serverMyelinEnabled = true;
                        serverWorldSeed = payload.worldSeed();
                        lastSpeedResetTime = System.currentTimeMillis();
                        net.minecraft.client.MinecraftClient mc = context.client();
                        mc.send(() -> {
                            if (!isAvailable()) return;
                            if (ClientPlayNetworking.canSend(MyelinHandshakeC2SPacket.ID)) {
                                ClientPlayNetworking.send(new MyelinHandshakeC2SPacket(modVersion));
                                savedPreferencesPending = true;
                                sendSavedPreferencesIfReady();
                                LOGGER.debug("[Myelin] Handshake reply sent successfully.");
                            } else {
                                LOGGER.warn("[Myelin] Could not send handshake reply: channel not ready.");
                            }
                        });
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(SurfaceChunkS2CPacket.ID,
                (payload, context) ->
                        context.client().execute(() -> {
                                if (!isAvailable()) return;
                                SurfaceChunkRenderer.handleSurfaceChunk(
                                        payload,
                                        context.client().getNetworkHandler()
                                );
                                MyelinClient.incrementChunksReceived(1);
                        }));

        ClientPlayNetworking.registerGlobalReceiver(SurfaceChunkBatchS2CPacket.ID,
                (payload, context) ->
                        context.client().execute(() -> {
                            if (!isAvailable()) return;
                            for (SurfaceChunkBatchS2CPacket.ChunkEntry entry : payload.chunks()) {
                                SurfaceChunkRenderer.storeSurfacePreview(
                                        payload.dimensionId(),
                                        entry.chunkX(),
                                        entry.chunkZ(),
                                        entry.heightmap(),
                                        entry.surfaceTypes()
                                );
                            }
                            MyelinClient.incrementChunksReceived(payload.chunks().length);
                        }));

        ClientPlayNetworking.registerGlobalReceiver(SurfaceModeConfirmS2CPacket.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (!isAvailable()) return;
                    surfaceModeEnabled = payload.actualMode();
                    if (!surfaceModeEnabled) {
                        MinecraftClient client = context.client();
                        if (client.player != null && client.world != null
                                && payload.groundHandoffPreviewRadiusChunks() > 0) {
                            SurfaceChunkRenderer.clearPreviewsChebyshev(
                                    DimensionIds.fromWorld(client.world),
                                    client.player.getBlockX() >> 4,
                                    client.player.getBlockZ() >> 4,
                                    payload.groundHandoffPreviewRadiusChunks());
                        }
                        CachedTerrainPreviewRenderer.clearCache();
                    }
                    showHudMessage(Text.literal("Myelin: Flight Mode " + (surfaceModeEnabled ? "ON" : "OFF")));
                }));
    }
}
