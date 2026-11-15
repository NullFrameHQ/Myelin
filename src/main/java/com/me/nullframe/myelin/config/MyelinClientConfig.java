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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client preferences saved in config/myelin-client.json.
 * Loaded once and kept in memory for the GUI and renderer.
 */
public class MyelinClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "myelin-client.json";

    public boolean showHudIndicator = false;
    /** Hide Myelin's action-bar notifications without hiding its HUD panel or chat. */
    public boolean disableHudMessages = false;
    public boolean usePreviewRenderer = false;
    /** Detail correction: 0 = Auto, 1 = On, 2 = Off. Requires terrain sampling 1:16+. */
    public int detailCorrectionMode = 0;
    /** Legacy saved switch. Off means 1:1; choosing a level in the GUI enables it. */
    public boolean terrainDownsamplingEnabled = true;
    /** Off selects Auto biome sampling; the previous explicit level stays saved. */
    public boolean biomeDownsamplingEnabled = true;
    /** Requested cached-preview reach in chunks. The renderer keeps an additional retention margin. */
    public int lodRenderDistanceChunks = 64;
    /** Saved terrain sample level, sent to the server on join and when settings change. */
    public int samplingStep = 4;
    /** Saved biome sample level. Disabling biome downsampling sends Auto instead. */
    public int biomeStep = 1;
    /**
     * Region path: 0 = Auto, 1 = On, 2 = Off.
     * Auto uses regions at terrain sampling 1:32 or finer. Sent to the server with preferences.
     */
    public int regionPathMode = 0;

    /** Uses the saved terrain level when enabled, or 1:1 when disabled. */
    public int effectiveSamplingStep() {
        return terrainDownsamplingEnabled ? normalizeStep(samplingStep) : 1;
    }

    public int effectiveBiomeStep() {
        return biomeDownsamplingEnabled ? normalizeStep(biomeStep) : 0;
    }

    public int effectiveCorrectionAmp() {
        return effectiveSamplingStep() >= 16 && detailCorrectionMode != 2 ? 4 : 0;
    }

    public boolean shouldHideHudMessage(String message) {
        if (!disableHudMessages) return false;
        String plain = net.minecraft.util.Formatting.strip(message);
        return plain != null && (plain.startsWith("Myelin:") || plain.startsWith("Myelin is "));
    }

    private static int normalizeStep(int value) {
        return Integer.highestOneBit(Math.max(1, Math.min(128, value)));
    }

    // Keep the loaded settings in memory; rendering does not reread the file.
    private static final MyelinClientConfig INSTANCE = load();

    public static MyelinClientConfig getInstance() {
        return INSTANCE;
    }

    private static MyelinClientConfig load() {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path configFile = configDir.resolve(FILE_NAME);
            if (!Files.exists(configFile)) {
                MyelinClientConfig defaults = new MyelinClientConfig();
                defaults.saveInternal();
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(configFile)) {
                MyelinClientConfig loaded = GSON.fromJson(reader, MyelinClientConfig.class);
                if (loaded == null) {
                    loaded = new MyelinClientConfig();
                }
                return loaded;
            }
        } catch (Exception e) {
            return new MyelinClientConfig();
        }
    }

    public void save() {
        saveInternal();
    }

    private synchronized void saveInternal() {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            Path configFile = configDir.resolve(FILE_NAME);
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            // Keep the in-memory settings if the file cannot be saved.
        }
    }
}
