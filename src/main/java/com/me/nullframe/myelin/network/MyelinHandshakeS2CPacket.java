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

import com.me.nullframe.myelin.MyelinMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record MyelinHandshakeS2CPacket(String serverModVersion, long worldSeed) implements CustomPayload {
    public static final Id<MyelinHandshakeS2CPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "handshake_s2c"));

    public static final PacketCodec<RegistryByteBuf, MyelinHandshakeS2CPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public MyelinHandshakeS2CPacket decode(RegistryByteBuf buf) {
                    return new MyelinHandshakeS2CPacket(buf.readString(64), buf.readLong());
                }

                @Override
                public void encode(RegistryByteBuf buf, MyelinHandshakeS2CPacket value) {
                    buf.writeString(value.serverModVersion(), 64);
                    buf.writeLong(value.worldSeed());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

