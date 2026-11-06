package com.omeldar.autopilot.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.omeldar.autopilot.ClientQueue;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Scheduler;
import com.omeldar.autopilot.farm.Farm;
import com.omeldar.autopilot.farm.FarmBook;
import com.omeldar.autopilot.farm.FarmRunner;
import com.omeldar.autopilot.pilot.Pilot;
import com.omeldar.autopilot.record.Macro;
import com.omeldar.autopilot.record.MacroStore;
import com.omeldar.autopilot.record.Recorder;
import com.omeldar.autopilot.replay.Clicker;
import com.omeldar.autopilot.replay.Replayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;
import java.util.Locale;

/**
 * The tools a Claude session gets.
 *
 * <p>Two design rules run through all of it. First, every tool answers in words a model can
 * act on: a refusal says what is wrong and what to do instead, because "false" sends it round
 * the loop guessing. Second, nothing here is a new ability - each tool is something the
 * player could do with a command, so a session driving the mod is doing the same things from
 * the other side of the same door.
 *
 * <p>The interesting tool is {@code farm_start}. "Fly to the sugarcane farm, land, and start
 * it" is one call, because the knowledge of where that farm is and how it starts lives in the
 * player's farm book rather than in the model.
 */
public final class Mcp {
    private static final Gson GSON = new Gson();

    private Mcp() {}

    // ------------------------------------------------------------------- tools

    public static JsonArray toolList() {
        JsonArray tools = new JsonArray();

        tools.add(tool("get_status",
                "What the player and the mod are doing right now: position, dimension, health, "
              + "food, whether flight is available, what task is running and what stopped the "
              + "last one. Call this first; most refusals are explained by it.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("list_macros",
                "Every recorded macro: name, length, whether it loops, and the anchor it has "
              + "to be run from. A macro is a replay of the player's own key presses.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("list_farms",
                "The farm book: the places this world knows about and the macros that run "
              + "them. This is the only place that knows a farm exists, so read it before "
              + "offering to start one.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("play_macro",
                "Replay a macro from where the player is standing. Refuses unless they are on "
              + "the macro's anchor, and refuses to loop a macro that does not end where it "
              + "started. loops 0 means until a guard stops it.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"macro\":{\"type\":\"string\"},"
              + "\"loops\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10000,"
              + "\"description\":\"0 for until something stops it, default 1\"}},"
              + "\"required\":[\"macro\"]}"));

        tools.add(tool("farm_start",
                "Go to a farm and run it: travel to its approach point, walk onto the working "
              + "block, run its start macro once if it has one, then loop its run macro. One "
              + "call for the whole errand. Everything stops on the first thing that goes "
              + "wrong, and the reason comes back from get_status.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"farm\":{\"type\":\"string\",\"description\":\"id from list_farms\"},"
              + "\"loops\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10000,"
              + "\"description\":\"0 for until a guard stops it, which is the usual case\"},"
              + "\"travel\":{\"type\":\"string\",\"enum\":[\"auto\",\"walk\",\"fly\",\"elytra\"]}},"
              + "\"required\":[\"farm\"]}"));

        tools.add(tool("travel_to",
                "Walk or fly the player to a coordinate. Walking does not dig, bridge or cross "
              + "gaps - it stops and says so instead. Flying is creative flight. Elytra only "
              + "steers a glide the player already started.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},"
              + "\"mode\":{\"type\":\"string\",\"enum\":[\"auto\",\"walk\",\"fly\",\"elytra\"]},"
              + "\"label\":{\"type\":\"string\",\"description\":\"what to call it in the status line\"}},"
              + "\"required\":[\"x\",\"y\",\"z\"]}"));

        tools.add(tool("click",
                "Press one button on a fixed interval, or hold it down. The plain auto-clicker, "
              + "for when a recorded macro is more than the job needs. Capped at 20 a second "
              + "because the game ticks 20 times a second.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"button\":{\"type\":\"string\",\"enum\":[\"attack\",\"use\",\"jump\",\"sneak\",\"drop\"]},"
              + "\"cps\":{\"type\":\"number\",\"minimum\":0.5,\"maximum\":20},"
              + "\"hold\":{\"type\":\"boolean\",\"description\":\"hold it down instead of tapping\"},"
              + "\"seconds\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"0 for no limit of its own\"}},"
              + "\"required\":[\"button\"]}"));

        tools.add(tool("stop",
                "Stop whatever the mod is doing and let go of every key. Always safe to call.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("record_start",
                "Start recording the player's key presses under a name. They then do the thing "
              + "by hand once; record_stop saves it. The mod cannot invent a macro - it can "
              + "only replay one a human performed.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"name\":{\"type\":\"string\"},"
              + "\"note\":{\"type\":\"string\",\"description\":\"what this macro is for\"}},"
              + "\"required\":[\"name\"]}"));

        tools.add(tool("record_stop",
                "Stop recording and save. Reports whether the result loops, which decides "
              + "whether it can be left running.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        tools.add(tool("farm_note",
                "Add or update a farm in the book from where the player is standing right now, "
              + "or set its macros and notes. Use this to write down a farm the player has "
              + "just shown you, so the next session can start it by name.",
                "{\"type\":\"object\",\"properties\":{"
              + "\"farm\":{\"type\":\"string\"},"
              + "\"name\":{\"type\":\"string\"},"
              + "\"useCurrentPosition\":{\"type\":\"boolean\",\"description\":\"take the station from where the player stands\"},"
              + "\"runMacro\":{\"type\":\"string\"},"
              + "\"startMacro\":{\"type\":\"string\"},"
              + "\"stopMacro\":{\"type\":\"string\"},"
              + "\"yields\":{\"type\":\"string\"},"
              + "\"notes\":{\"type\":\"string\"}},"
              + "\"required\":[\"farm\"]}"));

        return tools;
    }

    private static JsonObject tool(String name, String description, String schema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", JsonParser.parseString(schema).getAsJsonObject());
        return t;
    }

    // ---------------------------------------------------------------- dispatch

    public static String call(String name, JsonObject args) throws Exception {
        return switch (name) {
            case "get_status" -> status();
            case "list_macros" -> listMacros();
            case "list_farms" -> listFarms();
            case "play_macro" -> playMacro(args);
            case "farm_start" -> farmStart(args);
            case "travel_to" -> travelTo(args);
            case "click" -> click(args);
            case "stop" -> stop();
            case "record_start" -> recordStart(args);
            case "record_stop" -> recordStop();
            case "farm_note" -> farmNote(args);
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
    }

    // ------------------------------------------------------------------- impls

    private static String status() throws Exception {
        return ClientQueue.on(mc -> {
            JsonObject o = new JsonObject();
            ClientPlayerEntity p = mc.player;
            o.addProperty("inWorld", p != null && mc.world != null);
            if (p == null || mc.world == null) {
                o.addProperty("note", "The player is not in a world. Nothing can run yet.");
                return GSON.toJson(o);
            }
            o.addProperty("player", p.getGameProfile().name());
            o.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
            JsonObject at = new JsonObject();
            at.addProperty("x", round(p.getX()));
            at.addProperty("y", round(p.getY()));
            at.addProperty("z", round(p.getZ()));
            at.addProperty("yaw", round(p.getYaw()));
            at.addProperty("pitch", round(p.getPitch()));
            o.add("position", at);
            o.addProperty("health", round(p.getHealth()));
            o.addProperty("food", p.getHungerManager().getFoodLevel());
            o.addProperty("onGround", p.isOnGround());
            o.addProperty("gliding", p.isGliding());
            o.addProperty("canFly", p.getAbilities().allowFlying);
            o.addProperty("flying", p.getAbilities().flying);
            o.addProperty("emptyInventorySlots", countEmpty(mc));
            o.addProperty("heldItem", p.getMainHandStack().isEmpty()
                    ? "empty" : p.getMainHandStack().getName().getString());

            o.addProperty("recording", Recorder.recording());
            if (Recorder.recording()) {
                o.addProperty("recordingName", Recorder.recordingName());
                o.addProperty("recordingSeconds", Recorder.ticks() / 20);
            }
            o.addProperty("busy", Scheduler.busy());
            if (Scheduler.busy()) {
                o.addProperty("task", Scheduler.activeName());
                o.addProperty("taskStatus", Scheduler.status());
                o.addProperty("taskSeconds", Scheduler.secondsRunning());
            } else {
                o.addProperty("lastTask", Scheduler.lastTaskName());
                o.addProperty("lastOutcome", Scheduler.lastOutcome());
            }
            JsonArray held = new JsonArray();
            for (String key : Keys.heldNames()) held.add(key);
            o.add("keysHeldByMod", held);

            o.addProperty("note", "A run stops by itself on damage, low health or food, a full "
                    + "inventory, a worn tool, an open screen, chat saying the player's name, "
                    + "or the player touching a movement key. lastOutcome says which it was.");
            return GSON.toJson(o);
        });
    }

    private static String listMacros() {
        List<Macro> all = MacroStore.all();
        JsonArray list = new JsonArray();
        for (Macro m : all) list.add(m.summary());
        JsonObject o = new JsonObject();
        o.addProperty("count", all.size());
        o.add("macros", list);
        if (all.isEmpty()) {
            o.addProperty("note", "Nothing recorded yet. A macro can only be made by the "
                    + "player performing it once: ask them to stand where the run should "
                    + "start, then call record_start.");
        } else {
            o.addProperty("note", "A macro only works from its anchor. Check the player's "
                    + "position against it, and travel there first if it does not match.");
        }
        return GSON.toJson(o);
    }

    private static String listFarms() {
        List<Farm> all = FarmBook.all();
        JsonArray list = new JsonArray();
        for (Farm f : all) list.add(f.toJson());
        JsonObject o = new JsonObject();
        o.addProperty("count", all.size());
        o.add("farms", list);
        o.addProperty("file", com.omeldar.autopilot.Workspace.farmsFile().toString());
        if (all.isEmpty()) {
            o.addProperty("note", "The farm book is empty. Ask the player to stand at a farm "
                    + "and use farm_note, or to run /ap farm add <id> there.");
        }
        return GSON.toJson(o);
    }

    private static String playMacro(JsonObject args) throws Exception {
        String name = str(args, "macro", "");
        int loops = args.has("loops") ? args.get("loops").getAsInt() : 1;
        Macro macro;
        try {
            macro = MacroStore.load(name);
        } catch (Exception e) {
            return refusal("no macro called " + name
                    + ". Call list_macros to see what exists.");
        }
        return ClientQueue.on(mc -> {
            String no = Scheduler.begin(mc, new Replayer(macro, loops));
            return no == null
                    ? ok("playing " + macro.name + (loops == 0 ? " on repeat" : " x" + loops)
                        + ". Poll get_status; it will stop by itself and say why.")
                    : refusal(no);
        });
    }

    private static String farmStart(JsonObject args) throws Exception {
        String id = str(args, "farm", "");
        Farm farm = FarmBook.get(id);
        if (farm == null) {
            return refusal("no farm called " + id + " in the book. Call list_farms.");
        }
        int loops = args.has("loops") ? args.get("loops").getAsInt() : 0;
        Pilot.Mode mode = mode(str(args, "travel", "auto"));
        if (mode == null) return refusal("travel must be auto, walk, fly or elytra");
        return ClientQueue.on(mc -> {
            String no = Scheduler.begin(mc, new FarmRunner(farm, loops, mode));
            return no == null
                    ? ok("heading to " + farm.id + ". It will travel, walk onto the station "
                        + "and start running. Poll get_status for progress and for the reason "
                        + "it stops.")
                    : refusal(no);
        });
    }

    private static String travelTo(JsonObject args) throws Exception {
        double x = args.get("x").getAsDouble();
        double y = args.get("y").getAsDouble();
        double z = args.get("z").getAsDouble();
        Pilot.Mode mode = mode(str(args, "mode", "auto"));
        if (mode == null) return refusal("mode must be auto, walk, fly or elytra");
        String label = str(args, "label", "");
        return ClientQueue.on(mc -> {
            String no = Scheduler.begin(mc, new Pilot(x, y, z, mode, label));
            return no == null
                    ? ok("on the way. Poll get_status; walking gives up honestly rather than "
                        + "digging or bridging.")
                    : refusal(no);
        });
    }

    private static String click(JsonObject args) throws Exception {
        String button = str(args, "button", "attack");
        int bit = switch (button.toLowerCase(Locale.ROOT)) {
            case "attack" -> Keys.ATTACK;
            case "use" -> Keys.USE;
            case "jump" -> Keys.JUMP;
            case "sneak" -> Keys.SNEAK;
            case "drop" -> Keys.DROP;
            default -> -1;
        };
        if (bit < 0) return refusal("button must be attack, use, jump, sneak or drop");
        double cps = args.has("cps") ? args.get("cps").getAsDouble() : 5;
        boolean hold = args.has("hold") && args.get("hold").getAsBoolean();
        int seconds = args.has("seconds") ? args.get("seconds").getAsInt() : 0;
        return ClientQueue.on(mc -> {
            String no = Scheduler.begin(mc, new Clicker(bit, cps, hold, seconds));
            return no == null ? ok((hold ? "holding " : "clicking ") + button) : refusal(no);
        });
    }

    private static String stop() throws Exception {
        return ClientQueue.on(mc -> {
            if (Recorder.recording()) {
                Macro saved = Recorder.stop(mc);
                return ok(saved == null ? "recording discarded" : "saved " + saved.name);
            }
            if (!Scheduler.busy()) return ok("nothing was running");
            String was = Scheduler.activeName();
            Scheduler.end(mc, "stopped from the bridge");
            return ok("stopped " + was);
        });
    }

    private static String recordStart(JsonObject args) throws Exception {
        String name = str(args, "name", "");
        String note = str(args, "note", "");
        return ClientQueue.on(mc -> {
            String no = Recorder.start(mc, name, note);
            return no == null
                    ? ok("recording. The player should now do the run once by hand, finishing "
                        + "on the block they started from so it can be looped, then call "
                        + "record_stop.")
                    : refusal(no);
        });
    }

    private static String recordStop() throws Exception {
        return ClientQueue.on(mc -> {
            if (!Recorder.recording()) return refusal("not recording");
            Macro saved = Recorder.stop(mc);
            if (saved == null) return refusal("the recording could not be saved");
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.add("macro", saved.summary());
            o.addProperty("note", saved.loopable
                    ? "It ends where it started, so it can be looped."
                    : "It does not end where it started, so it can only be run once. To loop "
                      + "it, record it again finishing on the starting block.");
            return GSON.toJson(o);
        });
    }

    private static String farmNote(JsonObject args) throws Exception {
        String id = str(args, "farm", "").trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return refusal("farm id is required");
        return ClientQueue.on(mc -> {
            Farm f = FarmBook.get(id);
            boolean isNew = f == null;
            if (isNew) {
                f = new Farm();
                f.id = id;
                f.name = id;
            }
            if (args.has("name")) f.name = args.get("name").getAsString();
            if (args.has("runMacro")) f.runMacro = args.get("runMacro").getAsString();
            if (args.has("startMacro")) f.startMacro = args.get("startMacro").getAsString();
            if (args.has("stopMacro")) f.stopMacro = args.get("stopMacro").getAsString();
            if (args.has("yields")) f.yields = args.get("yields").getAsString();
            if (args.has("notes")) f.notes = args.get("notes").getAsString();

            boolean here = args.has("useCurrentPosition")
                    && args.get("useCurrentPosition").getAsBoolean();
            if (here || isNew) {
                ClientPlayerEntity p = mc.player;
                if (p == null || mc.world == null) {
                    return refusal("the player is not in a world, so there is no position to "
                            + "write down. Set the station by hand in farms.json, or ask them "
                            + "to stand at the farm.");
                }
                f.dimension = mc.world.getRegistryKey().getValue().toString();
                f.station = new Farm.Spot(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
                if (f.approach == null) {
                    f.approach = new Farm.Spot(p.getX(), p.getY(), p.getZ(),
                            p.getYaw(), p.getPitch());
                }
            }
            FarmBook.put(f);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.add("farm", f.toJson());
            o.addProperty("note", isNew
                    ? "Added. It needs a runMacro before farm_start will do anything."
                    : "Updated.");
            return GSON.toJson(o);
        });
    }

    // ---------------------------------------------------------------- plumbing

    private static Pilot.Mode mode(String raw) {
        try {
            return Pilot.Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static int countEmpty(MinecraftClient mc) {
        if (mc.player == null) return 0;
        int free = 0;
        var inv = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inv.getStack(slot).isEmpty()) free++;
        }
        return free;
    }

    private static String ok(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return GSON.toJson(o);
    }

    /**
     * A refusal says what is wrong, in a sentence. A bare false sends a model round the loop
     * trying variations, which with a mod that presses keys is the last thing anyone wants.
     */
    private static String refusal(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("refused", why);
        return GSON.toJson(o);
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
