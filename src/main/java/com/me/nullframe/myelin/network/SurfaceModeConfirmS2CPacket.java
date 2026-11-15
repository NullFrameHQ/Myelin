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
 * Reports the server's Flight Mode state to the client.
 * When turning Flight Mode off, a nonzero handoff radius asks the client to clear
 * cached previews in that square around the player.
 */
public record SurfaceModeConfirmS2CPacket(boolean actualMode, int groundHandoffPreviewRadiusChunks)
        implements CustomPayload {
    public static final Id<SurfaceModeConfirmS2CPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "surface_mode_confirm_s2c"));

    public static final PacketCodec<RegistryByteBuf, SurfaceModeConfirmS2CPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public SurfaceModeConfirmS2CPacket decode(RegistryByteBuf buf) {
                    boolean mode = buf.readBoolean();
                    int radiusChunks = buf.readVarInt();
                    return new SurfaceModeConfirmS2CPacket(mode, radiusChunks);
                }

                @Override
                public void encode(RegistryByteBuf buf, SurfaceModeConfirmS2CPacket value) {
                    buf.writeBoolean(value.actualMode());
                    buf.writeVarInt(value.groundHandoffPreviewRadiusChunks());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
