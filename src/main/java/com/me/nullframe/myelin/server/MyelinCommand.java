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

package com.me.nullframe.myelin.server;

import com.me.nullframe.myelin.MyelinMod;

import com.nullframe.core.StepTimings;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.minecraft.command.argument.EntityArgumentType.getPlayer;
import static net.minecraft.command.argument.EntityArgumentType.player;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Server-side surface controls, generation benchmarks, and stage timing diagnostics.
 * Benchmarks run only when explicitly requested through /myelin bench.
 */
public class MyelinCommand {
    private static final Logger LOGGER = LogManager.getLogger(MyelinMod.MOD_ID);

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = literal("myelin");
            root.then(literal("surface")
                    .then(argument("player", player())
                            .then(literal("on").executes(ctx -> setSurfaceMode(ctx, true)))
                            .then(literal("off").executes(ctx -> setSurfaceMode(ctx, false)))));
            root.then(literal("bench")
                    .then(argument("chunks", integer(1))
                            .then(argument("step", integer(1, 128))
                                    .executes(ctx -> runBench(ctx, false, 0))
                                    .then(literal("threads")
                                            .then(argument("threads", integer(1, 20))
                                                    .executes(ctx -> runBench(ctx, false, getInteger(ctx, "threads")))
                                                    .then(literal("region")
                                                            .executes(ctx -> runBench(ctx, true, getInteger(ctx, "threads"))))))
                                    .then(literal("region")
                                            .executes(ctx -> runBench(ctx, true, 0))
                                            .then(literal("threads")
                                                    .then(argument("threads", integer(1, 20))
                                                            .executes(ctx -> runBench(ctx, true, getInteger(ctx, "threads")))))))));
            root.then(literal("steps")
                    .executes(MyelinCommand::runSteps)
                    .then(literal("reset").executes(MyelinCommand::runStepsReset)));
            root.then(literal("cache")
                    .executes(ctx -> runCacheDiagnostics(ctx, false))
                    .then(literal("reset").executes(ctx -> runCacheDiagnostics(ctx, true))));
            dispatcher.register(root);
        });
    }

    private static int runCacheDiagnostics(CommandContext<ServerCommandSource> ctx, boolean reset) {
        if (!checkEnabled(ctx.getSource())) return 0;
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("Myelin: chunk manager not available"));
            return 0;
        }
        if (reset) {
            manager.resetCacheDiagnostics();
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "Myelin: terrain cache counters reset. Fly into fresh terrain, then run /myelin cache."), false);
        } else {
            String summary = manager.cacheDiagnosticsSummary();
            LOGGER.info(summary);
            ctx.getSource().sendFeedback(() -> Text.literal(summary), false);
        }
        return 1;
    }

    private static int runSteps(CommandContext<ServerCommandSource> ctx) {
        if (!checkEnabled(ctx.getSource())) return 0;
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("Myelin: chunk manager not available"));
            return 0;
        }
        String summary = manager.stepTimingsSummary();
        LOGGER.info(summary);
        ctx.getSource().sendFeedback(() -> Text.literal(summary), false);
        return 1;
    }

    private static int runStepsReset(CommandContext<ServerCommandSource> ctx) {
        if (!checkEnabled(ctx.getSource())) return 0;
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("Myelin: chunk manager not available"));
            return 0;
        }
        manager.resetStepTimings();
        ctx.getSource().sendFeedback(() -> Text.literal("Myelin: step timings reset — move/fly then run /myelin steps"), false);
        return 1;
    }

    private static int runBench(CommandContext<ServerCommandSource> ctx, boolean region, int requestedThreads)
            throws CommandSyntaxException {
        if (!checkEnabled(ctx.getSource())) return 0;
        // Works from in-game AND the server console (no player → overworld).
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        ServerWorld world = player != null ? (ServerWorld) player.getWorld()
                : ctx.getSource().getServer().getOverworld();
        int chunks = getInteger(ctx, "chunks");
        int step = getInteger(ctx, "step");
        if ((step & (step - 1)) != 0) {
            ctx.getSource().sendError(Text.literal("Myelin: sampling must be 1, 2, 4, 8, 16, 32, 64 or 128."));
            return 0;
        }
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("Myelin: chunk manager not available"));
            return 0;
        }
        int availableThreads = manager.getNativeWorkerCount();
        if (requestedThreads > availableThreads) {
            ctx.getSource().sendError(Text.literal("Myelin: requested " + requestedThreads
                    + " workers, but the pool has " + availableThreads
                    + ". Set workerThreads in myelin.json and restart to increase it."));
            return 0;
        }
        int threads = Math.min(chunks, requestedThreads == 0 ? availableThreads : requestedThreads);
        manager.benchmarkGeneration(world, player, chunks, step, false, region, requestedThreads);
        ctx.getSource().sendFeedback(() -> Text.literal("Myelin: " + (region ? "REGION " : "")
                + "bench started — " + chunks + " chunk snapshots @ 1:" + step + " with " + threads
                + " workers (result appears shortly)"), false);
        return 1;
    }

    private static int setSurfaceMode(CommandContext<ServerCommandSource> ctx, boolean desired) throws CommandSyntaxException {
        if (!checkEnabled(ctx.getSource())) return 0;
        ServerPlayerEntity player = getPlayer(ctx, "player");
        MyelinChunkManager manager = MyelinMod.getChunkManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("Myelin: chunk manager not available"));
            return 0;
        }

        boolean current = manager.isPlayerInSurfaceMode(player.getUuid());
        String name = player.getGameProfile().getName();
        if (current == desired) {
            ctx.getSource().sendFeedback(() -> Text.literal("Myelin: " + name
                    + " is already " + (desired ? "in surface mode" : "not in surface mode")), false);
            return 1;
        }

        // Toggle Flight Mode through the same handler as the client shortcut.
        manager.toggleSurfaceMode(player);
        ctx.getSource().sendFeedback(() -> Text.literal("Myelin: surface mode for "
                + name + " -> " + (desired ? "ON" : "OFF")), false);
        return 1;
    }

    private static boolean checkEnabled(ServerCommandSource source) {
        if (MyelinMod.isEnabled()) return true;
        source.sendError(Text.literal("Myelin is disabled in myelin.json. Set enabled=true and restart to activate."));
        return false;
    }
}
