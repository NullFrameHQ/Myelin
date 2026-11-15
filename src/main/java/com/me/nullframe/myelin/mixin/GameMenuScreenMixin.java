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

package com.me.nullframe.myelin.mixin;

import com.me.nullframe.myelin.client.gui.MyelinSettingsScreen;
import com.me.nullframe.myelin.client.MyelinClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void myelin$addSettingsButton(CallbackInfo ci) {
        if (!MyelinClient.isAvailable()) return;
        // Add "Myelin Settings" to the bottom-left corner to avoid overlapping vanilla buttons
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§bMyelin Settings"),
                button -> this.client.setScreen(MyelinSettingsScreen.createScreen(this))
        ).dimensions(2, this.height - 24, 100, 20).build());
    }
}
