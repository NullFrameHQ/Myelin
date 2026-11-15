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

import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * A slider that snaps to the supplied sample levels.
 * Each level gets an equal share of the bar, even when the values are unevenly spaced.
 */
public class MyelinStepSlider extends SliderWidget {
    private final int[] steps;
    private final Function<Integer, Text> valueText;
    private final Consumer<Integer> onChange;

    public MyelinStepSlider(int x, int y, int width, int[] steps, int currentStep,
                            Function<Integer, Text> valueText,
                            Consumer<Integer> onChange) {
        super(x, y, width, 20, valueText.apply(nearestStep(steps, currentStep)),
                ratioFor(nearestStep(steps, currentStep), steps));
        this.steps = steps;
        this.valueText = valueText;
        this.onChange = onChange;
    }

    /** Step currently selected by the slider's ratio. */
    private int stepAtCurrentRatio() {
        if (this.steps.length <= 1) {
            return this.steps[0];
        }
        int index = (int) Math.round(this.value * (this.steps.length - 1));
        index = Math.max(0, Math.min(this.steps.length - 1, index));
        return this.steps[index];
    }

    /** Updates the label for the current slider position. */
    @Override
    protected void updateMessage() {
        this.setMessage(this.valueText.apply(stepAtCurrentRatio()));
    }

    /** Snaps to the selected level and notifies the caller when the value changes. */
    @Override
    protected void applyValue() {
        int step = stepAtCurrentRatio();
        // Snap the handle onto the selected step's position.
        this.value = ratioFor(step, this.steps);
        this.updateMessage();
        this.onChange.accept(step);
    }

    /** Programmatically move the slider to {@code step} (nearest available). */
    public void setStep(int step) {
        this.value = ratioFor(step, this.steps);
        this.updateMessage();
    }

    public int getStep() {
        return stepAtCurrentRatio();
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        // Hide the label while drawing the bar, then restore it for narration.
        Text message = this.getMessage();
        boolean hadMessage = !message.getString().isEmpty();
        if (hadMessage) {
            this.setMessage(Text.empty());
        }
        super.renderWidget(context, mouseX, mouseY, delta);
        if (hadMessage) {
            this.setMessage(message);
        }
        // Dim the bar when the control is disabled.
        if (!this.active) {
            context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(),
                    this.getY() + this.getHeight(), 0x55000000);
        }
    }

    private static int nearestStep(int[] steps, int step) {
        int best = steps[0];
        int bestDist = Integer.MAX_VALUE;
        for (int s : steps) {
            int dist = Math.abs(s - step);
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    private static double ratioFor(int step, int[] steps) {
        if (steps.length <= 1) {
            return 0.0;
        }
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == step) {
                return (double) i / (double) (steps.length - 1);
            }
        }
        // Not a listed step — snap to the nearest one.
        return ratioFor(nearestStep(steps, step), steps);
    }
}
