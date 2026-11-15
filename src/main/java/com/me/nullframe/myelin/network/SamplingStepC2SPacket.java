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

/** Terrain and biome sample levels, plus the effective detail-correction amplitude. */
public record SamplingStepC2SPacket(int samplingStep, int biomeStep, int correctionAmp) implements CustomPayload {
    public SamplingStepC2SPacket {
        correctionAmp = samplingStep >= 16 && correctionAmp > 0 ? 4 : 0;
    }

    public SamplingStepC2SPacket(int samplingStep, int biomeStep) {
        this(samplingStep, biomeStep, samplingStep >= 16 ? 4 : 0);
    }
    public static final Id<SamplingStepC2SPacket> ID =
            new Id<>(Identifier.of(MyelinMod.MOD_ID, "sampling_step_c2s"));

    public static final PacketCodec<RegistryByteBuf, SamplingStepC2SPacket> CODEC =
            new PacketCodec<>() {
                @Override
                public SamplingStepC2SPacket decode(RegistryByteBuf buf) {
                    int terrain = buf.readVarInt();
                    int correction = buf.readVarInt();
                    return new SamplingStepC2SPacket(terrain, buf.readVarInt(), correction);
                }

                @Override
                public void encode(RegistryByteBuf buf, SamplingStepC2SPacket value) {
                    buf.writeVarInt(value.samplingStep());
                    buf.writeVarInt(value.correctionAmp());
                    buf.writeVarInt(value.biomeStep());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
