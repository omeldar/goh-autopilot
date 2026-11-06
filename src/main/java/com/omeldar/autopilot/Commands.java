package com.omeldar.autopilot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.omeldar.autopilot.farm.Farm;
import com.omeldar.autopilot.farm.FarmBook;
import com.omeldar.autopilot.farm.FarmRunner;
import com.omeldar.autopilot.pilot.Pilot;
import com.omeldar.autopilot.record.Macro;
import com.omeldar.autopilot.record.MacroStore;
import com.omeldar.autopilot.record.Recorder;
import com.omeldar.autopilot.replay.Clicker;
import com.omeldar.autopilot.replay.Replayer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * The in-game commands. Client-side only, so none of this is sent to the server and none of
 * it needs a permission there. {@code /ap} on its own says what is going on.
 */
public final class Commands {
    private Commands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var root = literal("autopilot")
                    .executes(ctx -> status())

                    // ---------------------------------------------------------- recording
                    .then(literal("rec")
                            .then(literal("stop").executes(ctx -> stopRecording()))
                            .then(literal("cancel").executes(ctx -> {
                                Recorder.cancel();
                                return 1;
                            }))
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> record(
                                            StringArgumentType.getString(ctx, "name"), ""))
                                    .then(argument("note", StringArgumentType.greedyString())
                                            .executes(ctx -> record(
                                                    StringArgumentType.getString(ctx, "name"),
                                                    StringArgumentType.getString(ctx, "note"))))))

                    // ------------------------------------------------------------- replay
                    .then(literal("play")
                            .then(argument("macro", StringArgumentType.word())
                                    .executes(ctx -> play(
                                            StringArgumentType.getString(ctx, "macro"), 1))
                                    .then(argument("loops", IntegerArgumentType.integer(0, 10000))
                                            .executes(ctx -> play(
                                                    StringArgumentType.getString(ctx, "macro"),
                                                    IntegerArgumentType.getInteger(ctx, "loops"))))))
                    .then(literal("loop")
                            .then(argument("macro", StringArgumentType.word())
                                    .executes(ctx -> play(
                                            StringArgumentType.getString(ctx, "macro"), 0))))
                    .then(literal("stop").executes(ctx -> stopEverything()))

                    // ------------------------------------------------------------ macros
                    .then(literal("list").executes(ctx -> listMacros()))
                    .then(literal("del")
                            .then(argument("macro", StringArgumentType.word())
                                    .executes(ctx -> deleteMacro(
                                            StringArgumentType.getString(ctx, "macro")))))

                    // ------------------------------------------------------- auto-clicker
                    .then(literal("click")
                            .then(argument("cps", DoubleArgumentType.doubleArg(0.5, 20))
                                    .executes(ctx -> click(
                                            DoubleArgumentType.getDouble(ctx, "cps"),
                                            "attack", 0))
                                    .then(argument("button", StringArgumentType.word())
                                            .executes(ctx -> click(
                                                    DoubleArgumentType.getDouble(ctx, "cps"),
                                                    StringArgumentType.getString(ctx, "button"), 0))
                                            .then(argument("seconds",
                                                        IntegerArgumentType.integer(1, 86400))
                                                    .executes(ctx -> click(
                                                            DoubleArgumentType.getDouble(ctx, "cps"),
                                                            StringArgumentType.getString(ctx, "button"),
                                                            IntegerArgumentType.getInteger(ctx, "seconds")))))))
                    .then(literal("hold")
                            .then(argument("button", StringArgumentType.word())
                                    .executes(ctx -> hold(
                                            StringArgumentType.getString(ctx, "button"), 0))
                                    .then(argument("seconds", IntegerArgumentType.integer(1, 86400))
                                            .executes(ctx -> hold(
                                                    StringArgumentType.getString(ctx, "button"),
                                                    IntegerArgumentType.getInteger(ctx, "seconds"))))))

                    // ------------------------------------------------------------ travel
                    .then(literal("goto")
                            .then(argument("x", DoubleArgumentType.doubleArg())
                                    .then(argument("y", DoubleArgumentType.doubleArg())
                                            .then(argument("z", DoubleArgumentType.doubleArg())
                                                    .executes(ctx -> travel(ctx, "auto"))
                                                    .then(argument("mode", StringArgumentType.word())
                                                            .executes(ctx -> travel(ctx,
                                                                    StringArgumentType.getString(ctx, "mode"))))))))

                    // -------------------------------------------------------------- farms
                    .then(literal("farm")
                            .then(literal("list").executes(ctx -> listFarms()))
                            .then(literal("reload").executes(ctx -> {
                                FarmBook.reload();
                                Chat.info("farm book reloaded: " + FarmBook.all().size() + " entries");
                                return 1;
                            }))
                            .then(literal("add")
                                    .then(argument("id", StringArgumentType.word())
                                            .executes(ctx -> addFarm(
                                                    StringArgumentType.getString(ctx, "id")))))
                            .then(literal("info")
                                    .then(argument("id", StringArgumentType.word())
                                            .executes(ctx -> farmInfo(
                                                    StringArgumentType.getString(ctx, "id")))))
                            .then(literal("start")
                                    .then(argument("id", StringArgumentType.word())
                                            .executes(ctx -> startFarm(
                                                    StringArgumentType.getString(ctx, "id"), 0)))))

                    // ------------------------------------------------------------- bridge
                    .then(literal("bridge").executes(ctx -> bridgeInfo()))
                    .then(literal("guards").executes(ctx -> guards()));

            var node = dispatcher.register(root);
            // /ap is the one people will actually type.
            dispatcher.register(literal("ap").redirect(node));
        });
    }

    // ------------------------------------------------------------------ actions

    private static int status() {
        if (Recorder.recording()) {
            Chat.info("recording " + Recorder.recordingName()
                    + " (" + Recorder.ticks() / 20 + "s)");
        }
        if (Scheduler.busy()) {
            Chat.info(Scheduler.status() + "  -  End or /ap stop to stop");
        } else {
            Chat.info("idle. last: " + Scheduler.lastTaskName()
                    + (Scheduler.lastOutcome().isBlank() ? "" : " - " + Scheduler.lastOutcome()));
        }
        Chat.info(MacroStore.all().size() + " macros, " + FarmBook.all().size() + " farms. "
                + "/ap rec <name> to record, /ap list to see them.");
        return 1;
    }

    private static int record(String name, String note) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String refusal = Recorder.start(mc, name, note);
        if (refusal != null) Chat.warn(refusal);
        return 1;
    }

    private static int stopRecording() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Recorder.recording()) {
            Chat.warn("not recording");
            return 1;
        }
        Recorder.stop(mc);
        return 1;
    }

    private static int stopEverything() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (Recorder.recording()) {
            Recorder.stop(mc);
            return 1;
        }
        if (!Scheduler.busy()) {
            Chat.info("nothing running");
            return 1;
        }
        Scheduler.end(mc, "stopped by command");
        return 1;
    }

    private static int play(String name, int loops) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            Macro macro = MacroStore.load(name);
            String refusal = Scheduler.begin(mc, new Replayer(macro, loops));
            if (refusal != null) Chat.warn(refusal);
        } catch (Exception e) {
            Chat.warn(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return 1;
    }

    private static int listMacros() {
        List<Macro> all = MacroStore.all();
        if (all.isEmpty()) {
            Chat.info("no macros yet. Stand where a farm run should start and /ap rec <name>");
            return 1;
        }
        Chat.info(all.size() + " macro" + (all.size() == 1 ? "" : "s") + ":");
        for (Macro m : all) {
            Chat.info(String.format(Locale.ROOT, "  %s  %.1fs  %s%s",
                    m.name, m.lengthTicks / 20.0,
                    m.loopable ? "loopable" : "one-shot",
                    m.note == null || m.note.isBlank() ? "" : "  - " + m.note));
        }
        return 1;
    }

    private static int deleteMacro(String name) {
        Chat.info(MacroStore.delete(name) ? "deleted " + name : "no macro called " + name);
        return 1;
    }

    private static int click(double cps, String button, int seconds) {
        int bit = buttonBit(button);
        if (bit < 0) return 1;
        MinecraftClient mc = MinecraftClient.getInstance();
        String refusal = Scheduler.begin(mc, new Clicker(bit, cps, false, seconds));
        if (refusal != null) Chat.warn(refusal);
        return 1;
    }

    private static int hold(String button, int seconds) {
        int bit = buttonBit(button);
        if (bit < 0) return 1;
        MinecraftClient mc = MinecraftClient.getInstance();
        String refusal = Scheduler.begin(mc, new Clicker(bit, 1, true, seconds));
        if (refusal != null) Chat.warn(refusal);
        return 1;
    }

    private static int buttonBit(String button) {
        return switch (button.toLowerCase(Locale.ROOT)) {
            case "attack", "left", "mine", "hit" -> Keys.ATTACK;
            case "use", "right", "place", "interact" -> Keys.USE;
            case "jump" -> Keys.JUMP;
            case "sneak", "shift" -> Keys.SNEAK;
            case "drop" -> Keys.DROP;
            default -> {
                Chat.warn("button must be attack, use, jump, sneak or drop");
                yield -1;
            }
        };
    }

    private static int travel(com.mojang.brigadier.context.CommandContext<?> ctx, String mode) {
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        Pilot.Mode m;
        try {
            m = Pilot.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            Chat.warn("mode must be auto, walk, fly or elytra");
            return 1;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        String refusal = Scheduler.begin(mc, new Pilot(x, y, z, m, null));
        if (refusal != null) Chat.warn(refusal);
        return 1;
    }

    private static int listFarms() {
        List<Farm> all = FarmBook.all();
        if (all.isEmpty()) {
            Chat.info("no farms yet. Stand at one and /ap farm add <id>");
            return 1;
        }
        Chat.info(all.size() + " farm" + (all.size() == 1 ? "" : "s") + ":");
        for (Farm f : all) {
            Chat.info("  " + f.id + "  " + (f.runMacro == null || f.runMacro.isBlank()
                    ? "(no run macro)" : "runs " + f.runMacro)
                    + (f.yields == null || f.yields.isBlank() ? "" : "  - " + f.yields));
        }
        return 1;
    }

    private static int farmInfo(String id) {
        Farm f = FarmBook.get(id);
        if (f == null) {
            Chat.warn("no farm called " + id);
            return 1;
        }
        Chat.info(f.id + ": " + f.name);
        if (f.station != null) {
            Chat.info(String.format(Locale.ROOT, "  station %.1f %.1f %.1f",
                    f.station.x, f.station.y, f.station.z));
        }
        Chat.info("  macros: start=" + nz(f.startMacro) + " run=" + nz(f.runMacro)
                + " stop=" + nz(f.stopMacro));
        if (f.notes != null && !f.notes.isBlank()) Chat.info("  " + f.notes);
        return 1;
    }

    private static int addFarm(String id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            Chat.warn("not in a world");
            return 1;
        }
        Farm f = FarmBook.get(id);
        boolean isNew = f == null;
        if (isNew) {
            f = new Farm();
            f.id = id.toLowerCase(Locale.ROOT);
            f.name = id;
        }
        f.dimension = mc.world.getRegistryKey().getValue().toString();
        f.station = new Farm.Spot(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        if (f.approach == null) {
            f.approach = new Farm.Spot(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        }
        FarmBook.put(f);
        Chat.good((isNew ? "added " : "updated ") + f.id + " at this spot. "
                + "Now set runMacro in farms.json, or record one and point it at this name.");
        return 1;
    }

    private static int startFarm(String id, int loops) {
        Farm f = FarmBook.get(id);
        if (f == null) {
            Chat.warn("no farm called " + id + ". /ap farm list");
            return 1;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        String refusal = Scheduler.begin(mc, new FarmRunner(f, loops, Pilot.Mode.AUTO));
        if (refusal != null) Chat.warn(refusal);
        return 1;
    }

    private static int bridgeInfo() {
        Config cfg = Config.get();
        if (!cfg.bridgeEnabled) {
            Chat.info("the bridge is off (bridgeEnabled in config.json)");
            return 1;
        }
        Chat.info("bridge on http://127.0.0.1:" + cfg.bridgePort + "/mcp");
        Chat.info("token and the mcp snippet are in " + Workspace.bridgeFile());
        Chat.info("Lumi picks it up from " + Workspace.lumiLinkDir() + " by itself");
        return 1;
    }

    private static int guards() {
        Config c = Config.get();
        Chat.info("a run stops when:");
        if (c.stopOnDamage) Chat.info("  you take any damage");
        if (c.minHealth > 0) Chat.info("  health drops below " + c.minHealth);
        if (c.minFood > 0) Chat.info("  food drops below " + c.minFood);
        if (c.stopOnInventoryFull) Chat.info("  the inventory fills up");
        if (c.minToolDurability > 0) {
            Chat.info("  the held tool falls to " + (int) (c.minToolDurability * 100) + "%");
        }
        if (c.stopOnPlayerWithin > 0) {
            Chat.info("  a player comes within " + (int) c.stopOnPlayerWithin + " blocks");
        }
        if (c.stopOnScreen) Chat.info("  any screen opens");
        if (c.stopOnMention) Chat.info("  chat says your name");
        if (c.stopOnDimensionChange) Chat.info("  the dimension changes");
        if (c.maxRunMinutes > 0) Chat.info("  " + c.maxRunMinutes + " minutes have passed");
        Chat.info("  you touch a movement key, or press End");
        return 1;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
