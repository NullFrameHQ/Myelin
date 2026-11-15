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

import com.me.nullframe.myelin.server.MyelinChunkManager;
import com.me.nullframe.myelin.MyelinMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    private ServerWorld world;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> chunksToUnload;

    // Bypassing chunk saves to disk. Should protect users' worlds to not corrupt

    @Inject(method = "save(Lnet/minecraft/world/chunk/Chunk;)Z", at = @At("HEAD"), cancellable = true)
    private void myelin$skipSavingTemporaryTopOnlyChunks(Chunk chunk,
                                                             CallbackInfoReturnable<Boolean> cir) {
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            return;
        }

        if (!manager.shouldSkipSavingTemporaryTopOnlyChunk(world, chunk)) {
            return;
        }

        chunk.setNeedsSaving(false);
        cir.setReturnValue(true);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FAST UNLOAD: Accelerate fake chunk eviction.
    //
    //  Zcode-verified bytecode signature (1.21.1):
    //    private void method_60440(ChunkHolder holder, long pos)
    //
    //  method_60440 does: save (bypassed for our fake chunks) + entity unload +
    //  lighting update + progress listener. It removes the holder from
    //  chunksToUnload itself.
    //
    //  It does NOT remove from currentChunkHolders — the caller must do that.
    //  Our previous version MISSED this removal, which was the leak: the holder
    //  stayed in currentChunkHolders, keeping the chunk data reachable. GC
    //  couldn't reclaim it. RAM never dropped.
    //
    //  This version:
    //    1. Calls method_60440(holder, pos) — does save + cleanup, removes from chunksToUnload
    //    2. Removes from currentChunkHolders — releases the strong reference
    //
    //  After both removals, the holder has zero references. GC reclaims it.
    // ════════════════════════════════════════════════════════════════════════════

    private static final int FAST_UNLOAD_MAX_CHUNKS = 256;
    private static final long FAST_UNLOAD_BUDGET_NANOS = 10_000_000L; // 10ms

    @Inject(method = "unloadChunks(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void myelin$fastUnloadFakeChunks(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) return;
        if (!manager.anyPlayerInSurfaceMode()) return;

        ServerChunkLoadingManager self = (ServerChunkLoadingManager) (Object) this;
        ServerChunkLoadingManagerAccessor accessor = (ServerChunkLoadingManagerAccessor) self;
        Long2ObjectLinkedOpenHashMap<ChunkHolder> currentChunkHolders = accessor.myelin$getCurrentChunkHolders();

        long startTime = System.nanoTime();
        int processed = 0;

        // Unload surface chunks with a per-tick limit.
        // Run the unloading process vanilla style, after which you can delete the chunk
        // holder from the map and rebuild the holder list.

        long[] keys = this.chunksToUnload.keySet().toLongArray();
        for (long pos : keys) {
            if (processed >= FAST_UNLOAD_MAX_CHUNKS) break;
            if (System.nanoTime() - startTime > FAST_UNLOAD_BUDGET_NANOS) break;

            ChunkHolder holder = this.chunksToUnload.get(pos);
            if (holder == null) continue;  // already removed this tick

            int chunkX = ChunkPos.getPackedX(pos);
            int chunkZ = ChunkPos.getPackedZ(pos);

            // Leave non-surface chunks to vanilla. Only unload surface chunks
            if (!manager.isFakeChunk(world, chunkX, chunkZ)) {
                continue;
            }

            // Clear the save flag before unloading
            WorldChunk worldChunk = holder.getWorldChunk();
            if (worldChunk != null) {
                worldChunk.setNeedsSaving(false);
            }

            // Run vanilla's unload routine
            try {
                accessor.myelin$invokeMethod60440(holder, pos);
            } catch (Throwable t) {
                continue;
            }

            // Remove from holder list and refresh
            currentChunkHolders.remove(pos);
            accessor.myelin$setChunkHolderListDirty(true);
            processed++;
        }
    }
}
