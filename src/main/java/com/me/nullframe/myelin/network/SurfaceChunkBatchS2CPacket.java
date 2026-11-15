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

package com.me.nullframe.myelin.network;

import com.nullframe.core.StepTimings;

import com.me.nullframe.myelin.MyelinMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sends up to {@link #MAX_CHUNKS_PER_BATCH} cached previews in one packet.
 * Each entry has chunk coordinates, a delta-encoded heightmap, and surface types.
 * Batching reduces the number of separate network sends.
 */
public record SurfaceChunkBatchS2CPacket(int dimensionId, ChunkEntry[] chunks) implements CustomPayload {
    public static final int MAX_CHUNKS_PER_BATCH = 512;
    private static final int HEIGHTMAP_SIZE = 16 * 16;

    public record ChunkEntry(int chunkX, int chunkZ, int[] heightmap, byte[] surfaceTypes) {
    }

    public static final Id<SurfaceChunkBatchS2CPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "surface_chunk_batch"));

    public static final PacketCodec<RegistryByteBuf, SurfaceChunkBatchS2CPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public SurfaceChunkBatchS2CPacket decode(RegistryByteBuf buf) {
                    long tDecode = System.nanoTime();
                    int dimensionId = buf.readVarInt();
                    int count = buf.readVarInt();
                    if (count > MAX_CHUNKS_PER_BATCH) {
                        count = MAX_CHUNKS_PER_BATCH; // Limit the number of entries decoded.
                    }
                    ChunkEntry[] chunks = new ChunkEntry[count];
                    for (int c = 0; c < count; c++) {
                        int chunkX = buf.readVarInt();
                        int chunkZ = buf.readVarInt();
                        int[] heightmap = new int[HEIGHTMAP_SIZE];
                        int running = 0;
                        for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
                            running += zigZagDecode(buf.readVarInt());
                            heightmap[i] = running;
                        }
                        byte[] surfaceTypes = new byte[HEIGHTMAP_SIZE];
                        for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
                            surfaceTypes[i] = buf.readByte();
                        }
                        chunks[c] = new ChunkEntry(chunkX, chunkZ, heightmap, surfaceTypes);
                    }
                    StepTimings.record(StepTimings.Step.CLIENT_DECODE, tDecode);
                    return new SurfaceChunkBatchS2CPacket(dimensionId, chunks);
                }

                @Override
                public void encode(RegistryByteBuf buf, SurfaceChunkBatchS2CPacket value) {
                    buf.writeVarInt(value.dimensionId());
                    buf.writeVarInt(value.chunks().length);
                    for (ChunkEntry e : value.chunks()) {
                        buf.writeVarInt(e.chunkX());
                        buf.writeVarInt(e.chunkZ());
                        int previous = 0;
                        for (int y : e.heightmap()) {
                            int delta = y - previous;
                            previous = y;
                            buf.writeVarInt(zigZagEncode(delta));
                        }
                        for (byte surfaceType : e.surfaceTypes()) {
                            buf.writeByte(surfaceType);
                        }
                    }
                }
            };

    public SurfaceChunkBatchS2CPacket {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        if (chunks.length > MAX_CHUNKS_PER_BATCH) {
            throw new IllegalArgumentException("batch too large: " + chunks.length);
        }
        for (ChunkEntry e : chunks) {
            if (e == null || e.heightmap() == null || e.heightmap().length != HEIGHTMAP_SIZE) {
                throw new IllegalArgumentException("heightmap must be exactly 256 integers");
            }
            if (e.surfaceTypes() == null || e.surfaceTypes().length != HEIGHTMAP_SIZE) {
                throw new IllegalArgumentException("surfaceTypes must be exactly 256 bytes");
            }
        }
        // Copy the arrays so later changes to a cached snapshot cannot change this packet.
        ChunkEntry[] copy = new ChunkEntry[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            ChunkEntry e = chunks[i];
            copy[i] = new ChunkEntry(e.chunkX(), e.chunkZ(), e.heightmap().clone(), e.surfaceTypes().clone());
        }
        chunks = copy;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static int zigZagEncode(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int zigZagDecode(int value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
