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
 * Tells the server whether this client needs cached preview terrain.
 * Turning the preview renderer off stops preview packets for that player.
 */
public record PreviewRendererStateC2SPacket(boolean enabled) implements CustomPayload {
    public static final Id<PreviewRendererStateC2SPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "preview_renderer_state_c2s"));

    public static final PacketCodec<RegistryByteBuf, PreviewRendererStateC2SPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public PreviewRendererStateC2SPacket decode(RegistryByteBuf buf) {
                    return new PreviewRendererStateC2SPacket(buf.readBoolean());
                }

                @Override
                public void encode(RegistryByteBuf buf, PreviewRendererStateC2SPacket value) {
                    buf.writeBoolean(value.enabled());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}