package com.omeldar.autopilot.pilot;

import com.omeldar.autopilot.Keys;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Walking toward a point, one tick at a time.
 *
 * <p>This is not a pathfinder and does not pretend to be. It faces the target, holds forward,
 * hops over a one-block step and swims up out of water, and gives up honestly when it cannot
 * make progress. That covers the actual job - crossing the cleared ground around a farm to
 * get back to the exact block you stand on - and it will not get itself killed trying to
 * cross a ravine, because it refuses instead of improvising.
 *
 * <p>Turning is rate-limited to something a hand could do. Snapping the camera 180 degrees in
 * one tick is both visibly wrong and the single easiest thing for anti-cheat to spot.
 */
public final class Walk {
    public enum Step {
        MOVING,
        ARRIVED,
        /** Could not make progress: walled in, or a drop it will not take. */
        BLOCKED,
        /**
         * Standing over the target, but on a different floor - the target is in the air, in a
         * cave below, or the y it was given was simply wrong. Walking cannot fix any of those,
         * so it is reported rather than circled.
         */
        WRONG_LEVEL
    }

    /** Inside this flat distance, the approach slows down for precision. */
    private static final double CLOSE = 1.5;

    /** How far the view may swing in one tick, in degrees. */
    private static final float MAX_TURN = 14f;

    private double lastX, lastZ;
    private int sinceProgressCheck;
    private int blockedTicks;

    public void reset(ClientPlayerEntity p) {
        lastX = p.getX();
        lastZ = p.getZ();
        sinceProgressCheck = 0;
        blockedTicks = 0;
    }

    public Step step(MinecraftClient mc, double tx, double ty, double tz, double arriveWithin) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return Step.BLOCKED;

        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        double dy = ty - p.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);

        // Arrived means close on the flat and not on a different floor. The vertical test is
        // loose on purpose: standing on a slab or in a one-block dip is still arrived.
        if (flat <= arriveWithin) {
            release(mc);
            if (Math.abs(dy) <= 2.5) return Step.ARRIVED;
            // The first flight test circled a target for two minutes because its y was the
            // y of the hill it took off from. Over the spot and on the wrong floor is an
            // answer, not a reason to keep walking.
            if (p.isOnGround() || p.isTouchingWater()) return Step.WRONG_LEVEL;
            return Step.MOVING;
        }

        boolean close = flat < CLOSE;
        float facingError = turnToward(p, dx, dz, close ? 35f : MAX_TURN);

        // The last block and a half is the part a farm cares about. At walking speed and a
        // hand-like turn rate the turning circle is about 0.8 blocks - wider than the third of
        // a block a farm station needs - so a plain walk orbits the spot instead of stopping
        // on it. Up close: sneak, which is slow enough to stop precisely and will not step off
        // a platform edge, and only press forward once facing the spot.
        boolean sprint = !close && flat > 4
                && p.getHungerManager().getFoodLevel() > 6
                && !p.isTouchingWater();
        Keys.hold(mc, Keys.FORWARD, !close || facingError < 25f);
        Keys.hold(mc, Keys.SPRINT, sprint);
        Keys.hold(mc, Keys.SNEAK, close && !p.isTouchingWater());

        boolean jump = false;
        if (p.isTouchingWater()) {
            // Swim up rather than drown trying to walk a river bed.
            jump = true;
        } else if (p.isOnGround() && stepUpAhead(mc, p)) {
            jump = true;
        }
        Keys.hold(mc, Keys.JUMP, jump);

        if (dangerAhead(mc, p)) {
            release(mc);
            return Step.BLOCKED;
        }

        // Progress check every second. Walking into a wall looks exactly like walking, from
        // the inside; the only way to tell is that the position stops changing.
        if (++sinceProgressCheck >= 20) {
            sinceProgressCheck = 0;
            double moved = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
            lastX = p.getX();
            lastZ = p.getZ();
            if (moved < 0.4) {
                if (++blockedTicks >= 3) {
                    release(mc);
                    return Step.BLOCKED;
                }
            } else {
                blockedTicks = 0;
            }
        }

        return Step.MOVING;
    }

    public void release(MinecraftClient mc) {
        Keys.hold(mc, Keys.FORWARD, false);
        Keys.hold(mc, Keys.SPRINT, false);
        Keys.hold(mc, Keys.JUMP, false);
        Keys.hold(mc, Keys.SNEAK, false);
    }

    // ---------------------------------------------------------------- helpers

    /** Yaw toward a horizontal delta, in Minecraft degrees. */
    public static float yawToward(double dx, double dz) {
        return (float) (MathHelper.atan2(dz, dx) * (180 / Math.PI)) - 90f;
    }

    /** Turn toward a delta, at most {@code maxTurn} degrees; returns the error left after. */
    private static float turnToward(ClientPlayerEntity p, double dx, double dz, float maxTurn) {
        float wanted = yawToward(dx, dz);
        float delta = MathHelper.wrapDegrees(wanted - p.getYaw());
        float step = MathHelper.clamp(delta, -maxTurn, maxTurn);
        p.setYaw(p.getYaw() + step);
        // Looking slightly down keeps the step-up check meaningful and looks like a person
        // walking rather than staring at the horizon.
        p.setPitch(MathHelper.clamp(p.getPitch() + (10f - p.getPitch()) * 0.2f, -30f, 40f));
        return Math.abs(delta - step);
    }

    /** True when there is a one-block step in front that a jump would clear. */
    private static boolean stepUpAhead(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos ahead = aheadOf(p, 0.7, 0);
        BlockPos above = ahead.up();
        boolean wall = solid(mc, ahead);
        boolean clear = !solid(mc, above)
                && !solid(mc, above.up());
        return wall && clear;
    }

    /**
     * True when the ground two blocks ahead drops away further than a jump can recover, or
     * the next step is into lava. Walking off a cliff while unattended is the failure that
     * costs a whole inventory, so this one is checked every tick.
     */
    private static boolean dangerAhead(MinecraftClient mc, ClientPlayerEntity p) {
        if (!p.isOnGround()) return false;
        BlockPos ahead = aheadOf(p, 1.1, 0);

        var aheadState = mc.world.getBlockState(ahead);
        if (!aheadState.getFluidState().isEmpty()
                && aheadState.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return true;
        }

        if (solid(mc, ahead)) return false;

        int drop = 0;
        BlockPos.Mutable cursor = ahead.mutableCopy();
        while (drop < 5) {
            cursor.move(0, -1, 0);
            if (cursor.getY() < mc.world.getBottomY()) return true;
            var state = mc.world.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()
                    && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
                return true;
            }
            if (solid(mc, cursor)) return false;
            drop++;
        }
        return true;
    }

    /**
     * Whether a block would stop the player walking into it. {@code blocksMovement} is
     * deprecated; the collision shape is what the movement code actually consults, so ask
     * that instead of a flag that no longer means quite the same thing.
     */
    private static boolean solid(MinecraftClient mc, BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private static BlockPos aheadOf(ClientPlayerEntity p, double distance, int dy) {
        double rad = Math.toRadians(p.getYaw());
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        return BlockPos.ofFloored(
                p.getX() + fx * distance,
                p.getY() + dy,
                p.getZ() + fz * distance);
    }
}
