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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.AbstractChunkHolder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkGenerating;
import net.minecraft.world.chunk.ChunkGenerationContext;
import net.minecraft.world.chunk.ChunkGenerationStep;
import net.minecraft.world.gen.chunk.Blender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkGenerating.class)
public abstract class ChunkGeneratingMixin {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);

    // Skipping unnecessary chunk stages: Structures. Structures are not needed in surface chunks

    @Inject(
            method = "generateStructures(Lnet/minecraft/world/chunk/ChunkGenerationContext;Lnet/minecraft/world/chunk/ChunkGenerationStep;Lnet/minecraft/util/collection/BoundedRegionArray;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void myelin$skipStructureStarts(ChunkGenerationContext context,
                                                       ChunkGenerationStep step,
                                                       BoundedRegionArray<AbstractChunkHolder> chunks,
                                                       Chunk chunk,
                                                       CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        skipIfTopOnly(context, chunk, cir);
    }

    // Skipping unnecessary chunk stages: Structure references. We don't need to check structures we don't generate

    @Inject(
            method = "generateStructureReferences(Lnet/minecraft/world/chunk/ChunkGenerationContext;Lnet/minecraft/world/chunk/ChunkGenerationStep;Lnet/minecraft/util/collection/BoundedRegionArray;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void myelin$skipStructureReferences(ChunkGenerationContext context,
                                                           ChunkGenerationStep step,
                                                           BoundedRegionArray<AbstractChunkHolder> chunks,
                                                           Chunk chunk,
                                                           CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        skipIfTopOnly(context, chunk, cir);
    }

    // Skipping unnecessary chunk stages: Biome. Biome data is supplied from NullFrame Core

    @Inject(
            method = "populateBiomes(Lnet/minecraft/world/chunk/ChunkGenerationContext;Lnet/minecraft/world/chunk/ChunkGenerationStep;Lnet/minecraft/util/collection/BoundedRegionArray;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void myelin$skipBiomes(ChunkGenerationContext context,
                                              ChunkGenerationStep step,
                                              BoundedRegionArray<AbstractChunkHolder> chunks,
                                              Chunk chunk,
                                              CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        skipIfTopOnly(context, chunk, cir);
    }

    // Intercepting in Noise stage. Call the noise generator directly for surface chunks.
    // We inject the surface chunks into Minecraft at this stage.
    // Don't let vanilla generate its own chunks in this stage

    @Inject(
            method = "populateNoise(Lnet/minecraft/world/chunk/ChunkGenerationContext;Lnet/minecraft/world/chunk/ChunkGenerationStep;Lnet/minecraft/util/collection/BoundedRegionArray;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void myelin$bypassNoiseRetrogenForTopOnly(ChunkGenerationContext context,
                                                                 ChunkGenerationStep step,
                                                                 BoundedRegionArray<AbstractChunkHolder> chunks,
                                                                 Chunk chunk,
                                                       CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        if (!MyelinMod.isEnabled()) return;
        try {
            ServerWorld world = context.world();
            MyelinChunkManager manager = MyelinMod.getChunkManager();
            if (manager == null) {
                return;
            }

            boolean topOnly = manager.shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z);
            if (!topOnly) {
                return;
            }

            ChunkRegion region = new ChunkRegion(world, chunks, step, chunk);
            cir.setReturnValue(context.generator().populateNoise(
                    Blender.getBlender(region),
                    world.getChunkManager().getNoiseConfig(),
                    world.getStructureAccessor().forRegion(region),
                    chunk
            ));
        } catch (Throwable t) {
            LOGGER.error("[Myelin] ChunkGeneratingMixin caught exception, letting vanilla run for chunk [{},{}]",
                    chunk.getPos().x, chunk.getPos().z, t);
        }
    }


    // Skip this generation step for surface chunks.
    // Return a completed future containing the unchanged chunk.

    private static void skipIfTopOnly(ChunkGenerationContext context,
                                      Chunk chunk,
                                      CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        if (!MyelinMod.isEnabled()) return;
        try {
            ServerWorld world = context.world();
            MyelinChunkManager manager = MyelinMod.getChunkManager();
            if (manager == null) return;
            if (!manager.shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z)) return;
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        } catch (Throwable t) {
            LOGGER.error("[Myelin] ChunkGeneratingMixin.skipIfTopOnly caught exception for chunk [{},{}]",
                    chunk.getPos().x, chunk.getPos().z, t);
        }
    }
}
