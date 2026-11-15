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

package com.me.nullframe.myelin;

import com.nullframe.core.cubiomes.Cubiomes;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** Maps Minecraft worlds to the integer dimension IDs used by NullFrame Core. */
public final class DimensionIds {

    private DimensionIds() {
    }

    public static int fromWorld(World world) {
        Identifier key = world.getRegistryKey().getValue();
        if (World.OVERWORLD.getValue().equals(key)) return Cubiomes.DIMENSION_OVERWORLD;
        if (World.NETHER.getValue().equals(key))    return Cubiomes.DIMENSION_NETHER;
        if (World.END.getValue().equals(key))       return Cubiomes.DIMENSION_END;
        return Cubiomes.DIMENSION_CUSTOM;
    }
}
