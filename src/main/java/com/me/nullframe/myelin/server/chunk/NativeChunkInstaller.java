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

import com.nullframe.core.cubiomes.NativeChunkData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowyBlock;
import net.minecraft.util.collection.EmptyPaletteStorage;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.registry.Registry;

import java.util.ArrayList;
import java.util.Arrays;

/** Installs complete native sections; vegetation and terrain take exactly the same path. */
final class NativeChunkInstaller {
    private static final BlockState[] STATES = new BlockState[NativeChunkData.MAX_BLOCK_ID + 1];
    static {
        STATES[0] = Blocks.AIR.getDefaultState();
        for (int i = 1; i < STATES.length; i++) STATES[i] = VegetationBlockMapper.toBlockState(i);
        STATES[64] = Blocks.STONE.getDefaultState();
        STATES[65] = Blocks.DIRT.getDefaultState();
        STATES[66] = Blocks.GRASS_BLOCK.getDefaultState();
        STATES[67] = Blocks.GRASS_BLOCK.getDefaultState().with(SnowyBlock.SNOWY, true);
        BlockState[] rest = { Blocks.SAND.getDefaultState(), Blocks.SANDSTONE.getDefaultState(),
                Blocks.WATER.getDefaultState(), Blocks.BEDROCK.getDefaultState(), Blocks.DEEPSLATE.getDefaultState(),
                Blocks.MYCELIUM.getDefaultState(), Blocks.PODZOL.getDefaultState(), Blocks.TERRACOTTA.getDefaultState(),
                Blocks.WHITE_TERRACOTTA.getDefaultState(), Blocks.LIGHT_GRAY_TERRACOTTA.getDefaultState(),
                Blocks.YELLOW_TERRACOTTA.getDefaultState(), Blocks.ORANGE_TERRACOTTA.getDefaultState(), Blocks.RED_TERRACOTTA.getDefaultState() };
        System.arraycopy(rest, 0, STATES, 68, rest.length);
        STATES[NativeChunkData.GRAVEL] = Blocks.GRAVEL.getDefaultState();
        STATES[NativeChunkData.COARSE_DIRT] = Blocks.COARSE_DIRT.getDefaultState();
    }

    static void install(NativeChunkData data, ChunkSection[] destination, Registry<Biome> biomes,
                        boolean[] modified, BlockState diagnostic) {
        if (destination.length != (data.options().topYExclusive() - data.options().bottomY()) / 16)
            throw new IllegalArgumentException("Native result does not match Minecraft chunk height");
        Arrays.fill(modified, false);
        for (NativeChunkData.Section source : data.sections()) {
            ArrayList<BlockState> entries = new ArrayList<>();
            for (int id : source.paletteIds()) {
                BlockState state = id < STATES.length ? STATES[id] : null;
                if (state == null) throw new IllegalStateException("Unmapped native block ID " + id);
                entries.add(state);
            }
            PaletteStorage storage = source.bits() == 0 ? new EmptyPaletteStorage(4096)
                    : new PackedIntegerArray(source.bits(), 4096, source.copyStorage());
            var provider = PalettedContainer.PaletteProvider.BLOCK_STATE;
            var blocks = new PalettedContainer<>(Block.STATE_IDS, provider,
                    provider.createDataProvider(Block.STATE_IDS, source.bits()), storage, entries);
            // Constructor calculates non-air, random-tick and fluid counts from the final palette.
            var existing = destination[source.index()];
            var biomeContainer = existing != null ? existing.getBiomeContainer()
                    : new PalettedContainer<>(biomes.getIndexedEntries(), biomes.entryOf(BiomeKeys.PLAINS),
                            PalettedContainer.PaletteProvider.BIOME);
            destination[source.index()] = new ChunkSection(blocks, biomeContainer);
            modified[source.index()] = true;
        }
        if (diagnostic != null) for (int position : data.diagnosticPositions()) {
            int index = position >>> 12, cell = position & 4095;
            destination[index].setBlockState(cell & 15, cell >>> 8, (cell >>> 4) & 15, diagnostic, false);
        }
    }
}
