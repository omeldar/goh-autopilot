package com.omeldar.autopilot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Every line the mod says, so the prefix and the colours stay in one place. */
public final class Chat {
    private Chat() {}

    private static Text tag() {
        return Text.literal("[ap] ").formatted(Formatting.AQUA);
    }

    public static void info(String message) {
        if (!Config.get().logToChat) return;
        say(message, Formatting.GRAY);
    }

    public static void good(String message) {
        if (!Config.get().logToChat) return;
        say(message, Formatting.GREEN);
    }

    /** Always shown, config or not: a guard trip is the one thing you must not miss. */
    public static void warn(String message) {
        say(message, Formatting.YELLOW);
    }

    private static void say(String message, Formatting colour) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        mc.player.sendMessage(tag().copy().append(Text.literal(message).formatted(colour)), false);
    }

    /**
     * The status line above the hotbar. Vanilla already draws it every frame, so using it
     * instead of a custom HUD element is why this mod does not cost any frames.
     */
    public static void status(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        mc.inGameHud.setOverlayMessage(
                Text.literal(message).formatted(Formatting.AQUA), false);
    }
}
