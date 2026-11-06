package com.omeldar.autopilot;

import com.omeldar.autopilot.record.Recorder;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Two keys, and the one piece of input the mod reads rather than writes.
 *
 * <p>The panic key is bound by default, on End, because a stop you have to go and bind first
 * is not a stop. The record toggle is left unbound so it cannot collide with whatever the
 * player already has.
 *
 * <p>The third thing here is the takeover check: if the player physically touches a movement
 * key while a task is running, the task ends. That has to read the keyboard directly through
 * GLFW rather than through the key bindings, because the bindings are exactly what the mod is
 * holding down - from the binding's point of view the key is already pressed. Reading the
 * hardware is the only way to tell the player's hand from the mod's.
 */
public final class Keybinds {
    private static KeyBinding panic;
    private static KeyBinding toggleRecord;

    private Keybinds() {}

    public static void register() {
        panic = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopilot.panic", GLFW.GLFW_KEY_END, KeyBinding.Category.MISC));
        toggleRecord = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopilot.record", InputUtil.UNKNOWN_KEY.getCode(),
                KeyBinding.Category.MISC));
    }

    public static void onTick(MinecraftClient mc) {
        if (panic != null) {
            while (panic.wasPressed()) {
                if (Scheduler.busy()) {
                    Scheduler.end(mc, "stopped by hand");
                } else if (Recorder.recording()) {
                    Recorder.stop(mc);
                }
            }
        }
        if (toggleRecord != null) {
            while (toggleRecord.wasPressed()) {
                if (Recorder.recording()) {
                    Recorder.stop(mc);
                } else {
                    String name = "quick-" + (System.currentTimeMillis() / 1000 % 100000);
                    String refusal = Recorder.start(mc, name, "recorded with the hotkey");
                    if (refusal != null) Chat.warn(refusal);
                }
            }
        }
        if (Scheduler.busy() && humanTookOver(mc)) {
            Scheduler.end(mc, "you took over");
        }
    }

    /** True when a movement key is physically held down by a hand on the keyboard. */
    private static boolean humanTookOver(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.currentScreen != null) return false;
        int[] watched = {Keys.FORWARD, Keys.BACK, Keys.LEFT, Keys.RIGHT, Keys.SNEAK};
        for (int bit : watched) {
            KeyBinding b = Keys.binding(mc, bit);
            if (b == null) continue;
            InputUtil.Key key = InputUtil.fromTranslationKey(b.getBoundKeyTranslationKey());
            if (key == null || key.getCategory() != InputUtil.Type.KEYSYM) continue;
            int code = key.getCode();
            if (code <= 0) continue;
            if (InputUtil.isKeyPressed(mc.getWindow(), code)) return true;
        }
        return false;
    }
}
