package com.omeldar.autopilot.record;

import com.omeldar.autopilot.Chat;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Scheduler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.time.Instant;

/**
 * Watches the player and writes down what they did.
 *
 * <p>It records intent, not outcome: which keys were held, where the mouse was pointing,
 * which hotbar slot was selected. It does not record positions to walk back to, because a
 * farm does not care where you were, it cares that you held right-click while looking at
 * that one block. Recording the keys and replaying the keys means the replay is subject to
 * every rule the original was.
 *
 * <p>Costs nothing when not recording: one boolean check per tick.
 */
public final class Recorder {
    private static boolean recording;
    private static Macro macro;
    private static int t;
    private static int lastKeys;
    private static float lastYaw, lastPitch;
    private static int lastSlot;

    private Recorder() {}

    public static boolean recording() {
        return recording;
    }

    public static String recordingName() {
        return macro == null ? null : macro.name;
    }

    public static int ticks() {
        return t;
    }

    /** Null when recording started, otherwise the reason it did not. */
    public static synchronized String start(MinecraftClient mc, String name, String note) {
        if (recording) return "already recording " + macro.name;
        if (Scheduler.busy()) return "stop " + Scheduler.activeName() + " first";
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "not in a world";

        String clean = MacroStore.sanitise(name);
        if (clean.isEmpty()) return "that name has nothing usable in it";

        macro = new Macro();
        macro.name = clean;
        macro.note = note == null ? "" : note;
        macro.createdAt = Instant.now().toString();
        macro.dimension = mc.world.getRegistryKey().getValue().toString();
        macro.anchor.x = p.getX();
        macro.anchor.y = p.getY();
        macro.anchor.z = p.getZ();
        macro.anchor.yaw = p.getYaw();
        macro.anchor.pitch = p.getPitch();

        t = 0;
        lastKeys = Keys.read(mc);
        lastYaw = p.getYaw();
        lastPitch = p.getPitch();
        lastSlot = p.getInventory().getSelectedSlot();
        macro.frames.add(new Macro.Frame(0, lastKeys, lastYaw, lastPitch, lastSlot));

        recording = true;
        Chat.good("recording " + clean + " - stand where you want the macro to start, "
                + "then run it; /ap stop when done");
        return null;
    }

    /** Finish and save. Returns the saved macro, or null if there was nothing to save. */
    public static synchronized Macro stop(MinecraftClient mc) {
        if (!recording) return null;
        recording = false;
        Macro done = macro;
        macro = null;
        if (done == null) return null;

        ClientPlayerEntity p = mc.player;

        // Always end with everything released, so a replay cannot leave a key held because
        // the recording happened to stop mid-click.
        done.frames.add(new Macro.Frame(t + 1, 0, lastYaw, lastPitch, lastSlot));
        done.lengthTicks = t + 1;

        if (p != null) {
            double dx = p.getX() - done.anchor.x;
            double dy = p.getY() - done.anchor.y;
            double dz = p.getZ() - done.anchor.z;
            double drift = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Half a block of slack: a loop that ends a block away walks off the farm after
            // twenty passes, and the whole point is that it can be left alone.
            done.loopable = drift <= Math.max(0.5, Config.get().anchorTolerance);
        }

        try {
            MacroStore.save(done);
        } catch (Exception e) {
            Chat.warn("could not save " + done.name + ": " + e.getMessage());
            return null;
        }

        String shape = done.loopable
                ? "loopable"
                : "not loopable - it ends away from where it started";
        Chat.good(String.format("saved %s: %.1fs, %d frames, %s",
                done.name, done.lengthTicks / 20.0, done.frames.size(), shape));
        return done;
    }

    public static synchronized void cancel() {
        if (!recording) return;
        recording = false;
        macro = null;
        Chat.warn("recording discarded");
    }

    /** Called once per client tick, before the scheduler. */
    public static void onTick(MinecraftClient mc) {
        if (!recording) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            Chat.warn("left the world - recording discarded");
            cancel();
            return;
        }

        Config cfg = Config.get();
        t++;
        if (cfg.maxRecordMinutes > 0 && t > cfg.maxRecordMinutes * 60 * 20) {
            Chat.warn("hit the " + cfg.maxRecordMinutes + " minute recording limit");
            stop(mc);
            return;
        }

        int keys = Keys.read(mc);
        float yaw = p.getYaw();
        float pitch = p.getPitch();
        int slot = p.getInventory().getSelectedSlot();
        float dead = Math.max(0.001f, cfg.lookDeadzoneDegrees);

        boolean changed = keys != lastKeys
                || slot != lastSlot
                || Math.abs(yaw - lastYaw) > dead
                || Math.abs(pitch - lastPitch) > dead;
        if (!changed) return;

        macro.frames.add(new Macro.Frame(t, keys, yaw, pitch, slot));
        lastKeys = keys;
        lastYaw = yaw;
        lastPitch = pitch;
        lastSlot = slot;

        if (cfg.showStatus && t % Math.max(5, cfg.statusEveryTicks) == 0) {
            Chat.status(String.format("recording %s  %ds  %d frames",
                    macro.name, t / 20, macro.frames.size()));
        }
    }
}
