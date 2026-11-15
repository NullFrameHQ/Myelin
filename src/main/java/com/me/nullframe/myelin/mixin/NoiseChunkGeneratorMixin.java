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
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = NoiseChunkGenerator.class, priority = 2000)
public abstract class NoiseChunkGeneratorMixin {

    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);

    @Inject(
            method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$populateTopOnlyChunk(Blender blender,
                                                 NoiseConfig noiseConfig,
                                                 StructureAccessor structureAccessor,
                                                 Chunk chunk,
                                                 CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
        if (!MyelinMod.isEnabled()) return;

        if (!shouldUseTopOnly(structureAccessor, chunk)) {
            return;
        }

        ServerWorld world = resolveServerWorld(structureAccessor);
        if (world == null) {
            return;
        }
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        cir.setReturnValue(manager.populateTopOnlyChunkAsync(world, chunk,
                () -> ((NoiseChunkGenerator) (Object) this).populateNoise(
                        blender, noiseConfig, structureAccessor, chunk)));
    }

    @Inject(
            method = "buildSurface(Lnet/minecraft/world/ChunkRegion;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$skipSurfaceBuild(ChunkRegion region,
                                             StructureAccessor structureAccessor,
                                             NoiseConfig noiseConfig,
                                             Chunk chunk,
                                             CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;

        if (shouldUseTopOnly(region.toServerWorld(), chunk)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "carve(Lnet/minecraft/world/ChunkRegion;JLnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/biome/source/BiomeAccess;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/GenerationStep$Carver;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$skipCarvers(ChunkRegion region,
                                        long seed,
                                        NoiseConfig noiseConfig,
                                        net.minecraft.world.biome.source.BiomeAccess biomeAccess,
                                        StructureAccessor structureAccessor,
                                        Chunk chunk,
                                        GenerationStep.Carver carverStep,
                                        CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;

        if (shouldUseTopOnly(region.toServerWorld(), chunk)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "populateEntities(Lnet/minecraft/world/ChunkRegion;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$skipEntityPopulation(ChunkRegion region,
                                                 CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;
        Chunk centerChunk = region.getCenterPos() != null
                ? region.getChunk(region.getCenterPos().x, region.getCenterPos().z)
                : null;

        if (centerChunk != null && shouldUseTopOnly(region.toServerWorld(), centerChunk)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$populateTopOnlyChunkDirect(Blender blender,
                                                       StructureAccessor structureAccessor,
                                                       NoiseConfig noiseConfig,
                                                       Chunk chunk,
                                                       int minimumCellY,
                                                       int cellHeight,
                                                       CallbackInfoReturnable<Chunk> cir) {
        if (!MyelinMod.isEnabled()) return;

        if (!shouldUseTopOnly(structureAccessor, chunk)) {
            return;
        }

        ServerWorld world = resolveServerWorld(structureAccessor);
        if (world == null) {
            return;
        }
        MyelinChunkManager manager = MyelinMod.getChunkManager();

        return;
    }

    private boolean shouldUseTopOnly(ServerWorld world, Chunk chunk) {
        try {
            MyelinChunkManager manager = MyelinMod.getChunkManager();
            if (manager == null) {
                return false;
            }
            return manager.shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z);
        } catch (Throwable t) {
            LOGGER.error("[Myelin] NoiseChunkGeneratorMixin.shouldUseTopOnly caught exception, returning false for chunk [{},{}]",
                    chunk.getPos().x, chunk.getPos().z, t);
            return false;
        }
    }

    private boolean shouldUseTopOnly(StructureAccessor structureAccessor, Chunk chunk) {
        try {
            ServerWorld world = resolveServerWorld(structureAccessor);
            if (world == null) {
                return false;
            }
            return shouldUseTopOnly(world, chunk);
        } catch (Throwable t) {
            LOGGER.error("[Myelin] NoiseChunkGeneratorMixin.shouldUseTopOnly(accessor) caught exception, returning false for chunk [{},{}]",
                    chunk.getPos().x, chunk.getPos().z, t);
            return false;
        }
    }

    private ServerWorld resolveServerWorld(StructureAccessor structureAccessor) {
        WorldAccess world = ((StructureAccessorAccessor) structureAccessor).myelin$getWorld();
        if (world instanceof ServerWorld serverWorld) {
            return serverWorld;
        }
        if (world instanceof ChunkRegion chunkRegion) {
            return chunkRegion.toServerWorld();
        }
        return null;
    }
}
