package com.omeldar.autopilot.replay;

import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Task;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/**
 * The plain auto-clicker, for when a macro is more ceremony than the job needs.
 *
 * <p>Clicks a button, holds right-click on a crop, grinds a stack through a grindstone. It
 * presses one key on a fixed interval and watches the same guards as everything else, so it
 * stops when the chest fills or a mob turns up instead of running into the evening.
 *
 * <p>The rate is capped at twenty a second because the game runs at twenty ticks a second:
 * anything faster is a number in a config file that the game cannot act on.
 */
public final class Clicker implements Task {
    private final int bit;
    private final int everyTicks;
    private final boolean hold;
    private final int limitTicks;
    private int ticks;
    private int clicks;

    /**
     * @param bit        which binding, from {@link Keys}
     * @param cps        clicks per second, 1 to 20; ignored when holding
     * @param hold       hold the key down instead of tapping it
     * @param seconds    stop after this long; 0 for no limit of its own
     */
    public Clicker(int bit, double cps, boolean hold, int seconds) {
        this.bit = bit;
        this.hold = hold;
        double clamped = Math.max(0.5, Math.min(20.0, cps));
        this.everyTicks = Math.max(1, (int) Math.round(20.0 / clamped));
        this.limitTicks = Math.max(0, seconds) * 20;
    }

    @Override
    public String name() {
        return (hold ? "hold " : "click ") + Keys.name(bit);
    }

    @Override
    public boolean tick(MinecraftClient mc) {
        if (limitTicks > 0 && ticks >= limitTicks) return false;
        ticks++;
        if (hold) {
            if (ticks == 1) {
                Keys.click(mc, bit);
                Keys.hold(mc, bit, true);
            }
            return true;
        }
        if (ticks % everyTicks == 0) {
            Keys.click(mc, bit);
            // One tick of held state per click, so vanilla sees a press and a release the way
            // it would from a mouse rather than a key that is somehow always down.
            Keys.hold(mc, bit, true);
            clicks++;
        } else {
            Keys.hold(mc, bit, false);
        }
        return true;
    }

    @Override
    public void stop(MinecraftClient mc, String reason) {
        Keys.hold(mc, bit, false);
    }

    @Override
    public String status() {
        if (hold) {
            return String.format(Locale.ROOT, "holding %s  %ds", Keys.name(bit), ticks / 20);
        }
        return String.format(Locale.ROOT, "clicking %s  %d clicks  %ds",
                Keys.name(bit), clicks, ticks / 20);
    }
}
