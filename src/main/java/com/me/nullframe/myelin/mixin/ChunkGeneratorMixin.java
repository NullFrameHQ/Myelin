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
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkGenerator.class, priority = 2000)
public abstract class ChunkGeneratorMixin {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);

    @Inject(
            method = "generateFeatures(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/StructureAccessor;)V",
            at = @At("HEAD"),
            cancellable = true
    )

    private void myelin$skipFeatures(StructureWorldAccess world,
                                         Chunk chunk,
                                         StructureAccessor structureAccessor,
                                         CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;
        ServerWorld serverWorld = resolveServerWorld(world);
        if (serverWorld == null) {
            return;
        }

        boolean topOnly = shouldUseTopOnly(serverWorld, chunk);

        if (topOnly) {
            ci.cancel();
        }
    }

    @Inject(
            method = "setStructureStarts(Lnet/minecraft/registry/DynamicRegistryManager;Lnet/minecraft/world/gen/chunk/placement/StructurePlacementCalculator;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/structure/StructureTemplateManager;)V",
            at = @At("HEAD"),
            cancellable = true
    )

    private void myelin$skipStructureStarts(DynamicRegistryManager dynamicRegistryManager,
                                                StructurePlacementCalculator structurePlacementCalculator,
                                                StructureAccessor structureAccessor,
                                                Chunk chunk,
                                                StructureTemplateManager structureTemplateManager,
                                                CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;
        ServerWorld world = resolveServerWorld(structureAccessor);
        if (world == null) {
            return;
        }

        boolean topOnly = shouldUseTopOnly(world, chunk);

        if (topOnly) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addStructureReferences(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myelin$skipStructureReferences(StructureWorldAccess world,
                                                    StructureAccessor structureAccessor,
                                                    Chunk chunk,
                                                    CallbackInfo ci) {
        if (!MyelinMod.isEnabled()) return;
        ServerWorld serverWorld = resolveServerWorld(world);

        if (serverWorld != null) {
            boolean topOnly = shouldUseTopOnly(serverWorld, chunk);

            if (topOnly) {
                ci.cancel();
            }
        }
    }

    private boolean shouldUseTopOnly(ServerWorld world, Chunk chunk) {
        try {
            MyelinChunkManager manager = MyelinMod.getChunkManager();
            if (manager == null) {
                return false;
            }
            return manager.shouldUseTopOnlyGeneration(world, chunk.getPos().x, chunk.getPos().z);
        } catch (Throwable t) {
            LOGGER.error("[Myelin] ChunkGeneratorMixin.shouldUseTopOnly caught exception, returning false for chunk [{},{}]",
                    chunk.getPos().x, chunk.getPos().z, t);
            return false;
        }
    }

    private ServerWorld resolveServerWorld(StructureWorldAccess world) {
        if (world instanceof ChunkRegion region) {
            return region.toServerWorld();
        }
        if (world instanceof ServerWorld serverWorld) {
            return serverWorld;
        }
        return null;
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
