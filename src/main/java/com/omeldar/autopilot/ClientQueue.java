package com.omeldar.autopilot;

import net.minecraft.client.MinecraftClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Runs a piece of work on the client thread and waits for its answer.
 *
 * <p>The bridge is served by its own thread, and almost nothing in Minecraft is safe to read
 * from there - a position read off-thread can land mid-update and report a player halfway
 * between two ticks, and a write can corrupt state outright. So every bridge call funnels
 * through here: hand the closure to {@code mc.execute}, block the bridge thread, answer from
 * the client thread's result.
 */
public final class ClientQueue {
    private ClientQueue() {}

    public static <T> T on(Function<MinecraftClient, T> work) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(work.apply(mc));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(15, TimeUnit.SECONDS);
    }
}
