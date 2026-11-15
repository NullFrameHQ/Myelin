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

import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.ControllerWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Rebind a Minecraft shortcut inline, keeping changes pending until YACL saves. */
public final class MyelinKeybindController implements Controller<InputUtil.Key> {
    private final Option<InputUtil.Key> option;
    private boolean listening;

    public MyelinKeybindController(Option<InputUtil.Key> option) { this.option = option; }
    @Override public Option<InputUtil.Key> option() { return option; }
    @Override public Text formatValue() { return listening ? Text.literal("> Press a key <") : option.pendingValue().getLocalizedText(); }
    public boolean isListening() { return listening; }

    public boolean captureKey(int keyCode, int scanCode) {
        option.requestSet(keyCode == GLFW.GLFW_KEY_ESCAPE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode));
        listening = false;
        return true;
    }

    public boolean captureMouse(int button) {
        option.requestSet(InputUtil.Type.MOUSE.createFromCode(button));
        listening = false;
        return true;
    }

    @Override public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dimensions) {
        return new ControllerWidget<MyelinKeybindController>(this, screen, dimensions) {
            @Override protected int getHoveredControlWidth() { return getUnhoveredControlWidth(); }

            @Override public boolean onMouseClicked(double mouseX, double mouseY, int button) {
                if (!isAvailable() || !isMouseOver(mouseX, mouseY) || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
                listening = true;
                playDownSound();
                return true;
            }

            @Override public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
                if (!isAvailable() || !isFocused()) return false;
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                    listening = true;
                    playDownSound();
                    return true;
                }
                return false;
            }
        };
    }
}
