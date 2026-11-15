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

package com.me.nullframe.myelin.server.player;

import com.me.nullframe.myelin.DimensionIds;
import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.scheduling.PrefetchDirection;
import com.me.nullframe.myelin.server.scheduling.StripGeometry;
import com.me.nullframe.myelin.server.scheduling.StripSnapshotScheduler;

import com.me.nullframe.myelin.network.SurfaceModeConfirmS2CPacket;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public final class MyelinPlayerManager {
    private final MyelinConfig config;
    private final StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler;
    private final LongSupplier surfaceCacheGeneration;
    private final LongSupplier tickCounter;
    private final BooleanSupplier closed;
    private final IntSupplier workerCount;
    private final Function<ServerWorld, Object> settings;

    private static final int SURFACE_MODE_TOGGLE_COOLDOWN_TICKS = 20;
    private static class PlayerMovementState {
        final PrefetchDirection.Tracker prefetchDirection = new PrefetchDirection.Tracker();
        int     effectiveTopOnlyOuterRadius;
        boolean hasClientAccess;
        volatile boolean needsSurfacePackets = true;  // false when client disabled preview renderer
        volatile boolean surfaceMode;
        long lastSurfaceModeToggleTick = Long.MIN_VALUE;
    }

    private final Map<UUID, PlayerMovementState> movementStateByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, ServerPlayerEntity> onlinePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, StripSnapshotScheduler.PlayerWindow> playerWindows = new ConcurrentHashMap<>();

    public MyelinPlayerManager(MyelinConfig config, StripSnapshotScheduler<SurfaceChunkSnapshot> stripScheduler,
                         LongSupplier surfaceCacheGeneration,
                         LongSupplier tickCounter, BooleanSupplier closed, IntSupplier workerCount,
                         Function<ServerWorld, Object> settings) {
        this.config = config;
        this.stripScheduler = stripScheduler;
        this.surfaceCacheGeneration = surfaceCacheGeneration;
        this.tickCounter = tickCounter;
        this.closed = closed;
        this.workerCount = workerCount;
        this.settings = settings;
    }

    public Map<UUID, StripSnapshotScheduler.PlayerWindow> windows() { return playerWindows; }

    public Map<UUID, ServerPlayerEntity> onlinePlayers() { return onlinePlayers; }

    public void clearWindows() { playerWindows.clear(); }

    public void clearOnlinePlayers() { onlinePlayers.clear(); }

    public void refreshWindows() {
        for (ServerPlayerEntity player : onlinePlayers.values()) publishPlayerWindow(player);
    }

    public void publishPlayerWindow(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        onlinePlayers.put(id, player);
        ServerWorld world = player.getServerWorld();
        int x = player.getBlockX() >> 4, z = player.getBlockZ() >> 4;
        int dimension = DimensionIds.fromWorld(world);
        PlayerMovementState state = movementStateByPlayer.computeIfAbsent(id,
                unused -> new PlayerMovementState());
        state.hasClientAccess = !config.requireClientMod || MyelinPlayerRegistry.getInstance().isModded(id);
        int effectiveRD = Math.min(player.getViewDistance(), world.getServer().getPlayerManager().getViewDistance());
        state.effectiveTopOnlyOuterRadius = effectiveRD + config.snapshotPrefetchMarginChunks;
        boolean eligible = state.surfaceMode && state.hasClientAccess && isDimensionEnabled(world)
                && workerCount.getAsInt() > 0;
        StripSnapshotScheduler.PlayerWindow window = new StripSnapshotScheduler.PlayerWindow(id, dimension,
                world.getSeed(), x, z, effectiveRD, config.snapshotPrefetchMarginChunks,
                state.prefetchDirection.sample(dimension, tickCounter.getAsLong(), player.getX(), player.getZ(), player.getYaw()), eligible,
                state.needsSurfacePackets && MyelinPlayerRegistry.getInstance().isModded(id),
                surfaceCacheGeneration.getAsLong(), settings.apply(world));
        playerWindows.put(id, window);
        stripScheduler.publishWindow(window);
    }

    public boolean isPlayerInSurfaceMode(UUID playerId) {
        if (!config.enabled) return false;
        PlayerMovementState state = movementStateByPlayer.get(playerId);
        return state != null && state.surfaceMode;
    }

    public boolean shouldUseTopOnlyGeneration(ServerWorld world, int chunkX, int chunkZ) {
        if (closed.getAsBoolean() || !config.enabled || workerCount.getAsInt() <= 0 || world == null) return false;
        int dimension = DimensionIds.fromWorld(world);
        for (StripSnapshotScheduler.PlayerWindow window : playerWindows.values()) {
            if (window.eligible() && window.dimension() == dimension) return true;
        }
        return false;
    }

    /** Whether any published player window is eligible for Surface generation. */
    public boolean anyPlayerInSurfaceMode() {
        if (closed.getAsBoolean() || !config.enabled) return false;
        for (StripSnapshotScheduler.PlayerWindow window : playerWindows.values()) {
            if (window.eligible()) return true;
        }
        return false;
    }

    /** Records whether the client needs cached preview packets. */
    public void setPlayerNeedsSurfacePackets(UUID playerId, boolean needs) {
        PlayerMovementState state = movementStateByPlayer.get(playerId);
        if (state != null) {
            state.needsSurfacePackets = needs;
        }
    }

    public boolean playerNeedsSurfacePackets(UUID playerId) {
        PlayerMovementState state = movementStateByPlayer.get(playerId);
        return state == null || state.needsSurfacePackets;
    }

    public boolean isWithinAnyPlayerWindow(int dimension, int x, int z) {
        int gridX = Math.floorDiv(x, StripGeometry.WIDTH), gridZ = Math.floorDiv(z, StripGeometry.HEIGHT);
        for (StripSnapshotScheduler.PlayerWindow window : playerWindows.values()) {
            if (window.eligible() && window.dimension() == dimension
                    && window.epoch() == surfaceCacheGeneration.getAsLong() && window.range().contains(gridX, gridZ)) return true;
        }
        return false;
    }

    public void onPlayerDisconnect(UUID playerId) {
        onlinePlayers.remove(playerId);
        playerWindows.remove(playerId);
        stripScheduler.removePlayer(playerId);
        movementStateByPlayer.remove(playerId);
    }


    public void toggleSurfaceMode(ServerPlayerEntity player) {
        if (!config.enabled || player == null) return;

        PlayerMovementState state = movementStateByPlayer.computeIfAbsent(
                player.getUuid(),
                id -> new PlayerMovementState());
        state.effectiveTopOnlyOuterRadius = getEffectiveTopOnlyOuterRadius(player);
        state.hasClientAccess = !config.requireClientMod || MyelinPlayerRegistry.getInstance().isModded(player.getUuid());

        if (isSurfaceModeToggleCoolingDown(state)) {
            long remainingTicks = SURFACE_MODE_TOGGLE_COOLDOWN_TICKS - (tickCounter.getAsLong() - state.lastSurfaceModeToggleTick);
            player.sendMessage(Text.literal("Myelin: wait " + Math.max(1L, (long) Math.ceil(remainingTicks / 20.0D)) + "s before toggling again."), true);
            sendSurfaceModeConfirm(player, state.surfaceMode, 0);
            return;
        }

        boolean newMode = !state.surfaceMode;
        if (newMode) {
            state.surfaceMode = true;
            state.lastSurfaceModeToggleTick = tickCounter.getAsLong();
player.sendMessage(Text.literal("Myelin: flight mode ON. Top-only terrain enabled."), true);
            sendSurfaceModeConfirm(player, true, 0);
            publishPlayerWindow(player);
        } else {
            state.surfaceMode = false;
            state.lastSurfaceModeToggleTick = tickCounter.getAsLong();
            onExitSurfaceMode(player);
            player.sendMessage(Text.literal("Myelin: Ground mode ON. Existing Surface chunks unload naturally."), true);
            sendSurfaceModeConfirm(player, false, 0);
        }
    }

    private boolean isSurfaceModeToggleCoolingDown(PlayerMovementState state) {
        return state.lastSurfaceModeToggleTick != Long.MIN_VALUE && tickCounter.getAsLong() - state.lastSurfaceModeToggleTick < SURFACE_MODE_TOGGLE_COOLDOWN_TICKS;
    }

    /** Stops Surface generation for this player; existing chunks unload normally. */
    public void convertTopOnlyToRealChunks(ServerPlayerEntity player) {
        onExitSurfaceMode(player);
    }

    private void onExitSurfaceMode(ServerPlayerEntity player) {
        if (player == null) return;
        PlayerMovementState state = movementStateByPlayer.get(player.getUuid());
        if (state != null) {
            state.surfaceMode = false;
        }
        clearPlayerSurfacePreviewState(player.getServerWorld(), player.getUuid());
    }

    private void clearPlayerSurfacePreviewState(ServerWorld world, UUID playerId) {
        ServerPlayerEntity player = onlinePlayers.get(playerId);
        if (player != null) publishPlayerWindow(player);
    }

    private boolean isDimensionEnabled(ServerWorld world) {
        String idString = world.getRegistryKey().getValue().toString();
        if (!config.dimensionAllowlist.isEmpty() && !config.dimensionAllowlist.contains(idString)) return false;
        return !config.dimensionDenylist.contains(idString);
    }

    private void sendSurfaceModeConfirm(ServerPlayerEntity player, boolean mode, int groundHandoffPreviewRadiusChunks) {
        ServerPlayNetworking.send(player, new SurfaceModeConfirmS2CPacket(mode, groundHandoffPreviewRadiusChunks));
    }

    private int getEffectiveTopOnlyOuterRadius(ServerPlayerEntity player) {
        int serverCap = player.getServerWorld().getServer().getPlayerManager().getViewDistance();
        int playerVD = player.getViewDistance();
        int effectiveVD = Math.min(serverCap, playerVD);
        return effectiveVD + config.snapshotPrefetchMarginChunks;
    }

    public String activeRadii() {
        int minRadius = Integer.MAX_VALUE;
        int maxRadius = 0;
        for (PlayerMovementState state : movementStateByPlayer.values()) {
            if (!state.surfaceMode || state.effectiveTopOnlyOuterRadius <= 0) continue;
            minRadius = Math.min(minRadius, state.effectiveTopOnlyOuterRadius);
            maxRadius = Math.max(maxRadius, state.effectiveTopOnlyOuterRadius);
        }
        String activeRadii = minRadius == Integer.MAX_VALUE ? "none"
                : minRadius == maxRadius ? Integer.toString(minRadius) : minRadius + "–" + maxRadius;
        return activeRadii;
    }

}
