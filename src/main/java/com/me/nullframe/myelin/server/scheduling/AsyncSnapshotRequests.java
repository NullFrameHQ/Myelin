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
package com.me.nullframe.myelin.server.scheduling;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Shares pending snapshot jobs between callers without blocking them.
 * Cancelling a caller's future does not cancel the shared job.
 */
final class AsyncSnapshotRequests<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> jobs = new ConcurrentHashMap<>();
    private final Executor executor;

    AsyncSnapshotRequests(Executor executor) {
        this.executor = executor;
    }

    boolean isPending(K key) {
        return jobs.containsKey(key);
    }

    CompletableFuture<V> request(K key, Supplier<CompletableFuture<V>> task, Runnable onScheduled) {
        CompletableFuture<V> shared = new CompletableFuture<>();
        CompletableFuture<V> existing = jobs.putIfAbsent(key, shared);
        if (existing != null) return existing.thenApply(value -> value);

        shared.whenComplete((value, error) -> jobs.remove(key, shared));
        try {
            onScheduled.run();
            executor.execute(() -> {
                try {
                    CompletableFuture<V> work = Objects.requireNonNull(task.get(), "Missing snapshot future");
                    work.whenComplete((value, error) -> {
                        if (error != null) shared.completeExceptionally(error);
                        else if (value == null) shared.completeExceptionally(
                                new IllegalStateException("Generation returned no snapshot"));
                        else shared.complete(value);
                    });
                } catch (Throwable error) {
                    shared.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            shared.completeExceptionally(error);
        }
        return shared.thenApply(value -> value);
    }
}
