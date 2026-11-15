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

package com.me.nullframe.myelin;

import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.MyelinChunkManager;
import com.me.nullframe.myelin.server.MyelinCommand;
import com.me.nullframe.myelin.server.chunk.MyelinChunkTracking;
import com.me.nullframe.myelin.server.player.MyelinPlayerRegistry;

import com.nullframe.core.cubiomes.Cubiomes;
import com.me.nullframe.myelin.network.MyelinHandshakeC2SPacket;
import com.me.nullframe.myelin.network.MyelinHandshakeS2CPacket;
import com.me.nullframe.myelin.network.MyelinServerStateS2CPacket;
import com.me.nullframe.myelin.network.SurfaceModeConfirmS2CPacket;
import com.me.nullframe.myelin.network.SurfaceModeToggleC2SPacket;
import com.me.nullframe.myelin.network.SamplingStepC2SPacket;
import com.me.nullframe.myelin.network.RegionModeC2SPacket;
import com.me.nullframe.myelin.network.PreviewRendererStateC2SPacket;
import com.me.nullframe.myelin.network.SurfaceChunkBatchS2CPacket;
import com.me.nullframe.myelin.network.SurfaceChunkS2CPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class MyelinMod implements ModInitializer {

    public static final String MOD_ID = "myelin";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static final String MOD_VERSION = "1";

    private static volatile MyelinChunkManager chunkManager;
    private static MyelinConfig config;
    private static MyelinPlayerRegistry playerRegistry;

    @Override
    public void onInitialize() {
        LOGGER.info("[Myelin] Initializing mod.");

        Path configDir = FabricLoader.getInstance().getConfigDir();
        LOGGER.info("[Myelin] Loading configuration from {} (restart required)",
                configDir.resolve("myelin.json").toAbsolutePath());
        config = MyelinConfig.load(configDir);
        if (config == null) {
            LOGGER.error("[Myelin] Config load returned null.");
            throw new IllegalStateException("MyelinConfig.load returned null");
        }
        LOGGER.info("[Myelin] Config loaded. enabled={} cacheStageColors={} requireClientMod={} regionPath={}",
                config.enabled,
                config.debugColorChunksCacheStage,
                config.requireClientMod,
                config.regionPathMode);
        playerRegistry = MyelinPlayerRegistry.getInstance();
        // The master switch is read at startup; changing it requires a restart.
        // Do not initialize the native backend or worker pools when disabled.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (isEnabled()) chunkManager = new MyelinChunkManager(config);
        });

        // Warm native worker caches only when a dedicated or integrated server
        // starts. Standalone clients do not generate chunks and need no warmup.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!isEnabled() || chunkManager == null) return;
            if (Cubiomes.isNativeAvailable()) {
                Cubiomes.setCorrectionAmp(4); // Native code applies correction only at 1:16 and coarser.
                Cubiomes.setBiomeStep(0); // Client sends its saved biome preference after the handshake.
                chunkManager.prewarmCubiomesCache();
            }
            chunkManager.startScheduling();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MyelinChunkManager manager = chunkManager;
            if (manager != null) manager.close();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            chunkManager = null;
            playerRegistry.clear();
            MyelinChunkTracking.clearAll();
        });

        // Register all custom packet payload types
        PayloadTypeRegistry.playS2C().register(SurfaceChunkS2CPacket.ID, SurfaceChunkS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(SurfaceChunkBatchS2CPacket.ID, SurfaceChunkBatchS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MyelinHandshakeS2CPacket.ID, MyelinHandshakeS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MyelinServerStateS2CPacket.ID, MyelinServerStateS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(SurfaceModeConfirmS2CPacket.ID, SurfaceModeConfirmS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(MyelinHandshakeC2SPacket.ID, MyelinHandshakeC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(SurfaceModeToggleC2SPacket.ID, SurfaceModeToggleC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(SamplingStepC2SPacket.ID, SamplingStepC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(RegionModeC2SPacket.ID, RegionModeC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PreviewRendererStateC2SPacket.ID, PreviewRendererStateC2SPacket.CODEC);

        // Server-side control commands (benchmark / headless support).
        MyelinCommand.register();

        // Report the master switch even when disabled. Only active servers send
        // the seed handshake and the initial surface-mode confirmation.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, MyelinServerStateS2CPacket.ID)) {
                ServerPlayNetworking.send(handler.player, new MyelinServerStateS2CPacket(isEnabled()));
            }
            if (!isEnabled()) return;
            ServerPlayNetworking.send(handler.player,
                    new MyelinHandshakeS2CPacket(MOD_VERSION,
                            handler.player.getServerWorld().getSeed()));
            ServerPlayNetworking.send(handler.player,
                    new SurfaceModeConfirmS2CPacket(
                            chunkManager != null
                                    && chunkManager.isPlayerInSurfaceMode(handler.player.getUuid()),
                            0));
        });

        // Forget both client access and player state when a player disconnects.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            playerRegistry.unregister(handler.player.getUuid());
            if (chunkManager != null) {
                chunkManager.onPlayerDisconnect(handler.player.getUuid());
            }
        });

        // On receiving handshake reply: mark player as having the client mod
        ServerPlayNetworking.registerGlobalReceiver(MyelinHandshakeC2SPacket.ID,
                (payload, context) -> {
                    if (!isEnabled()) return;
                    playerRegistry.register(context.player().getUuid());
                    LOGGER.debug("[Myelin] Handshake OK player={} version={} ",
                            context.player().getGameProfile().getName(),
                            payload.modVersion());
                });

        ServerPlayNetworking.registerGlobalReceiver(SurfaceModeToggleC2SPacket.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                if (!isEnabled()) {
                    player.sendMessage(Text.literal("Myelin is disabled on this server."), true);
                    if (ServerPlayNetworking.canSend(player, MyelinServerStateS2CPacket.ID)) {
                        ServerPlayNetworking.send(player, new MyelinServerStateS2CPacket(false));
                    }
                    if (ServerPlayNetworking.canSend(player, SurfaceModeConfirmS2CPacket.ID)) {
                        ServerPlayNetworking.send(player, new SurfaceModeConfirmS2CPacket(false, 0));
                    }
                    return;
                }
                MyelinChunkManager manager = MyelinMod.getChunkManager();
                if (manager != null) manager.toggleSurfaceMode(player);
            });
        });


        ServerPlayNetworking.registerGlobalReceiver(SamplingStepC2SPacket.ID, (payload, context) -> {
            if (!isEnabled()) return;
            ServerPlayerEntity player = context.player();
            MyelinConfig cfg = MyelinMod.getConfig();
            if (cfg != null) {
                int rawStep = payload.samplingStep();
                // Use 1:1 if the client sends an unsupported sample level.
                if (rawStep != 1 && rawStep != 2 && rawStep != 4 && rawStep != 8
                        && rawStep != 16 && rawStep != 32
                        && rawStep != 64 && rawStep != 128) rawStep = 1;
                int rawBiome = payload.biomeStep();
                if (rawBiome < 0) rawBiome = 0;
                if (rawBiome > 128) rawBiome = 128;
                final int step = rawStep;  
                final int biome = rawBiome;
                context.server().execute(() -> {
                    if (!isEnabled()) return;
                    MyelinChunkManager mgr = MyelinMod.getChunkManager();
                    if (mgr != null) mgr.applyClientSampling(step, biome, payload.correctionAmp());
                    player.sendMessage(Text.literal("\u00a7bMyelin: Sampling set to 1:" + step
                            + (step == 1 ? " (full detail)" : " (" + (step*step) + "x fewer samples)")), true);
                });
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(RegionModeC2SPacket.ID, (payload, context) -> {
            if (!isEnabled()) return;
            MyelinConfig cfg = MyelinMod.getConfig();
            if (cfg != null) {
                int rawMode = payload.mode();
                if (rawMode < 0 || rawMode > 2) rawMode = 0;
                final int modeIndex = rawMode;
                final String mode = modeIndex == 1 ? "on" : modeIndex == 2 ? "off" : "auto";
                context.server().execute(() -> {
                    if (!isEnabled()) return;
                    cfg.regionPathMode = mode;
                    context.player().sendMessage(Text.literal("\u00a7bMyelin: Region gen \u2014 "
                            + (modeIndex == 0 ? "Auto (1:32 and below)" : modeIndex == 1 ? "Always On" : "Off")), true);
                });
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(PreviewRendererStateC2SPacket.ID, (payload, context) -> {
            if (!isEnabled()) return;
            ServerPlayerEntity player = context.player();
            MyelinChunkManager manager = MyelinMod.getChunkManager();
            if (manager != null) {
                boolean enabled = payload.enabled();
                context.server().execute(() -> {
                    if (!isEnabled()) return;
                    manager.setPlayerNeedsSurfacePackets(player.getUuid(), enabled);
                });
            }
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            MyelinChunkManager manager = chunkManager;
            if (manager != null) manager.onServerTickStart(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // Track chunk load and unload events for residency and temporary save protection.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            MyelinChunkManager manager = chunkManager;
            if (manager != null) manager.onChunkLoad(world, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (chunkManager != null) {
                chunkManager.onChunkUnload(world, chunk);
            }
        });

        if (isEnabled()) {
            LOGGER.info("[Myelin] Ready. Native Cubiomes: {}",
                    Cubiomes.isNativeAvailable() ? "loaded" : "unavailable (flat fallback)");
        } else {
            LOGGER.info("[Myelin] Disabled by configuration. Change enabled and restart to activate.");
        }
    }

    public static MyelinChunkManager getChunkManager() { return chunkManager; }
    public static MyelinConfig getConfig()             { return config; }
    public static boolean isEnabled() { return config != null && config.enabled; }
    public static MyelinPlayerRegistry getPlayerRegistry() { return playerRegistry; }

    private void onServerTick(MinecraftServer server) {
        if (chunkManager != null) chunkManager.onServerTick(server);
    }
}
