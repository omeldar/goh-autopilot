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
        BLOCKED
    }

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
        if (flat <= arriveWithin && Math.abs(dy) <= 2.5) {
            release(mc);
            return Step.ARRIVED;
        }

        turnToward(p, dx, dz);

        boolean sprint = flat > 4
                && p.getHungerManager().getFoodLevel() > 6
                && !p.isTouchingWater();
        Keys.hold(mc, Keys.FORWARD, true);
        Keys.hold(mc, Keys.SPRINT, sprint);

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
    }

    // ---------------------------------------------------------------- helpers

    /** Yaw toward a horizontal delta, in Minecraft degrees. */
    public static float yawToward(double dx, double dz) {
        return (float) (MathHelper.atan2(dz, dx) * (180 / Math.PI)) - 90f;
    }

    private static void turnToward(ClientPlayerEntity p, double dx, double dz) {
        float wanted = yawToward(dx, dz);
        float delta = MathHelper.wrapDegrees(wanted - p.getYaw());
        float step = MathHelper.clamp(delta, -MAX_TURN, MAX_TURN);
        p.setYaw(p.getYaw() + step);
        // Looking slightly down keeps the step-up check meaningful and looks like a person
        // walking rather than staring at the horizon.
        p.setPitch(MathHelper.clamp(p.getPitch() + (10f - p.getPitch()) * 0.2f, -30f, 40f));
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
