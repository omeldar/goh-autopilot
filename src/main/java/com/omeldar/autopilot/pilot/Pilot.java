package com.omeldar.autopilot.pilot;

import com.omeldar.autopilot.Chat;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * Travel to a coordinate: the "go there" half of "go there and start the farm".
 *
 * <p>Three ways of getting about, and the mod picks the honest one rather than the clever one:
 *
 * <ul>
 *   <li><b>Walk</b> always works and is the fallback. Short hops, flat ground, back to the
 *       block you stand on at a farm.</li>
 *   <li><b>Fly</b> is creative flight, where distance is free and nothing can go wrong. It
 *       climbs to a cruising height, reacts to terrain in front of it, and sets down on the
 *       target.</li>
 *   <li><b>Elytra</b> only ever <em>assists</em> a glide the player already started. It will
 *       steer and spend rockets and bring you down gently, and it will never launch you off a
 *       cliff by itself. That is a deliberate limit: an automatic launch that misjudges a
 *       mountain costs the gear and everything in the inventory, and no amount of care in a
 *       hundred lines of client code makes that a risk worth taking unattended.</li>
 * </ul>
 */
public final class Pilot implements Task {
    public enum Mode { AUTO, WALK, FLY, ELYTRA }

    private enum Phase { TAKEOFF, CRUISE, DESCEND, GROUND, DONE }

    private final double tx, ty, tz;
    private final Mode requested;
    private final String label;

    private Mode mode = Mode.WALK;
    private Phase phase = Phase.GROUND;
    private final Walk walk = new Walk();
    private int ticks;
    private int cruiseY;
    private String note = "";

    public Pilot(double x, double y, double z, Mode mode, String label) {
        this.tx = x;
        this.ty = y;
        this.tz = z;
        this.requested = mode == null ? Mode.AUTO : mode;
        this.label = label == null || label.isBlank()
                ? String.format(Locale.ROOT, "%.0f %.0f %.0f", x, y, z)
                : label;
    }

    @Override
    public String name() {
        return "travel to " + label;
    }

    @Override
    public String start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "not in a world";

        boolean canFly = p.getAbilities().allowFlying;
        boolean gliding = p.isGliding() && hasElytra(p);

        mode = switch (requested) {
            case WALK -> Mode.WALK;
            case FLY -> canFly ? Mode.FLY : null;
            case ELYTRA -> gliding ? Mode.ELYTRA : null;
            case AUTO -> {
                if (gliding && Config.get().allowElytra) yield Mode.ELYTRA;
                else if (canFly) yield Mode.FLY;
                else yield Mode.WALK;
            }
        };
        if (mode == null) {
            return requested == Mode.FLY
                    ? "flight is not available here, so fly mode cannot be used"
                    : "elytra mode only steers a glide you have already started, and you are "
                      + "not gliding. Jump off something first, or use walk.";
        }
        if (mode == Mode.ELYTRA && !Config.get().allowElytra) {
            return "elytra travel is off in the config (allowElytra)";
        }

        int ceiling = mc.world.getTopYInclusive() - 12;
        cruiseY = (int) Math.min(ceiling,
                Math.max(p.getY(), ty) + Math.max(4, Config.get().cruiseHeight));

        walk.reset(p);
        ticks = 0;

        // Already there: start on the ground so the first tick finishes rather than taking
        // off for a flight of nothing. A model asking twice should not launch the player.
        double flat = Math.hypot(tx - p.getX(), tz - p.getZ());
        boolean here = flat <= Config.get().arriveWithin && Math.abs(ty - p.getY()) < 3;

        phase = here ? Phase.GROUND : switch (mode) {
            case FLY -> Phase.TAKEOFF;
            case ELYTRA -> Phase.CRUISE;
            default -> Phase.GROUND;
        };
        return null;
    }

    @Override
    public boolean tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;

        Config cfg = Config.get();
        if (cfg.travelTimeoutSeconds > 0 && ++ticks > cfg.travelTimeoutSeconds * 20) {
            note = "timed out after " + cfg.travelTimeoutSeconds + "s";
            release(mc);
            return false;
        }

        double flat = Math.hypot(tx - p.getX(), tz - p.getZ());
        if (flat <= cfg.arriveWithin && p.isOnGround() && Math.abs(ty - p.getY()) <= 2.5) {
            release(mc);
            note = "arrived";
            return false;
        }

        return switch (mode) {
            case WALK, AUTO -> walkTick(mc, cfg);
            case FLY -> flyTick(mc, p, cfg, flat);
            case ELYTRA -> elytraTick(mc, p, cfg, flat);
        };
    }

    // ------------------------------------------------------------------- walk

    private boolean walkTick(MinecraftClient mc, Config cfg) {
        Walk.Step step = walk.step(mc, tx, ty, tz, cfg.arriveWithin);
        switch (step) {
            case ARRIVED -> {
                note = "arrived on foot";
                return false;
            }
            case BLOCKED -> {
                note = "stopped: no way forward on foot from here. "
                     + "Walking does not dig, bridge or jump gaps.";
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    // -------------------------------------------------------------------- fly

    private boolean flyTick(MinecraftClient mc, ClientPlayerEntity p, Config cfg, double flat) {
        // Touched down already: finish the last few blocks on foot rather than taking off
        // again, which is what reading GROUND as "not cruising" would do.
        if (phase == Phase.GROUND) return walkTick(mc, cfg);

        if (phase == Phase.TAKEOFF) {
            if (p.getAbilities().flying) {
                phase = Phase.CRUISE;
            } else {
                // A double tap on jump is how a player starts flying, so that is what this
                // does; the ability flag is only forced if the taps did not take.
                int cycle = ticks % 8;
                Keys.hold(mc, Keys.JUMP, cycle == 0 || cycle == 3);
                if (ticks > 24) {
                    // The taps did not take - a server with odd abilities, or a key bound to
                    // something the double-tap check does not see. Set the flag and get on.
                    p.getAbilities().flying = true;
                    Keys.hold(mc, Keys.JUMP, false);
                    phase = Phase.CRUISE;
                }
            }
            return true;
        }

        boolean closing = flat < 8;
        if (closing) phase = Phase.DESCEND;

        if (phase == Phase.DESCEND) {
            Keys.hold(mc, Keys.FORWARD, flat > 1.2);
            Keys.hold(mc, Keys.SPRINT, false);
            faceTarget(mc, p, 25f);
            Keys.hold(mc, Keys.JUMP, false);
            // Sneak descends in flight. Vanilla drops the flying flag on touchdown, so
            // landing needs nothing beyond holding it until the ground arrives.
            Keys.hold(mc, Keys.SNEAK, !p.isOnGround());
            if (p.isOnGround()) {
                Keys.hold(mc, Keys.SNEAK, false);
                phase = Phase.GROUND;
                walk.reset(p);
            }
            return true;
        }

        // Cruise: hold altitude, react to anything in the way, and head for the target.
        faceTarget(mc, p, 0f);
        Keys.hold(mc, Keys.FORWARD, true);
        Keys.hold(mc, Keys.SPRINT, true);

        boolean low = p.getY() < cruiseY - 1;
        boolean wallAhead = solidAhead(mc, p, 3);
        Keys.hold(mc, Keys.JUMP, low || wallAhead);
        Keys.hold(mc, Keys.SNEAK, !low && !wallAhead && p.getY() > cruiseY + 3);
        return true;
    }

    // ----------------------------------------------------------------- elytra

    private boolean elytraTick(MinecraftClient mc, ClientPlayerEntity p, Config cfg, double flat) {
        if (!p.isGliding()) {
            if (p.isOnGround()) {
                note = "glide ended on the ground, " + (int) flat + " blocks short";
                phase = Phase.GROUND;
                walk.reset(p);
                mode = Mode.WALK;
                return true;
            }
            return true; // falling between glide and ground; let vanilla settle it
        }

        double dy = p.getY() - ty;
        // Far away: shallow nose-down for speed. Close: nose up to bleed it off, because the
        // damage on landing is a function of how fast you were still going.
        float wantedPitch = flat > 120 ? 2f : flat > 40 ? 8f : flat > 15 ? -5f : -18f;
        if (dy < 15 && flat > 40) wantedPitch = -8f;

        faceTarget(mc, p, wantedPitch);

        double speed = p.getVelocity().horizontalLength();
        boolean wantRocket = flat > 80 && speed < 0.45 && dy > 25 && hasRocket(p);
        Keys.hold(mc, Keys.USE, false);
        if (wantRocket && ticks % 40 == 0) {
            selectRocket(mc, p);
            Keys.click(mc, Keys.USE);
        }

        if (flat < 12 && p.isOnGround()) {
            note = "landed under elytra";
            return false;
        }
        return true;
    }

    private static boolean hasElytra(ClientPlayerEntity p) {
        return p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private static boolean hasRocket(ClientPlayerEntity p) {
        for (int slot = 0; slot < 9; slot++) {
            if (p.getInventory().getStack(slot).isOf(Items.FIREWORK_ROCKET)) return true;
        }
        return false;
    }

    private static void selectRocket(MinecraftClient mc, ClientPlayerEntity p) {
        for (int slot = 0; slot < 9; slot++) {
            if (p.getInventory().getStack(slot).isOf(Items.FIREWORK_ROCKET)) {
                p.getInventory().setSelectedSlot(slot);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void faceTarget(MinecraftClient mc, ClientPlayerEntity p, float pitch) {
        float wanted = Walk.yawToward(tx - p.getX(), tz - p.getZ());
        float delta = MathHelper.wrapDegrees(wanted - p.getYaw());
        p.setYaw(p.getYaw() + MathHelper.clamp(delta, -12f, 12f));
        float dp = MathHelper.clamp(pitch - p.getPitch(), -8f, 8f);
        p.setPitch(p.getPitch() + dp);
    }

    private static boolean solidAhead(MinecraftClient mc, ClientPlayerEntity p, int distance) {
        double rad = Math.toRadians(p.getYaw());
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        for (int step = 1; step <= distance; step++) {
            BlockPos probe = BlockPos.ofFloored(
                    p.getX() + fx * step * 1.5, p.getY() + 0.5, p.getZ() + fz * step * 1.5);
            if (blocks(mc, probe) || blocks(mc, probe.up())) return true;
        }
        return false;
    }

    private static boolean blocks(MinecraftClient mc, BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private void release(MinecraftClient mc) {
        walk.release(mc);
        Keys.hold(mc, Keys.SNEAK, false);
        Keys.hold(mc, Keys.JUMP, false);
        Keys.hold(mc, Keys.USE, false);
    }

    @Override
    public void stop(MinecraftClient mc, String reason) {
        release(mc);
        if (!note.isBlank()) Chat.info(note);
    }

    @Override
    public String status() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return "travelling";
        double flat = Math.hypot(tx - mc.player.getX(), tz - mc.player.getZ());
        return String.format(Locale.ROOT, "%s to %s  %.0f blocks left",
                mode.name().toLowerCase(Locale.ROOT), label, flat);
    }

    public String outcome() {
        return note;
    }
}
