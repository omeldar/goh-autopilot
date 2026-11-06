package com.omeldar.autopilot;

import net.minecraft.client.MinecraftClient;

/**
 * One thing the mod is doing on the player's behalf. Exactly one runs at a time, which is
 * the point: a macro and a flight can never fight over the same keys.
 */
public interface Task {
    /** Short name, for the status line and the bridge. */
    String name();

    /** Called once before the first tick. Return a reason to refuse, or null to start. */
    default String start(MinecraftClient mc) {
        return null;
    }

    /** One client tick. Return false when finished. */
    boolean tick(MinecraftClient mc);

    /** Called once when the task stops, for any reason. */
    default void stop(MinecraftClient mc, String reason) {}

    /** One line of human-readable progress. */
    String status();
}
