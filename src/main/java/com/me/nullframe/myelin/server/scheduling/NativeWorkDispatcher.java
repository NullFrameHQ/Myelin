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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/** Feeds priority queues to persistent native workers without interrupting running JNI calls. */
public final class NativeWorkDispatcher extends AbstractExecutorService {
    public enum Priority { DEMAND, VISIBLE, PREFETCH, VEGETATION }

    public record Statistics(int workers, int active, int demandQueued, int visibleQueued,
                      int prefetchQueued, int vegetationQueued, String phase,
                      long completed, long failed, long queueWaitSamples,
                      long queueWaitNanos, long queueWaitP50Nanos,
                      long queueWaitP95Nanos, long queueWaitP99Nanos) { }

    private static final Comparator<Work<?>> ORDER = Comparator
            .<Work<?>, Integer>comparing(work -> work.priority.ordinal())
            .thenComparingLong(work -> work.sequence);
    private final Object monitor = new Object();
    private final PriorityQueue<Work<?>> normal = new PriorityQueue<>(ORDER);
    private final PriorityQueue<Work<?>> exclusive = new PriorityQueue<>(ORDER);
    private final IdentityHashMap<CompletableFuture<?>, Work<?>> queued = new IdentityHashMap<>();
    private final ReentrantLock exclusiveCoordinator = new ReentrantLock();
    private final ThreadLocal<Long> coordinatorPhase = new ThreadLocal<>();
    private final ThreadLocal<Boolean> workerThread = new ThreadLocal<>();
    private final List<Thread> workers;
    private final AtomicLongArray waitHistogram = new AtomicLongArray(64);
    private volatile Runnable wakeup;
    private boolean closing;
    private boolean stopNow;
    private boolean exclusivePending;
    private boolean exclusiveRunning;
    private String phaseLabel = "normal";
    private long sequence;
    private long phaseSequence;
    private long phaseId;
    private long drainThrough;
    private int activeNormal;
    private int activeExclusive;
    private int liveWorkers;
    private long completed;
    private long failed;
    private long waitSamples;
    private long waitNanos;

    public NativeWorkDispatcher(int count, ThreadFactory factory, Runnable wakeup) {
        if (count < 0) throw new IllegalArgumentException("Native worker count cannot be negative");
        Objects.requireNonNull(factory, "factory");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
        workers = new ArrayList<>(count);
        liveWorkers = count;
        for (int i = 0; i < count; i++) {
            Thread thread = Objects.requireNonNull(factory.newThread(this::workerLoop), "worker thread");
            workers.add(thread);
        }
        workers.forEach(Thread::start);
    }

    NativeWorkDispatcher(int count, ThreadFactory factory) {
        this(count, factory, () -> { });
    }

    public void setWakeup(Runnable callback) {
        wakeup = Objects.requireNonNull(callback, "callback");
    }

    public int workerCount() { return workers.size(); }

    int availableSlots() {
        synchronized (monitor) {
            if (closing || exclusivePending || exclusiveRunning) return 0;
            return Math.max(0, workers.size() - activeNormal - normal.size());
        }
    }

    int activeCount() {
        synchronized (monitor) { return activeNormal + activeExclusive; }
    }

    int queuedCount(Priority priority) {
        synchronized (monitor) { return countQueued(priority); }
    }

    public <T> CompletableFuture<T> submit(Priority priority, Callable<T> action) {
        return enqueue(priority, action, null, false);
    }

    public CompletableFuture<Void> submit(Priority priority, Runnable action) {
        Objects.requireNonNull(action, "action");
        return enqueue(priority, () -> { action.run(); return null; }, action, false);
    }

    /** Returns null when worker capacity is already reserved; the planner keeps the work for later. */
    <T> CompletableFuture<T> trySubmitSpeculative(Priority priority, Callable<T> action) {
        if (priority == Priority.DEMAND) throw new IllegalArgumentException("Demand must use submit");
        return enqueue(priority, action, null, true);
    }

    CompletableFuture<Void> trySubmitSpeculative(Priority priority, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (priority == Priority.DEMAND) throw new IllegalArgumentException("Demand must use submit");
        return enqueue(priority, () -> { action.run(); return null; }, action, true);
    }

    @Override public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        enqueue(Priority.DEMAND, () -> { command.run(); return null; }, command, false);
    }

    /** Changes queued priority without interrupting a running native call. */
    boolean promote(CompletableFuture<?> future, Priority priority) {
        return updatePriority(future, priority, true);
    }

    /** Applies the latest window's priority while a task is still queued. */
    boolean reprioritize(CompletableFuture<?> future, Priority priority) {
        return updatePriority(future, priority, false);
    }

    private boolean updatePriority(CompletableFuture<?> future, Priority priority, boolean promotionOnly) {
        Objects.requireNonNull(priority, "priority");
        synchronized (monitor) {
            Work<?> work = queued.get(future);
            if (work == null || priority == work.priority
                    || promotionOnly && priority.ordinal() >= work.priority.ordinal()) return false;
            PriorityQueue<Work<?>> queue = work.phase == 0 ? normal : exclusive;
            if (!queue.remove(work)) return false;
            work.priority = priority;
            queue.add(work);
            monitor.notifyAll();
            return true;
        }
    }

    /** Cancels only work not yet claimed by a worker; running JNI and its result remain untouched. */
    boolean cancelQueued(CompletableFuture<?> future) {
        Work<?> work;
        synchronized (monitor) {
            work = queued.remove(future);
            if (work == null) return false;
            (work.phase == 0 ? normal : exclusive).remove(work);
            monitor.notifyAll();
        }
        cancelOriginal(work);
        work.result.cancel(false);
        signalPlanner();
        return true;
    }

    private <T> CompletableFuture<T> enqueue(Priority priority, Callable<T> action,
                                             Runnable original, boolean speculative) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(action, "action");
        Long context = coordinatorPhase.get();
        synchronized (monitor) {
            boolean phaseSubmission = context != null && context == phaseId && exclusiveRunning;
            if (stopNow || (closing && !phaseSubmission)) throw rejected();
            if (workers.isEmpty()) {
                if (speculative) return null;
                throw new RejectedExecutionException("Native workers are disabled");
            }
            if (speculative && (exclusivePending || exclusiveRunning
                    || activeNormal + normal.size() >= workers.size())) return null;
            Work<T> work = new Work<>(priority, ++sequence, phaseSubmission ? phaseId : 0,
                    action, original);
            queued.put(work.result, work);
            (phaseSubmission ? exclusive : normal).add(work);
            work.result.whenComplete((ignored, error) -> {
                if (work.result.isCancelled()) forgetCancelled(work);
            });
            monitor.notifyAll();
            return work.result;
        }
    }

    private void forgetCancelled(Work<?> work) {
        boolean removed;
        synchronized (monitor) {
            removed = queued.remove(work.result, work);
            if (removed) {
                (work.phase == 0 ? normal : exclusive).remove(work);
                monitor.notifyAll();
            }
        }
        if (removed) {
            cancelOriginal(work);
            signalPlanner();
        }
    }

    /**
     * Waits for admitted ordinary work, then runs an exclusive coordinator outside the workers.
     * Tasks submitted by that coordinator belong to its phase; new ordinary work waits.
     * Wait for task futures rather than signalling completion from inside their task bodies.
     */
    public void runExclusive(String label, Runnable coordinator) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(coordinator, "coordinator");
        if (Boolean.TRUE.equals(workerThread.get()) || coordinatorPhase.get() != null)
            throw new IllegalStateException("Exclusive phase requires an external, non-nested coordinator");
        boolean interrupted = false;
        exclusiveCoordinator.lock();
        try {
            synchronized (monitor) {
                if (closing) throw rejected();
                exclusivePending = true;
                drainThrough = sequence;
                phaseId = ++phaseSequence;
                phaseLabel = "draining for " + label;
                monitor.notifyAll();
                while (activeNormal != 0 || hasDrainingWork()) {
                    try { monitor.wait(); }
                    catch (InterruptedException error) { interrupted = true; }
                    if (stopNow) {
                        exclusivePending = false;
                        phaseLabel = "normal";
                        monitor.notifyAll();
                        throw rejected();
                    }
                }
                if (stopNow) {
                    exclusivePending = false;
                    phaseLabel = "normal";
                    monitor.notifyAll();
                    throw rejected();
                }
                exclusivePending = false;
                exclusiveRunning = true;
                phaseLabel = label;
                coordinatorPhase.set(phaseId);
                monitor.notifyAll();
            }
            try {
                coordinator.run();
            } finally {
                coordinatorPhase.remove();
                synchronized (monitor) {
                    while (activeExclusive != 0 || !exclusive.isEmpty()) {
                        try { monitor.wait(); }
                        catch (InterruptedException error) { interrupted = true; }
                    }
                    exclusiveRunning = false;
                    phaseLabel = "normal";
                    monitor.notifyAll();
                }
                signalPlanner();
            }
        } finally {
            exclusiveCoordinator.unlock();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean hasDrainingWork() {
        for (Work<?> work : normal) if (work.sequence <= drainThrough) return true;
        return false;
    }

    private Work<?> pollEligible() {
        if (exclusiveRunning) return exclusive.poll();
        if (!exclusivePending) return normal.poll();
        Work<?> best = null;
        for (Work<?> work : normal) {
            if (work.sequence <= drainThrough && (best == null || ORDER.compare(work, best) < 0)) best = work;
        }
        if (best != null) normal.remove(best);
        return best;
    }

    private void workerLoop() {
        workerThread.set(true);
        try {
            while (true) {
                Work<?> work;
                synchronized (monitor) {
                    while (true) {
                        if (stopNow || (closing && normal.isEmpty() && exclusive.isEmpty()
                                && !exclusivePending && !exclusiveRunning)) return;
                        work = pollEligible();
                        if (work != null) {
                            queued.remove(work.result);
                            if (work.result.isCancelled()) {
                                cancelOriginal(work);
                                monitor.notifyAll();
                                continue;
                            }
                            if (work.phase == 0) activeNormal++;
                            else activeExclusive++;
                            long elapsed = Math.max(0, System.nanoTime() - work.enqueuedNanos);
                            waitNanos += elapsed;
                            waitSamples++;
                            waitHistogram.incrementAndGet(bucket(elapsed));
                            break;
                        }
                        try { monitor.wait(); }
                        catch (InterruptedException ignored) { /* Native lifecycle owns shutdown. */ }
                    }
                }
                boolean success = work.run();
                synchronized (monitor) {
                    if (work.phase == 0) activeNormal--;
                    else activeExclusive--;
                    completed++;
                    if (!success) failed++;
                    monitor.notifyAll();
                }
                signalPlanner();
            }
        } finally {
            workerThread.remove();
            synchronized (monitor) {
                liveWorkers--;
                monitor.notifyAll();
            }
            signalPlanner();
        }
    }

    private void signalPlanner() {
        try { wakeup.run(); }
        catch (Throwable ignored) { /* An observer must not kill a TLS-owning native worker. */ }
    }

    public Statistics statistics() {
        synchronized (monitor) {
            return new Statistics(workers.size(), activeNormal + activeExclusive,
                    countQueued(Priority.DEMAND), countQueued(Priority.VISIBLE),
                    countQueued(Priority.PREFETCH), countQueued(Priority.VEGETATION), phaseLabel,
                    completed, failed, waitSamples, waitNanos,
                    percentile(0.50), percentile(0.95), percentile(0.99));
        }
    }

    private int countQueued(Priority priority) {
        int count = 0;
        for (Work<?> work : normal) if (work.priority == priority) count++;
        for (Work<?> work : exclusive) if (work.priority == priority) count++;
        return count;
    }

    private static int bucket(long nanos) {
        return nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1);
    }

    private long percentile(double fraction) {
        if (waitSamples == 0) return 0;
        long target = (long) Math.ceil(waitSamples * fraction);
        long seen = 0;
        for (int i = 0; i < 64; i++) {
            seen += waitHistogram.get(i);
            if (seen >= target) return i == 63 ? Long.MAX_VALUE : 1L << i;
        }
        return 0;
    }

    @Override public void shutdown() {
        synchronized (monitor) {
            closing = true;
            monitor.notifyAll();
        }
        signalPlanner();
    }

    /** Cancels queued work and lets active native calls finish before their workers exit. */
    @Override public List<Runnable> shutdownNow() {
        List<Work<?>> abandoned;
        synchronized (monitor) {
            closing = true;
            stopNow = true;
            abandoned = new ArrayList<>(normal);
            abandoned.addAll(exclusive);
            normal.clear();
            exclusive.clear();
            queued.clear();
            monitor.notifyAll();
        }
        List<Runnable> commands = new ArrayList<>(abandoned.size());
        for (Work<?> work : abandoned) {
            cancelOriginal(work);
            work.result.completeExceptionally(new RejectedExecutionException("Native dispatcher stopped"));
            commands.add(work.original != null ? work.original : () -> { });
        }
        signalPlanner();
        return commands;
    }

    private static void cancelOriginal(Work<?> work) {
        if (work.original instanceof Future<?> future) future.cancel(false);
    }

    @Override public boolean isShutdown() {
        synchronized (monitor) { return closing; }
    }

    @Override public boolean isTerminated() {
        synchronized (monitor) { return closing && liveWorkers == 0; }
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        synchronized (monitor) {
            while (!(closing && liveWorkers == 0)) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                remaining = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private static RejectedExecutionException rejected() {
        return new RejectedExecutionException("Native dispatcher is closed");
    }

    private static final class Work<T> {
        private Priority priority;
        private final long sequence;
        private final long phase;
        private final long enqueuedNanos = System.nanoTime();
        private final Callable<T> action;
        private final Runnable original;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Work(Priority priority, long sequence, long phase, Callable<T> action, Runnable original) {
            this.priority = priority;
            this.sequence = sequence;
            this.phase = phase;
            this.action = action;
            this.original = original;
        }

        private boolean run() {
            try { result.complete(action.call()); return true; }
            catch (Throwable error) { result.completeExceptionally(error); return false; }
        }
    }
}
