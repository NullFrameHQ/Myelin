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

/**
 * Client region preference: 0 = Auto, 1 = On, 2 = Off.
 * Auto uses regions for terrain sampling 1:32 or finer.
 */
public record RegionModeC2SPacket(int mode) implements CustomPayload {
    public static final Id<RegionModeC2SPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "region_mode_c2s"));

    public static final PacketCodec<RegistryByteBuf, RegionModeC2SPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public RegionModeC2SPacket decode(RegistryByteBuf buf) {
                    return new RegionModeC2SPacket(buf.readVarInt());
                }

                @Override
                public void encode(RegistryByteBuf buf, RegionModeC2SPacket value) {
                    buf.writeVarInt(value.mode());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
