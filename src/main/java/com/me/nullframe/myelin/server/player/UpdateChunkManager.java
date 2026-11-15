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

import com.me.nullframe.myelin.MyelinMod;
import com.me.nullframe.myelin.server.MyelinChunkManager;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;

/**
 * Compatibility entry point for leaving Flight Mode.
 * Existing Surface chunks remain loaded until vanilla unloads them normally.
 */
public class UpdateChunkManager {

    /**
     * Stops Surface generation without changing the player's game mode.
     *
     * @param player the player leaving Flight Mode
     * @param topOnlyChunkKeys retained for compatibility; no chunks are forcibly unloaded
     * @param cubiomesExecutor unused; retained for compatibility
     */
    public static void startHandoff(ServerPlayerEntity player,
                                    Set<MyelinChunkManager.ChunkKey> topOnlyChunkKeys,
                                    java.util.concurrent.ExecutorService cubiomesExecutor) {
        MyelinMod.getChunkManager().convertTopOnlyToRealChunks(player);
    }
}
