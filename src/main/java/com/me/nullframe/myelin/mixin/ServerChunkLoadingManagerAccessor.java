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

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {

    @Accessor("currentChunkHolders")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> myelin$getCurrentChunkHolders();

     // Calls vanilla's unload methods for one chunk.
     // The caller should also remove its holder from currentChunkHolders and mark the holder list dirty.

    @Invoker(value = "method_60440", remap = false)
    void myelin$invokeMethod60440(ChunkHolder holder, long pos);

    // Mark chunk holder list for rebuilding later

    @Accessor("chunkHolderListDirty")
    void myelin$setChunkHolderListDirty(boolean value);
}
