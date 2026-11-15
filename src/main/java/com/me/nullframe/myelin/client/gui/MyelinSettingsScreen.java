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

package com.me.nullframe.myelin.client.gui;

import com.me.nullframe.myelin.client.MyelinClient;
import com.me.nullframe.myelin.client.render.CachedTerrainPreviewRenderer;
import com.me.nullframe.myelin.config.MyelinClientConfig;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.util.List;

/** A single scrolling settings page; descriptions live beside their options. */
public final class MyelinSettingsScreen {
    private static final List<Integer> TERRAIN_STEPS = List.of(1, 2, 4, 8, 16, 32, 64, 128);
    private static final List<Integer> BIOME_STEPS = List.of(0, 1, 2, 4, 8, 16, 32);

    private MyelinSettingsScreen() {}

    public static Screen createScreen(Screen parent) {
        MyelinClientConfig config = MyelinClientConfig.getInstance();
        MinecraftClient client = MinecraftClient.getInstance();
        MyelinClientConfig defaults = new MyelinClientConfig();

        Option<Integer> terrain = Option.<Integer>createBuilder()
                .name(Text.literal("Terrain Sample Level"))
                .description(description("Choose how closely terrain is sampled. 1:1 is full detail; larger steps " +
                        "reduce detail. I'd stick with 1:4 or 1:8. In my tests, coarser levels looked noticeably worse " +
                        "without much of an in-game speed improvement."))
                .binding(TERRAIN_STEPS.indexOf(defaults.samplingStep),
                        () -> indexOf(TERRAIN_STEPS, config.effectiveSamplingStep()),
                        index -> {
                            config.samplingStep = TERRAIN_STEPS.get(index);
                            config.terrainDownsamplingEnabled = true;
                        })
                .controller(option -> IntegerSliderControllerBuilder.create(option)
                        .range(0, TERRAIN_STEPS.size() - 1).step(1)
                        .formatValue(index -> Text.literal("1:" + TERRAIN_STEPS.get(index))))
                .build();

        Option<Integer> correction = Option.<Integer>createBuilder()
                .name(Text.literal("Detail Correction"))
                .description(description("Adds Value Noise to surface chunks at 1:16 and coarser. Auto enables it at supported levels;" +
                        " On respects the same minimum; Off disables it."))
                .binding(0, () -> normalizedMode(config.detailCorrectionMode), value -> config.detailCorrectionMode = value)
                .available(TERRAIN_STEPS.get(terrain.pendingValue()) >= 16)
                .controller(option -> CyclingListControllerBuilder.create(option)
                        .values(0, 1, 2).formatValue(MyelinSettingsScreen::modeLabel))
                .build();
        terrain.addEventListener((option, event) -> {
            int selectedMode = correction.pendingValue();
            correction.setAvailable(TERRAIN_STEPS.get(option.pendingValue()) >= 16);
            // YACL resets disabled options; retain the choice while finer sampling ignores it.
            correction.requestSet(selectedMode);
        });

        Option<Integer> biomes = Option.<Integer>createBuilder()
                .name(Text.literal("Biome Sample Level"))
                .description(description("Choose biome sampling separately when terrain sampling is 1:16 or coarser. " +
                        "Finer biome sampling gives more detailed biome boundaries, but adds generation work. " +
                        "It won't add detail to terrain heights. Auto follows the terrain sampling level."))
                .binding(BIOME_STEPS.indexOf(defaults.biomeStep),
                        () -> indexOf(BIOME_STEPS, config.effectiveBiomeStep()),
                        index -> {
                            int step = BIOME_STEPS.get(index);
                            config.biomeDownsamplingEnabled = step != 0;
                            if (step != 0) config.biomeStep = step;
                        })
                .controller(option -> IntegerSliderControllerBuilder.create(option)
                        .range(0, BIOME_STEPS.size() - 1).step(1)
                        .formatValue(index -> Text.literal(BIOME_STEPS.get(index) == 0 ? "Auto" : "1:" + BIOME_STEPS.get(index))))
                .build();

        Option<Boolean> flight = Option.<Boolean>createBuilder()
                .name(Text.literal("Flight Mode"))
                .description(description("Use Myelin surface chunks while travelling. Turning this off brings back normal terrain generation. " +
                        "Flight Mode can make chunk generation much faster while using fewer resources."))
                .binding(false, () -> MyelinClient.isSurfaceModeEnabled(), MyelinClient::requestSurfaceMode)
                .available(MyelinClient.canToggleSurfaceMode())
                .controller(BooleanControllerBuilder::create)
                .build();

        MyelinKeybindController[] keyController = new MyelinKeybindController[1];
        KeyBinding flightKey = MyelinClient.surfaceModeKeyBinding();
        Option<InputUtil.Key> keybind = Option.<InputUtil.Key>createBuilder()
                .name(Text.literal("Flight Mode Shortcut"))
                .description(description("Click the key, then press a keyboard key or mouse button. Escape unbinds it. Save applies the new shortcut."))
                .binding(flightKey.getDefaultKey(), () -> KeyBindingHelper.getBoundKeyOf(flightKey),
                        key -> {
                            client.options.setKeyCode(flightKey, key);
                            KeyBinding.updateKeysByCode();
                        })
                .customController(option -> keyController[0] = new MyelinKeybindController(option))
                .build();

        Option<Boolean> hud = Option.<Boolean>createBuilder()
                .name(Text.literal("HUD Indicator Panel"))
                .description(description("Show the Myelin panel with flight mode, sampling level, etc."))
                .binding(defaults.showHudIndicator, () -> config.showHudIndicator, value -> config.showHudIndicator = value)
                .controller(TickBoxControllerBuilder::create)
                .build();

        Option<Boolean> messages = Option.<Boolean>createBuilder()
                .name(Text.literal("Disable HUD Messages"))
                .description(description("Hide Myelin notifications such as sampling changes, region mode, and flight mode. The HUD indicator panel and chat are separate."))
                .binding(false, () -> config.disableHudMessages, value -> config.disableHudMessages = value)
                .controller(TickBoxControllerBuilder::create)
                .build();

        Option<Boolean> cachedTerrain = Option.<Boolean>createBuilder()
                .name(Text.literal("Render Cached Terrain"))
                .description(description("Display terrain already held in Myelin's cache using a separate renderer. This does not choose what gets cached. " +
                        "Cached chunks are streamed from the server and rendered on the client. " +
                        "This can lower your FPS quite a bit. Not optimized yet :)"))
                .binding(false, () -> config.usePreviewRenderer, value -> config.usePreviewRenderer = value)
                .controller(TickBoxControllerBuilder::create)
                .build();

        Option<Integer> region = Option.<Integer>createBuilder()
                .name(Text.literal("Region Path"))
                .description(description("Choose regional terrain generation. Auto uses it through 1:32, On keeps it enabled, and Off uses the per-chunk path. " +
                        "How much it helps depends on your setup. Try both paths and see which works better for you."))
                .binding(defaults.regionPathMode, () -> normalizedMode(config.regionPathMode), value -> config.regionPathMode = value)
                .controller(option -> CyclingListControllerBuilder.create(option)
                        .values(0, 1, 2).formatValue(MyelinSettingsScreen::modeLabel))
                .build();

        boolean[] previousRenderer = {config.usePreviewRenderer};
        YetAnotherConfigLib settings = YetAnotherConfigLib.createBuilder()
                .title(Text.literal("Myelin Settings"))
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Settings"))
                        .group(group("Quality", terrain, correction, biomes))
                        .group(group("Controls", flight, keybind))
                        .group(group("Information", hud, messages))
                        .group(group("Advanced", cachedTerrain, region))
                        .build())
                .save(() -> {
                    config.save();
                    client.options.write();
                    if (previousRenderer[0] != config.usePreviewRenderer) CachedTerrainPreviewRenderer.clearCache();
                    previousRenderer[0] = config.usePreviewRenderer;
                    MyelinClient.sendSavedPreferences();
                })
                .build();

        // Capture keys before the screen handles Escape or navigation shortcuts.
        return new YACLScreen(settings, parent) {
            @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyController[0].isListening()) return keyController[0].captureKey(keyCode, scanCode);
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (keyController[0].isListening()) return keyController[0].captureMouse(button);
                return super.mouseClicked(mouseX, mouseY, button);
            }
        };
    }

    private static OptionGroup group(String name, Option<?>... options) {
        return OptionGroup.createBuilder().name(Text.literal(name)).options(List.of(options)).collapsed(false).build();
    }

    private static OptionDescription description(String text) { return OptionDescription.of(Text.literal(text)); }
    private static int indexOf(List<Integer> steps, int value) { return Math.max(0, steps.indexOf(value)); }
    private static int normalizedMode(int mode) { return mode >= 0 && mode <= 2 ? mode : 0; }
    private static Text modeLabel(int mode) { return Text.literal(mode == 1 ? "On" : mode == 2 ? "Off" : "Auto"); }
}
