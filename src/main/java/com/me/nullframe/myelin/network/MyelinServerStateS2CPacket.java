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

/** Reports the server's master switch without activating generation or sharing a seed. */
public record MyelinServerStateS2CPacket(boolean enabled) implements CustomPayload {
    public static final Id<MyelinServerStateS2CPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "server_state_s2c"));

    public static final PacketCodec<RegistryByteBuf, MyelinServerStateS2CPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public MyelinServerStateS2CPacket decode(RegistryByteBuf buf) {
                    return new MyelinServerStateS2CPacket(buf.readBoolean());
                }

                @Override
                public void encode(RegistryByteBuf buf, MyelinServerStateS2CPacket value) {
                    buf.writeBoolean(value.enabled());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
