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

package com.me.nullframe.myelin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Server options loaded from myelin.json. Client biome preferences stay in runtime state. */
public final class MyelinConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "myelin.json";

    /** Master switch for this installation. Changing it requires a client/server restart. */
    public volatile boolean enabled = true;
    /** Solid layers beneath each sampled surface block (0..4096). Restart required. */
    public int surfaceChunkDepth = 3;
    // Fill the ground beneath Surface chunks down to the world bottom.
    // Otherwise keep the thin surface shell and its bottom slab.
    public boolean fillBottom = true;
    /** Generate and place Myelin vegetation. Changing this requires a restart. */
    public boolean addVegetation = true;
    /** Extra snapshot prefetch ahead of movement, or facing while stationary (0–32 chunks). Restart required. */
    public int snapshotPrefetchMarginChunks = 4;
    // Maximum number of cached snapshots, not a byte limit.
    // Leave room for active player windows and recently visited terrain.
    public int cacheMaxEntries = 16384;

    /** Native region computation: off, on, or auto (on through 1:32). Client GUI can change it. */
    public volatile String regionPathMode = "off";

    /** Whether the region path should be used for the given sampling step. */
    public boolean regionPathEnabledFor(int samplingStep) {
        if ("on".equals(regionPathMode)) return true;
        if ("off".equals(regionPathMode)) return false;
        return samplingStep <= 32;
    }

    public int cacheTtlSeconds = 120;
    // Enable the Overworld by default. Myelin terrain is only tested for this dimension.
    public List<String> dimensionAllowlist = new ArrayList<>(List.of("minecraft:overworld"));
    public List<String> dimensionDenylist = new ArrayList<>();
    /** Replace surface blocks with cache-stage colors. Restart required; never affects cache counters. */
    public boolean debugColorChunksCacheStage = false;
    /** Require the client handshake for a player's Myelin eligibility; does not reject joins. */
    public volatile boolean requireClientMod = true;
    /**
     * Terrain sample spacing. Larger steps use fewer samples and lose detail.
     * Players can change this through the client settings.
     */
    public volatile int samplingStep = 1;
    /** Native generation workers: -1 = automatic, 0 = disabled, 1..20 = fixed count. */
    public volatile int workerThreads = -1;

    public static MyelinConfig load(Path configDir) {
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path configFile = configDir.resolve(FILE_NAME);
            if (!Files.exists(configFile)) {
                MyelinConfig defaults = new MyelinConfig();
                defaults.save(configFile);
                return defaults;
            }
            JsonObject json;
            try (Reader reader = Files.newBufferedReader(configFile)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }
            MyelinConfig loaded = GSON.fromJson(json, MyelinConfig.class);
            if (loaded == null) loaded = new MyelinConfig();
            loaded.sanitize();
            migrateObsoleteFields(configFile, json);
            return loaded;
        } catch (IOException exception) {
            return new MyelinConfig();
        }
    }

    /** Removes retired keys while preserving the admin's other values and unknown settings. */
    private static void migrateObsoleteFields(Path file, JsonObject json) {
        boolean changed = false;
        for (String key : List.of("checkIntervalTicks", "interactionRadiusChunks", "logDebug",
                "correctionAmp", "biomeStep", "topOnlyOuterRadiusChunks")) {
            changed |= json.remove(key) != null;
        }
        if (!json.has("debugColorChunksCacheStage")) {
            json.addProperty("debugColorChunksCacheStage", false);
            changed = true;
        }
        if (changed) {
            Path temporary = null;
            try {
                temporary = Files.createTempFile(file.getParent(), "myelin-migration-", ".tmp");
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    GSON.toJson(json, writer);
                }
                try {
                    Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                // A read-only config still supplies its valid settings for this session.
                org.apache.logging.log4j.LogManager.getLogger("myelin")
                        .warn("[Myelin] Could not remove retired config keys from {}", file, exception);
            } finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public void save(Path configFile) throws IOException {
        sanitize();
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(this, writer);
        }
    }

    private void sanitize() {
        if (regionPathMode == null
                || !("auto".equals(regionPathMode) || "on".equals(regionPathMode) || "off".equals(regionPathMode))) {
            regionPathMode = "auto";
        }
        surfaceChunkDepth = Math.max(0, Math.min(4096, surfaceChunkDepth));
        snapshotPrefetchMarginChunks = Math.max(0, Math.min(32, snapshotPrefetchMarginChunks));
        if (cacheMaxEntries < 32) {
            cacheMaxEntries = 32;
        }
        if (cacheTtlSeconds < 5) {
            cacheTtlSeconds = 5;
        }

        if (workerThreads < -1) workerThreads = -1;
        if (workerThreads > 20) workerThreads = 20;

        if (dimensionAllowlist == null) {
            dimensionAllowlist = new ArrayList<>();
        }
        if (dimensionDenylist == null) {
            dimensionDenylist = new ArrayList<>();
        }
    }
}
