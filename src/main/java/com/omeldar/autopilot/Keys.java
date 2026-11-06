package com.omeldar.autopilot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The only thing in this mod that touches the game: it holds and releases the player's own
 * key bindings.
 *
 * <p>That is a deliberate design choice. Nothing here moves the player, swings the arm or
 * places a block directly; it sets the same {@code pressed} flag the keyboard sets, one tick
 * before vanilla reads it, and vanilla then does every bit of the actual work. So a replay is
 * subject to exactly the same rules, cooldowns, reach checks and server packets as a human
 * holding the key, and there is no behaviour here that a keyboard could not produce.
 *
 * <p>Two kinds of signal are needed, because vanilla reads the bindings two different ways.
 * {@code isPressed()} drives held actions - walking, continuous mining, holding right-click -
 * and that is what {@link #hold} sets. {@code wasPressed()} drives discrete actions, and
 * vanilla drains it in a {@code while} loop each tick, so a click has to be counted rather
 * than held; that is {@link #click}.
 */
public final class Keys {
    /** Bit positions used by the recorder and the macro format. Do not reorder: saved macros. */
    public static final int FORWARD = 0;
    public static final int BACK = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    public static final int JUMP = 4;
    public static final int SNEAK = 5;
    public static final int SPRINT = 6;
    public static final int ATTACK = 7;
    public static final int USE = 8;
    public static final int SWAP_HANDS = 9;
    public static final int DROP = 10;
    public static final int COUNT = 11;

    private static final String[] NAMES = {
        "forward", "back", "left", "right", "jump", "sneak",
        "sprint", "attack", "use", "swapHands", "drop"
    };

    /** What the mod is currently holding down, so it can always let go of exactly that. */
    private static final boolean[] HELD = new boolean[COUNT];

    private Keys() {}

    public static String name(int bit) {
        return bit >= 0 && bit < NAMES.length ? NAMES[bit] : "?";
    }

    public static KeyBinding binding(MinecraftClient mc, int bit) {
        GameOptions o = mc.options;
        return switch (bit) {
            case FORWARD -> o.forwardKey;
            case BACK -> o.backKey;
            case LEFT -> o.leftKey;
            case RIGHT -> o.rightKey;
            case JUMP -> o.jumpKey;
            case SNEAK -> o.sneakKey;
            case SPRINT -> o.sprintKey;
            case ATTACK -> o.attackKey;
            case USE -> o.useKey;
            case SWAP_HANDS -> o.swapHandsKey;
            case DROP -> o.dropKey;
            default -> null;
        };
    }

    /** The live state of every binding, as a bitmask. Used by the recorder. */
    public static int read(MinecraftClient mc) {
        int mask = 0;
        for (int bit = 0; bit < COUNT; bit++) {
            KeyBinding b = binding(mc, bit);
            if (b != null && b.isPressed()) mask |= 1 << bit;
        }
        return mask;
    }

    public static boolean isSet(int mask, int bit) {
        return (mask & (1 << bit)) != 0;
    }

    /** Hold a binding down, or let it go. */
    public static void hold(MinecraftClient mc, int bit, boolean down) {
        KeyBinding b = binding(mc, bit);
        if (b == null) return;
        b.setPressed(down);
        HELD[bit] = down;
    }

    /**
     * Count one discrete press, the way a real click does. Needed for anything vanilla
     * handles through {@code wasPressed()} rather than {@code isPressed()}: swapping hands,
     * dropping an item, and the first swing of an attack.
     */
    public static void click(MinecraftClient mc, int bit) {
        KeyBinding b = binding(mc, bit);
        if (b == null) return;
        InputUtil.Key key = InputUtil.fromTranslationKey(b.getBoundKeyTranslationKey());
        if (key != null && !key.equals(InputUtil.UNKNOWN_KEY)) {
            KeyBinding.onKeyPressed(key);
        }
    }

    /**
     * Apply a whole bitmask, clicking on every rising edge. {@code previous} is the mask
     * applied last tick, so a key that was already down is not clicked again.
     */
    public static void apply(MinecraftClient mc, int mask, int previous) {
        for (int bit = 0; bit < COUNT; bit++) {
            boolean want = isSet(mask, bit);
            boolean had = isSet(previous, bit);
            if (want && !had) click(mc, bit);
            if (want != had || HELD[bit] != want) hold(mc, bit, want);
        }
    }

    /**
     * Let go of everything the mod is holding. Called whenever a run ends for any reason -
     * finished, cancelled, guard trip, disconnect - so the mod can never leave a key stuck
     * down. Bindings the mod never touched are left alone.
     */
    public static void releaseAll(MinecraftClient mc) {
        if (mc == null || mc.options == null) return;
        for (int bit = 0; bit < COUNT; bit++) {
            if (!HELD[bit]) continue;
            KeyBinding b = binding(mc, bit);
            if (b != null) b.setPressed(false);
            HELD[bit] = false;
        }
    }

    /** Which bindings are held right now, for the status line and the bridge. */
    public static List<String> heldNames() {
        List<String> out = new ArrayList<>();
        for (int bit = 0; bit < COUNT; bit++) {
            if (HELD[bit]) out.add(NAMES[bit]);
        }
        return out;
    }
}
