package com.omeldar.autopilot.farm;

import com.google.gson.JsonObject;

/**
 * One entry in the farm book: a place, and what to do when you get there.
 *
 * <p>This is the whole point of the mod from a Claude session's side. "Go start the sugarcane
 * farm" is not a thing a model can work out from a world scan - it needs to know that the
 * sugarcane farm is at those coordinates, that you approach from the north platform because
 * the south one has a drop, that the lever by the door has to be flipped first, and that the
 * run macro is called sugarcane-run. All of that is knowledge about a particular world, so it
 * lives in a file the player owns rather than in the model or in the code.
 */
public final class Farm {
    public String id = "";
    public String name = "";
    public String dimension = "minecraft:overworld";
    /** Where to fly or walk to first: somewhere safe to arrive, not the working spot. */
    public Spot approach;
    /** The exact block to stand on, which should match the macro anchor. */
    public Spot station;
    /** Run once on arrival: flip the lever, open the door, switch the water off. */
    public String startMacro = "";
    /** Run on repeat: the actual farming. */
    public String runMacro = "";
    /** Run once when stopping, if there is anything to put back. */
    public String stopMacro = "";
    /** What the farm produces, in the player's words. Shown to whoever asks. */
    public String yields = "";
    /** Anything a person would tell you before you used it. */
    public String notes = "";

    public static final class Spot {
        public double x, y, z;
        public float yaw, pitch;

        public Spot() {}

        public Spot(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name == null || name.isBlank() ? id : name);
        o.addProperty("dimension", dimension);
        if (approach != null) o.add("approach", spot(approach));
        if (station != null) o.add("station", spot(station));
        o.addProperty("startMacro", nz(startMacro));
        o.addProperty("runMacro", nz(runMacro));
        o.addProperty("stopMacro", nz(stopMacro));
        o.addProperty("yields", nz(yields));
        o.addProperty("notes", nz(notes));
        return o;
    }

    private static JsonObject spot(Spot s) {
        JsonObject o = new JsonObject();
        o.addProperty("x", s.x);
        o.addProperty("y", s.y);
        o.addProperty("z", s.z);
        o.addProperty("yaw", s.yaw);
        o.addProperty("pitch", s.pitch);
        return o;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
