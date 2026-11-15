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

package com.me.nullframe.myelin.client.render;

import com.me.nullframe.myelin.client.MyelinClient;
import com.me.nullframe.myelin.config.MyelinClientConfig;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Shows Flight Mode, sampling, and the two chunk receive rates in the HUD panel. */
public class MyelinHudRenderer implements HudRenderCallback {

    // Panel colors (ARGB).
    private static final int COLOR_TITLE       = 0xFFD0D0D0;  // white/grey
    private static final int COLOR_SEPARATOR   = 0xFF606060;  // dark grey
    private static final int COLOR_MODE_FLIGHT = 0xFFFF5555;  // red
    private static final int COLOR_MODE_REAL   = 0xFF55FF55;  // green
    private static final int COLOR_SAMPLING    = 0xFFFFFF55;  // yellow
    private static final int COLOR_SPEED_IDLE  = 0xFF888888;  // grey     (0-10)
    private static final int COLOR_SPEED_LOW   = 0xFFAA55FF;  // purple   (10-100)
    private static final int COLOR_SPEED_MED   = 0xFF55CCFF;  // cyan     (100-500)
    private static final int COLOR_SPEED_HIGH  = 0xFFFF8833;  // orange   (500-1000)
    private static final int COLOR_SPEED_MAX   = 0xFFFF3333;  // red      (1000+)

    // Panel position and spacing.
    private static final int PANEL_BG       = 0xC0101018;  // dark semi-transparent
    private static final float HUD_SCALE    = 0.85f;
    private static final int PANEL_X        = 10;
    private static final int PANEL_Y        = 10;
    private static final int PANEL_PAD      = 8;
    private static final int LINE_HEIGHT    = 12;
    private static final int SEPARATOR_PAD  = 2;

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!MyelinClient.isAvailable()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (!MyelinClientConfig.getInstance().showHudIndicator) return;

        boolean isSurfaceMode = MyelinClient.isSurfaceModeEnabled();
        int step = MyelinClientConfig.getInstance().samplingStep;
        long previewSpeed = MyelinClient.getChunksPerSecond();
        long worldSpeed = MyelinClient.getWorldChunksPerSecond();
        String worldSpeedText = receivedText("Minecraft World Chunks received: ", worldSpeed);
        String previewSpeedText = receivedText("Cached Preview Terrain received: ", previewSpeed);
        String samplingText = "Sampling: 1:" + step + " (" + getQualityLabel(step) + ")";

        int panelW = Math.max(175, Math.max(client.textRenderer.getWidth(samplingText),
                Math.max(client.textRenderer.getWidth(worldSpeedText), client.textRenderer.getWidth(previewSpeedText))) + PANEL_PAD * 2);
        int panelH = PANEL_PAD * 2 + LINE_HEIGHT * 5 + SEPARATOR_PAD * 2 + 1;

        ctx.getMatrices().push();
        ctx.getMatrices().translate(PANEL_X, PANEL_Y, 0);
        ctx.getMatrices().scale(HUD_SCALE, HUD_SCALE, 1);
        ctx.getMatrices().translate(-PANEL_X, -PANEL_Y, 0);
        try {
            ctx.fill(PANEL_X, PANEL_Y, PANEL_X + panelW, PANEL_Y + panelH, PANEL_BG);

            int x = PANEL_X + PANEL_PAD;
            int y = PANEL_Y + PANEL_PAD;

            ctx.drawTextWithShadow(client.textRenderer, "Myelin", x, y, COLOR_TITLE);
            y += LINE_HEIGHT;

            y += SEPARATOR_PAD;
            ctx.fill(x, y, x + panelW - PANEL_PAD * 2, y + 1, COLOR_SEPARATOR);
            y += 1 + SEPARATOR_PAD;

            if (isSurfaceMode) {
                ctx.drawTextWithShadow(client.textRenderer, "\u2708 Mode: Flight", x, y, COLOR_MODE_FLIGHT);
            } else {
                ctx.drawTextWithShadow(client.textRenderer, "\u25A0 Mode: Real", x, y, COLOR_MODE_REAL);
            }
            y += LINE_HEIGHT;

            ctx.drawTextWithShadow(client.textRenderer,
                    samplingText,
                    x, y, COLOR_SAMPLING);
            y += LINE_HEIGHT;

            ctx.drawTextWithShadow(client.textRenderer, worldSpeedText, x, y, getSpeedColor(worldSpeed));
            y += LINE_HEIGHT;
            ctx.drawTextWithShadow(client.textRenderer, previewSpeedText, x, y, getSpeedColor(previewSpeed));
        } finally {
            ctx.getMatrices().pop();
        }
    }

    private static String receivedText(String label, long speed) {
        return label + (speed <= 0 ? "idle" : String.format("%,d ch/s", speed));
    }

    private static int getSpeedColor(long sp) {
        if (sp <= 0)    return COLOR_SPEED_IDLE;
        if (sp < 10)    return COLOR_SPEED_IDLE;
        if (sp < 100)   return COLOR_SPEED_LOW;
        if (sp < 500)   return COLOR_SPEED_MED;
        if (sp < 1000)  return COLOR_SPEED_HIGH;
        return COLOR_SPEED_MAX;
    }

    private static String getQualityLabel(int step) {
        return switch (step) {
            case 1  -> "Accurate";
            case 2  -> "Detailed";
            case 4  -> "Standard";
            case 8  -> "Balanced";
            case 16 -> "Warp";
            case 32 -> "Turbo";
            case 64 -> "Hyper";
            case 128 -> "Uber";
            default -> "Custom";
        };
    }
}
