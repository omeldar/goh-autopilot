package com.omeldar.autopilot.replay;

import com.omeldar.autopilot.Chat;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Task;
import com.omeldar.autopilot.pilot.Walk;
import com.omeldar.autopilot.record.Macro;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * Plays a macro back, optionally on repeat.
 *
 * <p>The interesting part is not the playback, it is the anchor. A farm macro is a sequence of
 * key presses that only means anything from one exact spot: half a block to the left and the
 * same presses break the wrong block, or reach into someone's chest. So the recording stores
 * where it started, and every pass checks that the player is still standing there - before
 * the first frame, and again before every repeat. Drift gets walked off or stops the run; it
 * never gets ignored.
 *
 * <p>Between frames nothing happens at all. A macro that holds right-click for nine seconds
 * is two frames and nine seconds of a held key, not a hundred and eighty decisions.
 */
public final class Replayer implements Task {
    private enum Phase { REALIGN, PLAY }

    private final Macro macro;
    /** 0 means until something stops it. */
    private final int loops;

    private Phase phase = Phase.PLAY;
    private final Walk walk = new Walk();
    private int realignTicks;
    private int localTick;
    private int frameIndex;
    private int appliedKeys;
    private int pass;
    private int totalTicks;
    private String note = "";

    public Replayer(Macro macro, int loops) {
        this.macro = macro;
        this.loops = Math.max(0, loops);
    }

    @Override
    public String name() {
        return "macro " + macro.name + (loops == 0 ? " (looping)" : " x" + loops);
    }

    @Override
    public String start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "not in a world";
        if (macro.frames.isEmpty()) return macro.name + " has no frames";

        String here = mc.world.getRegistryKey().getValue().toString();
        if (!macro.dimension.isBlank() && !macro.dimension.equals(here)) {
            return macro.name + " was recorded in " + macro.dimension + ", and you are in "
                 + here + ". Travel there first.";
        }
        if (loops != 1 && !macro.loopable) {
            return macro.name + " does not end where it started, so looping it would walk "
                 + "the player off the farm. Run it once, or record it again finishing on "
                 + "the starting block.";
        }

        String off = anchorComplaint(p);
        if (off != null) {
            return off + " Stand on the recorded spot, or ask to travel to the anchor first "
                 + "(" + anchorText() + ").";
        }

        Config cfg = Config.get();
        if (cfg.snapLookOnStart) {
            p.setYaw(macro.anchor.yaw);
            p.setPitch(macro.anchor.pitch);
        }
        beginPass(mc);
        return null;
    }

    @Override
    public boolean tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;
        totalTicks++;

        if (phase == Phase.REALIGN) return realignTick(mc, p);

        // Apply every frame whose moment has arrived. More than one can land on the same
        // tick after a lag spike, and the last one wins, which is the right answer: it is
        // the state the player was actually in.
        while (frameIndex < macro.frames.size() && macro.frames.get(frameIndex).t <= localTick) {
            Macro.Frame f = macro.frames.get(frameIndex++);
            Keys.apply(mc, f.keys, appliedKeys);
            appliedKeys = f.keys;
            p.setYaw(f.yaw);
            p.setPitch(f.pitch);
            if (f.slot >= 0 && f.slot < 9 && p.getInventory().getSelectedSlot() != f.slot) {
                p.getInventory().setSelectedSlot(f.slot);
            }
        }

        localTick++;
        if (localTick < macro.lengthTicks) return true;

        // End of a pass.
        Keys.apply(mc, 0, appliedKeys);
        appliedKeys = 0;
        pass++;
        if (loops != 0 && pass >= loops) {
            note = "ran " + pass + " pass" + (pass == 1 ? "" : "es");
            return false;
        }

        String off = anchorComplaint(p);
        if (off == null) {
            beginPass(mc);
            return true;
        }
        if ("stop".equalsIgnoreCase(Config.get().onDrift)) {
            note = "stopped: drifted off the anchor after " + pass + " passes: " + off;
            return false;
        }
        Chat.info("drifted off the anchor, walking back (" + off + ")");
        phase = Phase.REALIGN;
        realignTicks = 0;
        walk.reset(p);
        return true;
    }

    private boolean realignTick(MinecraftClient mc, ClientPlayerEntity p) {
        // Thirty seconds is generous for a walk back onto a farm block, and short enough
        // that a macro left running in a changed world gives up rather than wanders.
        if (++realignTicks > 600) {
            note = "stopped: could not get back to the anchor after " + pass + " passes";
            walk.release(mc);
            return false;
        }
        Walk.Step step = walk.step(mc, macro.anchor.x, macro.anchor.y, macro.anchor.z,
                Math.max(0.35, Config.get().anchorTolerance * 0.6));
        if (step == Walk.Step.BLOCKED) {
            note = "stopped: the way back to the anchor is blocked";
            return false;
        }
        if (step == Walk.Step.WRONG_LEVEL) {
            note = "stopped: drifted onto a different floor from the anchor";
            return false;
        }
        if (step == Walk.Step.ARRIVED) {
            walk.release(mc);
            p.setYaw(macro.anchor.yaw);
            p.setPitch(macro.anchor.pitch);
            if (anchorComplaint(p) != null) {
                note = "stopped: walked back but still off the anchor";
                return false;
            }
            phase = Phase.PLAY;
            beginPass(mc);
        }
        return true;
    }

    private void beginPass(MinecraftClient mc) {
        phase = Phase.PLAY;
        localTick = 0;
        frameIndex = 0;
        appliedKeys = 0;
        Keys.releaseAll(mc);
    }

    /** Null when the player is standing on the anchor, otherwise what is wrong with it. */
    private String anchorComplaint(ClientPlayerEntity p) {
        Config cfg = Config.get();
        double dx = p.getX() - macro.anchor.x;
        double dy = p.getY() - macro.anchor.y;
        double dz = p.getZ() - macro.anchor.z;
        double off = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (off > cfg.anchorTolerance) {
            return String.format(Locale.ROOT, "%.2f blocks off the anchor (tolerance %.2f)",
                    off, cfg.anchorTolerance);
        }
        if (!cfg.snapLookOnStart && cfg.anchorLookTolerance > 0) {
            float dyaw = Math.abs(MathHelper.wrapDegrees(p.getYaw() - macro.anchor.yaw));
            float dpitch = Math.abs(p.getPitch() - macro.anchor.pitch);
            if (dyaw > cfg.anchorLookTolerance || dpitch > cfg.anchorLookTolerance) {
                return String.format(Locale.ROOT, "looking %.0f/%.0f degrees off", dyaw, dpitch);
            }
        }
        return null;
    }

    private String anchorText() {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f",
                macro.anchor.x, macro.anchor.y, macro.anchor.z);
    }

    @Override
    public void stop(MinecraftClient mc, String reason) {
        Keys.releaseAll(mc);
        walk.release(mc);
        if (!note.isBlank()) Chat.info(note);
    }

    @Override
    public String status() {
        if (phase == Phase.REALIGN) {
            return "macro " + macro.name + ": walking back to the anchor";
        }
        String of = loops == 0 ? "" : "/" + loops;
        return String.format(Locale.ROOT, "macro %s  pass %d%s  %ds in  %ds elapsed",
                macro.name, pass + 1, of, localTick / 20, totalTicks / 20);
    }

    public int passes() {
        return pass;
    }

    @Override
    public String outcome() {
        return note;
    }
}
