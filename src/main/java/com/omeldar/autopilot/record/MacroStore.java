package com.omeldar.autopilot.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.omeldar.autopilot.AutopilotClient;
import com.omeldar.autopilot.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Reads and writes macros as one file each, under {@code config/autopilot/macros/}. */
public final class MacroStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MacroStore() {}

    /**
     * Macro names end up in a file path, so they are restricted to something that cannot
     * climb out of the directory. The bridge takes a name from a model, and a model that
     * has been told the wrong thing should not be able to write to the home directory.
     */
    public static String sanitise(String name) {
        if (name == null) return "";
        String clean = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        while (clean.startsWith(".") || clean.startsWith("-")) clean = clean.substring(1);
        if (clean.length() > 64) clean = clean.substring(0, 64);
        return clean;
    }

    private static Path file(String name) {
        return Workspace.macros().resolve(name + ".json");
    }

    public static boolean exists(String name) {
        String clean = sanitise(name);
        return !clean.isEmpty() && Files.isRegularFile(file(clean));
    }

    public static void save(Macro macro) throws Exception {
        Workspace.ensure();
        macro.name = sanitise(macro.name);
        if (macro.name.isEmpty()) throw new IllegalArgumentException("empty macro name");
        Files.writeString(file(macro.name), GSON.toJson(macro.toJson()), StandardCharsets.UTF_8);
    }

    public static Macro load(String name) throws Exception {
        String clean = sanitise(name);
        Path path = file(clean);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("no macro called " + clean);
        JsonObject o = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        Macro m = Macro.fromJson(o);
        m.name = clean;
        return m;
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(sanitise(name)));
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Macro> all() {
        List<Macro> out = new ArrayList<>();
        Path dir = Workspace.macros();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    out.add(load(name.substring(0, name.length() - 5)));
                } catch (Exception e) {
                    AutopilotClient.LOG.warn("skipping unreadable macro {}", p, e);
                }
            });
        } catch (Exception e) {
            AutopilotClient.LOG.warn("could not list macros", e);
        }
        return out;
    }
}
