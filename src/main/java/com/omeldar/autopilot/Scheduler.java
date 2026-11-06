package com.omeldar.autopilot;

import com.omeldar.autopilot.guard.Watchdog;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the one active {@link Task}, and is the only tick listener in the mod.
 *
 * <p>When nothing is running this is a null check and a return, which is the whole answer to
 * "what does this cost me when I am not using it": one reference comparison per tick, no
 * allocation, no rendering, no polling thread. When something is running it is a handful of
 * state reads a tick, still no allocation in the common path.
 *
 * <p>It also owns stopping. Tasks never stop themselves half way: they finish, or the
 * watchdog ends them, and either way the keys are released here, exactly once.
 */
public final class Scheduler {
    private static Task active;
    private static Watchdog watchdog;
    private static long tick;
    private static long startedAtTick;
    private static int sinceStatus;

    /** Why the last task ended, kept so the bridge can answer after the fact. */
    private static final AtomicReference<String> lastOutcome = new AtomicReference<>("idle");
    private static final AtomicReference<String> lastTaskName = new AtomicReference<>("");

    private Scheduler() {}

    public static synchronized boolean busy() {
        return active != null;
    }

    public static synchronized String activeName() {
        return active == null ? null : active.name();
    }

    public static String lastOutcome() {
        return lastOutcome.get();
    }

    public static String lastTaskName() {
        return lastTaskName.get();
    }

    public static synchronized String status() {
        if (active == null) return "idle";
        return active.status();
    }

    public static long now() {
        return tick;
    }

    /**
     * Take over. Returns null when the task started, or the reason it did not. Must run on
     * the client thread; the bridge hands work over through {@link ClientQueue}.
     */
    public static synchronized String begin(MinecraftClient mc, Task task) {
        if (active != null) {
            return "busy: " + active.name() + " is already running. Stop it first.";
        }
        if (mc.player == null || mc.world == null) {
            return "not in a world";
        }
        String refusal = task.start(mc);
        if (refusal != null) return refusal;

        active = task;
        watchdog = new Watchdog(mc, tick);
        startedAtTick = tick;
        sinceStatus = 0;
        lastTaskName.set(task.name());
        lastOutcome.set("running");
        Chat.good("started " + task.name());
        return null;
    }

    /** Stop whatever is running. Safe to call when nothing is. */
    public static synchronized void end(MinecraftClient mc, String reason) {
        if (active == null) return;
        Task finished = active;
        active = null;
        watchdog = null;
        Keys.releaseAll(mc);
        Watchdog.clearMention();
        lastOutcome.set(reason);
        try {
            finished.stop(mc, reason);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("task {} threw while stopping", finished.name(), e);
        }
        if (reason.startsWith("finished")) {
            Chat.good(finished.name() + ": " + reason);
        } else {
            Chat.warn(finished.name() + " stopped: " + reason);
        }
        Chat.status("autopilot idle");
    }

    /** The single client tick hook. */
    public static void onTick(MinecraftClient mc) {
        tick++;
        Task task;
        Watchdog guard;
        synchronized (Scheduler.class) {
            task = active;
            guard = watchdog;
        }
        if (task == null) return;

        if (mc.player == null || mc.world == null) {
            end(mc, "left the world");
            return;
        }

        if (guard != null) {
            String trip = guard.check(mc, tick);
            if (trip != null) {
                end(mc, trip);
                return;
            }
        }

        boolean more;
        try {
            more = task.tick(mc);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("task {} threw", task.name(), e);
            end(mc, "the task threw: " + e);
            return;
        }
        if (!more) {
            end(mc, "finished");
            return;
        }

        Config cfg = Config.get();
        if (cfg.showStatus && ++sinceStatus >= Math.max(5, cfg.statusEveryTicks)) {
            sinceStatus = 0;
            Chat.status(task.status());
        }
    }

    /** Seconds the current task has been running, for status lines. */
    public static synchronized int secondsRunning() {
        if (active == null) return 0;
        return (int) ((tick - startedAtTick) / 20);
    }
}
