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

package com.me.nullframe.myelin.server.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MyelinPlayerRegistry {
    private static final MyelinPlayerRegistry INSTANCE = new MyelinPlayerRegistry();

    private final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();

    private MyelinPlayerRegistry() {
    }

    public static MyelinPlayerRegistry getInstance() {
        return INSTANCE;
    }

    public void register(UUID playerId) {
        moddedPlayers.add(playerId);
    }

    public void unregister(UUID playerId) {
        moddedPlayers.remove(playerId);
    }

    public boolean isModded(UUID playerId) {
        return moddedPlayers.contains(playerId);
    }

    public void clear() {
        moddedPlayers.clear();
    }
}

