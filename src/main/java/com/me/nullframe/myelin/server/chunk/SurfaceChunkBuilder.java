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

import com.me.nullframe.myelin.config.MyelinConfig;
import com.me.nullframe.myelin.server.MyelinChunkManager;

import com.me.nullframe.myelin.server.MyelinChunkManager.SnapshotLookupKind;
import com.me.nullframe.myelin.mixin.ChunkAccessor;
import com.nullframe.core.StepTimings;
import com.nullframe.core.cubiomes.Cubiomes;
import com.nullframe.core.cubiomes.SurfaceChunkSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WrapperProtoChunk;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class SurfaceChunkBuilder {
    private final MyelinConfig config;
    private final SurfaceChunkLifecycle surfaceChunkLifecycle;
    private final BooleanSupplier closed;
    private final LongSupplier surfaceCacheGeneration;

    private static final ThreadLocal<BlockPos.Mutable> CURSOR_TL = ThreadLocal.withInitial(BlockPos.Mutable::new);

    public SurfaceChunkBuilder(MyelinConfig config, SurfaceChunkLifecycle surfaceChunkLifecycle,
                        BooleanSupplier closed, LongSupplier surfaceCacheGeneration) {
        this.config = config;
        this.surfaceChunkLifecycle = surfaceChunkLifecycle;
        this.closed = closed;
        this.surfaceCacheGeneration = surfaceCacheGeneration;
    }

    public static BlockState cacheStageColor(boolean enabled, SnapshotLookupKind kind) {
        return !enabled ? null : switch (kind) {
            case CACHE_HIT -> null;
            case READY_PENDING -> Blocks.BLUE_CONCRETE.getDefaultState();
            case UNFINISHED_PENDING -> Blocks.ORANGE_CONCRETE.getDefaultState();
            case NO_USABLE_PENDING -> Blocks.PINK_CONCRETE.getDefaultState();
        };
    }

    public Chunk build(ServerWorld world, Chunk chunk, SurfaceChunkSnapshot snapshot,
                                      SnapshotLookupKind lookupKind, long generation) {
        if (closed.getAsBoolean()) throw new java.util.concurrent.CancellationException("Myelin closed");
        if (snapshot == null) return chunk;
        // Capture before generation bookkeeping advances the ProtoChunk's status.
        boolean initialLightingPending = canUseVanillaInitialLighting(chunk);
        BlockState diagnosticSurface = cacheStageColor(config.debugColorChunksCacheStage, lookupKind);
        surfaceChunkLifecycle.markTemporaryTopOnlyChunk(world, chunk, generation);
        forceClearAllBlocksInChunk(chunk);
        int bottomY = chunk.getBottomY();
        int chunkStartX = chunk.getPos().getStartX(), chunkStartZ = chunk.getPos().getStartZ();
        ChunkSection[] sections = ((ChunkAccessor) chunk).myelin$getSections();
        boolean[] modifiedSections = new boolean[sections.length];
        long installed = System.nanoTime();
        NativeChunkInstaller.install(snapshot.nativeChunkData(), sections,
                world.getRegistryManager().get(RegistryKeys.BIOME), modifiedSections, diagnosticSurface);
        StepTimings.record(StepTimings.Step.INSTALL_SECTIONS, installed);
        final ServerWorld fWorld = world;
        final Chunk fChunk = chunk;
        final int fBottomY = bottomY, fChunkStartX = chunkStartX, fChunkStartZ = chunkStartZ;
        final boolean[] fModifiedSections = modifiedSections;

        // Fresh chunks will be lit by the later INITIALIZE_LIGHT and LIGHT stages.
        // Reused or already initialized chunks still need explicit light checks.
        if (!initialLightingPending) {
            long tLight = System.nanoTime();
            BlockPos.Mutable cursor = CURSOR_TL.get();
            var lightingProvider = fWorld.getChunkManager().getLightingProvider();
            for (int sectionIndex = 0; sectionIndex < fModifiedSections.length; sectionIndex++) {
                if (!fModifiedSections[sectionIndex]) continue;
                int sectionY = fBottomY + (sectionIndex * 16);
                for (int sy = 0; sy < 16; sy += 8) {
                    for (int sx = 0; sx < 16; sx += 8) {
                        for (int sz = 0; sz < 16; sz += 8) {
                            cursor.set(fChunkStartX + sx, sectionY + sy, fChunkStartZ + sz);
                            lightingProvider.checkBlock(cursor);
                        }
                    }
                }
            }
            StepTimings.record(StepTimings.Step.PASTE_LIGHT, tLight);
        }
        writeSnapshotBiomes(fWorld, fChunk, snapshot);
        markTopOnlyChunkGenerationComplete(fChunk);
        fChunk.refreshSurfaceY();
        fChunk.setNeedsSaving(false);
        if (!closed.getAsBoolean() && generation == surfaceCacheGeneration.getAsLong()) surfaceChunkLifecycle.markResident(world, chunk, generation);
        return chunk;
    }

    /** Only new generation before light initialization may rely on the later vanilla stages. */
    public static boolean canUseVanillaInitialLighting(Chunk chunk) {
        return chunk instanceof ProtoChunk && !(chunk instanceof WrapperProtoChunk)
                && !chunk.isLightOn() && !chunk.hasBelowZeroRetrogen()
                && chunk.getStatus().isEarlierThan(ChunkStatus.INITIALIZE_LIGHT);
    }

    /**
     * Writes snapshot biome IDs because Surface chunks skip vanilla biome generation.
     * Each section uses the same 4x4 biome grid, repeated through its four vertical layers.
     */
    private void writeSnapshotBiomes(ServerWorld world, Chunk chunk, SurfaceChunkSnapshot snapshot) {
        Registry<Biome> biomeRegistry = world.getRegistryManager().get(RegistryKeys.BIOME);
        if (biomeRegistry == null) return;
        ChunkSection[] sections = ((ChunkAccessor) chunk).myelin$getSections();

        RegistryEntry.Reference<Biome> plains = biomeRegistry.entryOf(BiomeKeys.PLAINS);
        RegistryEntry<Biome>[] cells = new RegistryEntry[16];
        int[] biomeIds = snapshot.biomeIdsView();
        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                int localX = cx * 4 + 2;
                int localZ = cz * 4 + 2;
                RegistryKey<Biome> key = biomeKeyForCubiomesId(biomeIds[localZ * 16 + localX]);
                RegistryEntry<Biome> entry = key == null
                        ? plains
                        : biomeRegistry.getEntry(key).orElse(plains);
                cells[cz * 4 + cx] = entry;
            }
        }

        for (ChunkSection section : sections) {
            if (section == null) continue;
            var container = section.getBiomeContainer();
            if (!(container instanceof PalettedContainer<?>)) continue;
            @SuppressWarnings("unchecked")
            PalettedContainer<RegistryEntry<Biome>> biomes = (PalettedContainer<RegistryEntry<Biome>>) container;
            for (int layer = 0; layer < 4; layer++) {
                for (int cz = 0; cz < 4; cz++) {
                    for (int cx = 0; cx < 4; cx++) {
                        biomes.set(cx, layer, cz, cells[cz * 4 + cx]);
                    }
                }
            }
        }
    }

    /**
     * Caches the Minecraft biome key for each native biome ID.
     * The keys can be reused across worlds; each world still resolves its own registry entries.
     */
    private static final java.util.Map<Integer, RegistryKey<Biome>> BIOME_KEYS_BY_CUBIOMES_ID =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static RegistryKey<Biome> biomeKeyForCubiomesId(int biomeId) {
        return BIOME_KEYS_BY_CUBIOMES_ID.computeIfAbsent(biomeId, id -> {
            String name = Cubiomes.getBiomeName(id);
            if (name == null || name.isEmpty()) return null;
            Identifier identifier = name.indexOf(':') >= 0
                    ? Identifier.tryParse(name)
                    : Identifier.ofVanilla(name);
            if (identifier == null) return null;
            return RegistryKey.of(RegistryKeys.BIOME, identifier);
        });
    }

    private void forceClearAllBlocksInChunk(Chunk chunk) {
        // Fresh EMPTY ProtoChunks need no clearing; leave their empty containers in place.
        if (chunk instanceof ProtoChunk protoChunk && protoChunk.getStatus() == ChunkStatus.EMPTY) {
            return;
        }

        ChunkSection[] sections = ((ChunkAccessor) chunk).myelin$getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection existingSection = sections[sectionIndex];
            if (existingSection == null || existingSection.isEmpty()) {
                continue;
            }
            sections[sectionIndex] = new ChunkSection(
                    new PalettedContainer<>(
                            Block.STATE_IDS,
                            Blocks.AIR.getDefaultState(),
                            PalettedContainer.PaletteProvider.BLOCK_STATE
                    ),
                    existingSection.getBiomeContainer()
            );
        }
    }

    private void markTopOnlyChunkGenerationComplete(Chunk chunk) {
        if (chunk instanceof ProtoChunk protoChunk) {
            protoChunk.setStatus(ChunkStatus.FEATURES);
        }
    }

}
