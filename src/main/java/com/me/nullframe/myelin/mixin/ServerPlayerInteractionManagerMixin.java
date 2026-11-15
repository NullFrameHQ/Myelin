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

import com.me.nullframe.myelin.server.chunk.MyelinChunkTracking;
import com.me.nullframe.myelin.MyelinMod;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {

    @Shadow
    protected ServerWorld world;

    private boolean myelin$isTopOnly(BlockPos pos) {
        if (!MyelinMod.isEnabled()) return false;
        return MyelinChunkTracking.isTemporaryTopOnlyChunk(
                world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    // Discarding block breaks
    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void myelin$rejectBreakOnTopOnly(BlockPos pos,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (myelin$isTopOnly(pos)) {
            cir.setReturnValue(false);
        }
    }

    // No breaking or placing blocks on surface chunks. They are meant to be temporary
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void myelin$rejectPlaceOnTopOnly(ServerPlayerEntity player,
                                                 World world,
                                                 ItemStack stack,
                                                 Hand hand,
                                                 BlockHitResult hitResult,
                                                 CallbackInfoReturnable<ActionResult> cir) {
        if (myelin$isTopOnly(hitResult.getBlockPos())) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
