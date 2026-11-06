package com.omeldar.autopilot.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A recorded sequence, stored as what changed and when rather than as one row per tick.
 *
 * <p>The difference matters more than it sounds. A sugarcane run is mostly "hold right-click
 * and stand still for nine seconds", which is two frames delta-encoded and a hundred and
 * eighty rows otherwise; a file you may want to open and read by hand should look like the
 * first thing. Replay holds the last frame's state until the next frame's tick comes round,
 * so the two are exactly equivalent.
 *
 * <p>Frames are written as flat arrays - {@code [tick, keymask, yaw, pitch, slot]} - because
 * a few thousand of them as objects with named fields turns a readable file into a wall.
 */
public final class Macro {
    public static final int FORMAT = 1;

    public String name;
    public int format = FORMAT;
    public String createdAt = "";
    public String dimension = "";
    /** Where the recording started, and which way it faced. */
    public Anchor anchor = new Anchor();
    /** How long one pass takes, in ticks. */
    public int lengthTicks;
    /**
     * Whether the recording ends roughly where it began. Only a loopable macro can be run on
     * repeat without walking away from the farm, so this is checked when it is saved rather
     * than hoped for at run time.
     */
    public boolean loopable;
    public String note = "";
    public final List<Frame> frames = new ArrayList<>();

    public static final class Anchor {
        public double x, y, z;
        public float yaw, pitch;
    }

    /** One moment where something changed. */
    public static final class Frame {
        public int t;
        public int keys;
        public float yaw, pitch;
        /** Selected hotbar slot, 0-8. */
        public int slot;

        public Frame() {}

        public Frame(int t, int keys, float yaw, float pitch, int slot) {
            this.t = t;
            this.keys = keys;
            this.yaw = yaw;
            this.pitch = pitch;
            this.slot = slot;
        }
    }

    // ------------------------------------------------------------------- json

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("format", format);
        o.addProperty("createdAt", createdAt);
        o.addProperty("dimension", dimension);
        o.addProperty("lengthTicks", lengthTicks);
        o.addProperty("lengthSeconds", round(lengthTicks / 20.0));
        o.addProperty("loopable", loopable);
        o.addProperty("note", note == null ? "" : note);

        JsonObject a = new JsonObject();
        a.addProperty("x", round(anchor.x));
        a.addProperty("y", round(anchor.y));
        a.addProperty("z", round(anchor.z));
        a.addProperty("yaw", round(anchor.yaw));
        a.addProperty("pitch", round(anchor.pitch));
        o.add("anchor", a);

        o.addProperty("frameFormat", "[tick, keymask, yaw, pitch, hotbarSlot]");
        JsonArray rows = new JsonArray();
        for (Frame f : frames) {
            JsonArray row = new JsonArray();
            row.add(f.t);
            row.add(f.keys);
            row.add(round(f.yaw));
            row.add(round(f.pitch));
            row.add(f.slot);
            rows.add(row);
        }
        o.add("frames", rows);
        return o;
    }

    public static Macro fromJson(JsonObject o) {
        Macro m = new Macro();
        m.name = str(o, "name", "unnamed");
        m.format = o.has("format") ? o.get("format").getAsInt() : FORMAT;
        m.createdAt = str(o, "createdAt", "");
        m.dimension = str(o, "dimension", "");
        m.lengthTicks = o.has("lengthTicks") ? o.get("lengthTicks").getAsInt() : 0;
        m.loopable = o.has("loopable") && o.get("loopable").getAsBoolean();
        m.note = str(o, "note", "");
        if (o.has("anchor")) {
            JsonObject a = o.getAsJsonObject("anchor");
            m.anchor.x = num(a, "x");
            m.anchor.y = num(a, "y");
            m.anchor.z = num(a, "z");
            m.anchor.yaw = (float) num(a, "yaw");
            m.anchor.pitch = (float) num(a, "pitch");
        }
        if (o.has("frames")) {
            for (var el : o.getAsJsonArray("frames")) {
                JsonArray row = el.getAsJsonArray();
                m.frames.add(new Frame(
                        row.get(0).getAsInt(),
                        row.get(1).getAsInt(),
                        row.get(2).getAsFloat(),
                        row.get(3).getAsFloat(),
                        row.size() > 4 ? row.get(4).getAsInt() : 0));
            }
        }
        return m;
    }

    /** A one-line summary for listings, so a caller can choose without reading the frames. */
    public JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("seconds", round(lengthTicks / 20.0));
        o.addProperty("frames", frames.size());
        o.addProperty("loopable", loopable);
        o.addProperty("dimension", dimension);
        o.addProperty("anchor", (int) Math.floor(anchor.x) + " "
                + (int) Math.floor(anchor.y) + " " + (int) Math.floor(anchor.z));
        if (note != null && !note.isBlank()) o.addProperty("note", note);
        return o;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
