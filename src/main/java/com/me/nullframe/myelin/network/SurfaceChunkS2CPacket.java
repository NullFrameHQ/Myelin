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
 * Lightweight surface-chunk payload carrying a 16x16 top-height map and
 * per-column surface classifications.
 */
public record SurfaceChunkS2CPacket(int dimensionId,
                                    int chunkX,
                                    int chunkZ,
                                    int[] heightmap,
                                    byte[] surfaceTypes) implements CustomPayload {
    public static final Id<SurfaceChunkS2CPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "surface_chunk"));

    public static final PacketCodec<RegistryByteBuf, SurfaceChunkS2CPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public SurfaceChunkS2CPacket decode(RegistryByteBuf buf) {
                    long tDecode = System.nanoTime();
                    int dimensionId = buf.readVarInt();
                    int chunkX = buf.readVarInt();
                    int chunkZ = buf.readVarInt();
                    int[] heightmap = new int[16 * 16];
                    int running = 0;
                    for (int i = 0; i < heightmap.length; i++) {
                        // Delta decode keeps payload compact because terrain is smooth.
                        int delta = zigZagDecode(buf.readVarInt());
                        running += delta;
                        heightmap[i] = running;
                    }
                    byte[] surfaceTypes = new byte[16 * 16];
                    for (int i = 0; i < surfaceTypes.length; i++) {
                        surfaceTypes[i] = buf.readByte();
                    }
                    StepTimings.record(StepTimings.Step.CLIENT_DECODE, tDecode);
                    return new SurfaceChunkS2CPacket(dimensionId, chunkX, chunkZ, heightmap, surfaceTypes);
                }

                @Override
                public void encode(RegistryByteBuf buf, SurfaceChunkS2CPacket value) {
                    if (value.heightmap.length != 16 * 16) {
                        throw new IllegalArgumentException("Surface heightmap must contain exactly 256 entries");
                    }
                    if (value.surfaceTypes.length != 16 * 16) {
                        throw new IllegalArgumentException("Surface type map must contain exactly 256 entries");
                    }
                    buf.writeVarInt(value.dimensionId);
                    buf.writeVarInt(value.chunkX);
                    buf.writeVarInt(value.chunkZ);

                    int previous = 0;
                    for (int y : value.heightmap) {
                        int delta = y - previous;
                        previous = y;
                        buf.writeVarInt(zigZagEncode(delta));
                    }
                    for (byte surfaceType : value.surfaceTypes) {
                        buf.writeByte(surfaceType);
                    }
                }
            };

    public SurfaceChunkS2CPacket {
        if (heightmap == null || heightmap.length != 16 * 16) {
            throw new IllegalArgumentException("heightmap must be exactly 256 integers");
        }
        if (surfaceTypes == null || surfaceTypes.length != 16 * 16) {
            throw new IllegalArgumentException("surfaceTypes must be exactly 256 bytes");
        }
        heightmap = heightmap.clone();
        surfaceTypes = surfaceTypes.clone();
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public int[] copyHeightmap() {
        return heightmap.clone();
    }

    public byte[] copySurfaceTypes() {
        return surfaceTypes.clone();
    }

    private static int zigZagEncode(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int zigZagDecode(int value) {
        return (value >>> 1) ^ -(value & 1);
    }
}

