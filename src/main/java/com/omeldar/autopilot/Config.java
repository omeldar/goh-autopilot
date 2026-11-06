package com.omeldar.autopilot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Settings, stored at {@code config/autopilot/config.json}. Hand-editable; the mod rewrites
 * the file once on first run to fill in anything missing, and otherwise leaves it alone.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    // ----------------------------------------------------------------- bridge

    /** Serve the local HTTP/MCP bridge so a Claude Code session can drive the mod. */
    public boolean bridgeEnabled = true;
    /** Loopback port. Deliberately beside Lumi's 25599 rather than on top of it. */
    public int bridgePort = 25598;
    /** Shared secret, generated on first run. */
    public String bridgeToken = "";
    /** Announce this mod to Lumi by writing config/lumi-link/autopilot.json. */
    public boolean lumiLink = true;

    // -------------------------------------------------------------- recording

    /**
     * Ignore look changes smaller than this while recording, in degrees. Mouse jitter would
     * otherwise write a frame every single tick and make every macro a few hundred KB.
     */
    public float lookDeadzoneDegrees = 0.05f;
    /** Refuse to keep recording past this, as a runaway guard. */
    public int maxRecordMinutes = 30;

    // ----------------------------------------------------------------- replay

    /**
     * How far from the recorded anchor the player may stand and still start a macro, in
     * blocks. A farm macro that starts half a block off usually hits the wrong block.
     */
    public double anchorTolerance = 0.6;
    /** How far the look angle may be off at the anchor, in degrees. */
    public float anchorLookTolerance = 3.0f;
    /** Snap the look angle to the anchor instead of refusing, when the position is right. */
    public boolean snapLookOnStart = true;
    /** What to do when the player has drifted off the anchor between two loops. */
    public String onDrift = "realign"; // realign | stop

    // ----------------------------------------------------------------- guards
    //
    // None of these is a gameplay change. They are the reasons a human would have stopped
    // too, noticed a tick after the fact instead of ten minutes later.

    /** Stop as soon as the player takes any damage. */
    public boolean stopOnDamage = true;
    /** Stop below this much health, in half-hearts. 0 disables. */
    public float minHealth = 14.0f;
    /** Stop below this food level, out of 20, so a long run cannot starve. 0 disables. */
    public int minFood = 7;
    /** Stop when no inventory slot is free, since everything after that is dropped. */
    public boolean stopOnInventoryFull = true;
    /** Stop when the held tool has this fraction of its durability left or less. 0 disables. */
    public double minToolDurability = 0.08;
    /** Stop when another player comes within this many blocks. 0 disables. */
    public double stopOnPlayerWithin = 0.0;
    /** Stop when a screen opens: a chest, the inventory, a death screen, anything. */
    public boolean stopOnScreen = true;
    /** Stop when the dimension changes. */
    public boolean stopOnDimensionChange = true;
    /** Stop when a chat line mentions the player by name. */
    public boolean stopOnMention = true;
    /** Hard ceiling on one run, in minutes, whatever else is configured. 0 disables. */
    public int maxRunMinutes = 180;

    // ------------------------------------------------------------------ pilot

    /** Default travel mode: walk, fly, or auto (fly when flight is available, else walk). */
    public String travelMode = "auto";
    /** How high above the terrain to cruise when flying. */
    public int cruiseHeight = 12;
    /** Give up travelling after this long, in seconds. */
    public int travelTimeoutSeconds = 300;
    /** Treat the player as arrived within this many blocks of the target. */
    public double arriveWithin = 1.5;
    /**
     * Allow elytra travel. Off by default and honestly experimental: a glide that goes wrong
     * costs the gear and the inventory, and walking is what a careful player would do.
     */
    public boolean allowElytra = false;

    // -------------------------------------------------------------------- hud

    /**
     * Show status on the action bar while something runs. This is why the mod costs no
     * frames: there is no custom rendering anywhere, only the line vanilla already draws.
     */
    public boolean showStatus = true;
    /** How often to refresh that line, in ticks. */
    public int statusEveryTicks = 20;
    /** Echo starts, stops and guard trips into chat. */
    public boolean logToChat = true;

    // --------------------------------------------------------------- plumbing

    public static synchronized Config get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        Path file = Workspace.configFile();
        Config cfg = null;
        try {
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
            }
        } catch (Exception e) {
            AutopilotClient.LOG.warn("config.json unreadable, falling back to defaults", e);
        }
        if (cfg == null) cfg = new Config();
        if (cfg.bridgeToken == null || cfg.bridgeToken.isBlank()) {
            cfg.bridgeToken = UUID.randomUUID().toString().replace("-", "");
        }
        instance = cfg;
        save();
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Workspace.ensure();
            Files.writeString(Workspace.configFile(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("could not write config.json", e);
        }
    }
}
