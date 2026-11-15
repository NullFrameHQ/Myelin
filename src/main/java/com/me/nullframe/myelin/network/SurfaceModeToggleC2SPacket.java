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

public record SurfaceModeToggleC2SPacket() implements CustomPayload {
    public static final Id<SurfaceModeToggleC2SPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "surface_mode_toggle_c2s"));

    public static final PacketCodec<RegistryByteBuf, SurfaceModeToggleC2SPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public SurfaceModeToggleC2SPacket decode(RegistryByteBuf buf) {
                    return new SurfaceModeToggleC2SPacket();
                }

                @Override
                public void encode(RegistryByteBuf buf, SurfaceModeToggleC2SPacket value) {
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
