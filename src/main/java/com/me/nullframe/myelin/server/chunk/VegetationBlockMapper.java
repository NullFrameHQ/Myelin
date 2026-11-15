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

package com.me.nullframe.myelin.server.chunk;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.MushroomBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoubleBlockHalf;

/**
 * Maps native block IDs to Minecraft block states once per section palette entry.
 * Terrain and vegetation share this mapping. Keep the constants in sync with
 * the BlockType enum in cubiomes_bridge.c.
 */
public final class VegetationBlockMapper {

    // Native block IDs; these must match the BlockType enum in cubiomes_bridge.c.
    public static final int BLK_AIR              = 0;
    public static final int BLK_OAK_LOG          = 10;
    public static final int BLK_BIRCH_LOG        = 11;
    public static final int BLK_SPRUCE_LOG       = 12;
    public static final int BLK_DARK_OAK_LOG     = 13;
    public static final int BLK_ACACIA_LOG       = 14;
    public static final int BLK_JUNGLE_LOG       = 15;
    public static final int BLK_CHERRY_LOG       = 16;
    public static final int BLK_OAK_LEAVES       = 20;
    public static final int BLK_BIRCH_LEAVES     = 21;
    public static final int BLK_SPRUCE_LEAVES    = 22;
    public static final int BLK_DARK_OAK_LEAVES  = 23;
    public static final int BLK_ACACIA_LEAVES    = 24;
    public static final int BLK_JUNGLE_LEAVES    = 25;
    public static final int BLK_CHERRY_LEAVES    = 26;
    public static final int BLK_DANDELION        = 30;
    public static final int BLK_POPPY            = 31;
    public static final int BLK_AZURE_BLUET      = 32;
    public static final int BLK_OXEYE_DAISY      = 33;
    public static final int BLK_CORNFLOWER       = 34;
    public static final int BLK_RED_TULIP        = 35;
    public static final int BLK_ORANGE_TULIP     = 36;
    public static final int BLK_WHITE_TULIP      = 37;
    public static final int BLK_PINK_TULIP       = 38;
    public static final int BLK_SHORT_GRASS      = 40;
    public static final int BLK_TALL_GRASS_LOWER = 41;
    public static final int BLK_TALL_GRASS_UPPER = 42;
    public static final int BLK_SUNFLOWER_LOWER  = 43;
    public static final int BLK_SUNFLOWER_UPPER  = 44;
    public static final int BLK_CACTUS           = 45;
    public static final int BLK_DEAD_BUSH        = 46;
    public static final int BLK_SNOW_LAYER       = 47;
    public static final int BLK_BROWN_MUSHROOM   = 48;
    public static final int BLK_RED_MUSHROOM     = 49;
    public static final int BLK_MUSHROOM_STEM    = 50;

    private VegetationBlockMapper() {}

    /**
     * Resolves a block ID from the native section palette.
     * Returns null for air or an unknown ID.
     */
    public static BlockState toBlockState(int blockType) {
        if (blockType >= 96 && blockType < 160) {
            int faces = (blockType - 96) & 31;
            return (blockType < 128 ? Blocks.BROWN_MUSHROOM_BLOCK : Blocks.RED_MUSHROOM_BLOCK).getDefaultState()
                    .with(MushroomBlock.WEST, (faces & 1) != 0).with(MushroomBlock.EAST, (faces & 2) != 0)
                    .with(MushroomBlock.NORTH, (faces & 4) != 0).with(MushroomBlock.SOUTH, (faces & 8) != 0)
                    .with(MushroomBlock.UP, (faces & 16) != 0).with(MushroomBlock.DOWN, false);
        }
        return switch (blockType) {
            // Logs
            case BLK_OAK_LOG       -> Blocks.OAK_LOG.getDefaultState();
            case BLK_BIRCH_LOG     -> Blocks.BIRCH_LOG.getDefaultState();
            case BLK_SPRUCE_LOG    -> Blocks.SPRUCE_LOG.getDefaultState();
            case BLK_DARK_OAK_LOG  -> Blocks.DARK_OAK_LOG.getDefaultState();
            case BLK_ACACIA_LOG    -> Blocks.ACACIA_LOG.getDefaultState();
            case BLK_JUNGLE_LOG    -> Blocks.JUNGLE_LOG.getDefaultState();
            case BLK_CHERRY_LOG    -> Blocks.CHERRY_LOG.getDefaultState();

            // Leaves (with PERSISTENT=true so they don't decay)
            case BLK_OAK_LEAVES      -> persistentLeaves(Blocks.OAK_LEAVES);
            case BLK_BIRCH_LEAVES    -> persistentLeaves(Blocks.BIRCH_LEAVES);
            case BLK_SPRUCE_LEAVES   -> persistentLeaves(Blocks.SPRUCE_LEAVES);
            case BLK_DARK_OAK_LEAVES -> persistentLeaves(Blocks.DARK_OAK_LEAVES);
            case BLK_ACACIA_LEAVES   -> persistentLeaves(Blocks.ACACIA_LEAVES);
            case BLK_JUNGLE_LEAVES   -> persistentLeaves(Blocks.JUNGLE_LEAVES);
            case BLK_CHERRY_LEAVES   -> persistentLeaves(Blocks.CHERRY_LEAVES);

            // Flowers
            case BLK_DANDELION   -> Blocks.DANDELION.getDefaultState();
            case BLK_POPPY       -> Blocks.POPPY.getDefaultState();
            case BLK_AZURE_BLUET -> Blocks.AZURE_BLUET.getDefaultState();
            case BLK_OXEYE_DAISY -> Blocks.OXEYE_DAISY.getDefaultState();
            case BLK_CORNFLOWER  -> Blocks.CORNFLOWER.getDefaultState();
            case BLK_RED_TULIP   -> Blocks.RED_TULIP.getDefaultState();
            case BLK_ORANGE_TULIP -> Blocks.ORANGE_TULIP.getDefaultState();
            case BLK_WHITE_TULIP -> Blocks.WHITE_TULIP.getDefaultState();
            case BLK_PINK_TULIP  -> Blocks.PINK_TULIP.getDefaultState();

            // Grass
            case BLK_SHORT_GRASS      -> Blocks.SHORT_GRASS.getDefaultState();
            case BLK_TALL_GRASS_LOWER -> Blocks.TALL_GRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER);
            case BLK_TALL_GRASS_UPPER -> Blocks.TALL_GRASS.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER);
            case BLK_SUNFLOWER_LOWER  -> Blocks.SUNFLOWER.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER);
            case BLK_SUNFLOWER_UPPER  -> Blocks.SUNFLOWER.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER);

            // Desert / badlands
            case BLK_CACTUS    -> Blocks.CACTUS.getDefaultState();
            case BLK_DEAD_BUSH -> Blocks.DEAD_BUSH.getDefaultState();

            // Snow layer (tree caps in snowy biomes)
            case BLK_SNOW_LAYER -> Blocks.SNOW.getDefaultState();
            case BLK_BROWN_MUSHROOM -> Blocks.BROWN_MUSHROOM.getDefaultState();
            case BLK_RED_MUSHROOM -> Blocks.RED_MUSHROOM.getDefaultState();
            case BLK_MUSHROOM_STEM -> Blocks.MUSHROOM_STEM.getDefaultState()
                    .with(MushroomBlock.UP, false).with(MushroomBlock.DOWN, false);

            // Air or unknown
            default -> null;
        };
    }

    private static BlockState persistentLeaves(Block leavesBlock) {
        BlockState state = leavesBlock.getDefaultState();
        return state.contains(LeavesBlock.PERSISTENT) ? state.with(LeavesBlock.PERSISTENT, true) : state;
    }
}
