package com.omeldar.autopilot.guard;

import com.omeldar.autopilot.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * The reasons a run stops on its own.
 *
 * <p>An unattended macro is only as good as the moment it decides to quit. A human at the
 * keyboard stops when a creeper hisses, when the chest fills up, when someone says their
 * name in chat; this is that judgement, written down, checked once a tick. Every check is an
 * observation of state the client already has - nothing here reaches for information the
 * player would not have on screen.
 *
 * <p>All of it is configurable and all of it fails closed: the first reason that matches ends
 * the run and is reported, rather than being counted and carried on past.
 */
public final class Watchdog {
    private final long startedAtTick;
    private final RegistryKey<World> dimension;
    private float lastHealth;
    private boolean armed;
    private int graceTicks;

    /** Set from the chat listener, since a chat line does not arrive on a tick boundary. */
    private static volatile String pendingMention;

    public Watchdog(MinecraftClient mc, long tick) {
        this.startedAtTick = tick;
        this.lastHealth = mc.player == null ? 20f : mc.player.getHealth();
        this.dimension = mc.world == null ? null : mc.world.getRegistryKey();
        // A screen is often still closing on the tick a command runs, and health can tick in
        // late after a teleport, so give the world a moment to settle before arming.
        this.graceTicks = 10;
        pendingMention = null;
    }

    /** Called by the chat listener when a line mentions the player. */
    public static void noteMention(String line) {
        pendingMention = line;
    }

    public static void clearMention() {
        pendingMention = null;
    }

    /** Null while everything is fine, otherwise the reason to stop, ready to show a human. */
    public String check(MinecraftClient mc, long tick) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "left the world";
        if (p.isDead() || p.getHealth() <= 0) return "died";

        Config cfg = Config.get();

        if (cfg.maxRunMinutes > 0 && tick - startedAtTick > cfg.maxRunMinutes * 60L * 20L) {
            return "hit the " + cfg.maxRunMinutes + " minute ceiling";
        }

        if (graceTicks > 0) {
            graceTicks--;
            lastHealth = p.getHealth();
            return null;
        }
        armed = true;

        if (cfg.stopOnDimensionChange && dimension != null
                && !dimension.equals(mc.world.getRegistryKey())) {
            return "the dimension changed";
        }

        float health = p.getHealth();
        if (cfg.stopOnDamage && health < lastHealth - 0.001f) {
            String reason = String.format("took damage (%.1f -> %.1f)", lastHealth, health);
            lastHealth = health;
            return reason;
        }
        lastHealth = health;

        if (cfg.minHealth > 0 && health < cfg.minHealth) {
            return String.format("health %.1f is below the %.1f floor", health, cfg.minHealth);
        }

        if (cfg.minFood > 0 && p.getHungerManager().getFoodLevel() < cfg.minFood) {
            return "food level " + p.getHungerManager().getFoodLevel()
                 + " is below the " + cfg.minFood + " floor";
        }

        if (cfg.stopOnInventoryFull && p.getInventory().getEmptySlot() < 0) {
            return "the inventory is full";
        }

        if (cfg.minToolDurability > 0) {
            ItemStack held = p.getMainHandStack();
            if (held.isDamageable() && held.getMaxDamage() > 0) {
                double left = 1.0 - (double) held.getDamage() / held.getMaxDamage();
                if (left <= cfg.minToolDurability) {
                    return String.format("%s is down to %.0f%% durability",
                            held.getName().getString(), left * 100);
                }
            }
        }

        if (cfg.stopOnPlayerWithin > 0) {
            double limit = cfg.stopOnPlayerWithin * cfg.stopOnPlayerWithin;
            for (AbstractClientPlayerEntity other : mc.world.getPlayers()) {
                if (other == p) continue;
                if (other.squaredDistanceTo(p) <= limit) {
                    return other.getName().getString() + " came within "
                         + (int) cfg.stopOnPlayerWithin + " blocks";
                }
            }
        }

        if (cfg.stopOnScreen && mc.currentScreen != null) {
            return "a screen opened (" + mc.currentScreen.getClass().getSimpleName() + ")";
        }

        if (cfg.stopOnMention) {
            String mention = pendingMention;
            if (mention != null) {
                pendingMention = null;
                return "chat mentioned you: " + trim(mention);
            }
        }

        return null;
    }

    public boolean armed() {
        return armed;
    }

    private static String trim(String s) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() > 80 ? flat.substring(0, 77) + "..." : flat;
    }
}
