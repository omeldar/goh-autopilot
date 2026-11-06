package com.omeldar.autopilot.farm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.omeldar.autopilot.AutopilotClient;
import com.omeldar.autopilot.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The farm book, at {@code config/autopilot/farms.json}: one file, hand-editable, reloadable
 * in game. Written as a plain list rather than a database because the useful thing to be able
 * to do with it is read it, and paste it to someone.
 */
public final class FarmBook {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Farm> FARMS = new LinkedHashMap<>();
    private static boolean loaded;

    private FarmBook() {}

    public static synchronized void reload() {
        FARMS.clear();
        loaded = true;
        try {
            if (!Files.isRegularFile(Workspace.farmsFile())) {
                writeExample();
                return;
            }
            String body = Files.readString(Workspace.farmsFile(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray list = root.has("farms") ? root.getAsJsonArray("farms") : new JsonArray();
            for (var el : list) {
                Farm farm = GSON.fromJson(el, Farm.class);
                if (farm == null || farm.id == null || farm.id.isBlank()) continue;
                farm.id = farm.id.trim().toLowerCase(Locale.ROOT);
                FARMS.put(farm.id, farm);
            }
            AutopilotClient.LOG.info("farm book: {} entries", FARMS.size());
        } catch (Exception e) {
            AutopilotClient.LOG.warn("farms.json unreadable - no farms loaded", e);
        }
    }

    public static synchronized List<Farm> all() {
        if (!loaded) reload();
        return new ArrayList<>(FARMS.values());
    }

    public static synchronized Farm get(String id) {
        if (!loaded) reload();
        return id == null ? null : FARMS.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static synchronized void put(Farm farm) {
        if (!loaded) reload();
        FARMS.put(farm.id.trim().toLowerCase(Locale.ROOT), farm);
        save();
    }

    public static synchronized boolean remove(String id) {
        if (!loaded) reload();
        boolean gone = FARMS.remove(id.trim().toLowerCase(Locale.ROOT)) != null;
        if (gone) save();
        return gone;
    }

    public static synchronized void save() {
        try {
            Workspace.ensure();
            JsonArray list = new JsonArray();
            for (Farm f : FARMS.values()) list.add(f.toJson());
            JsonObject root = new JsonObject();
            root.addProperty("_comment",
                    "Farms this world knows about. approach is somewhere safe to arrive; "
                  + "station is the exact block to stand on and should match the anchor of "
                  + "runMacro. Hand-edit freely, then /ap farms reload.");
            root.add("farms", list);
            Files.writeString(Workspace.farmsFile(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("could not write farms.json", e);
        }
    }

    /** First run: write a commented example rather than an empty file nobody can decode. */
    private static void writeExample() {
        try {
            Workspace.ensure();
            Farm example = new Farm();
            example.id = "example";
            example.name = "Example - delete me";
            example.dimension = "minecraft:overworld";
            example.approach = new Farm.Spot(0, 70, 0, 0, 0);
            example.station = new Farm.Spot(0, 70, 0, 0, 0);
            example.runMacro = "";
            example.notes = "Stand at the farm, run /ap farm add <id>, and this file is "
                          + "filled in from where you are standing.";
            FARMS.put(example.id, example);
            save();
        } catch (Exception e) {
            AutopilotClient.LOG.warn("could not write the example farm", e);
        }
    }
}
