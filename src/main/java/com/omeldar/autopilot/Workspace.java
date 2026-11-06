package com.omeldar.autopilot;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the mod keeps its files. Everything lives under {@code config/autopilot/} so the
 * whole thing can be backed up, diffed or hand-edited as plain text.
 */
public final class Workspace {
    private Workspace() {}

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("autopilot");
    }

    /** One file per recorded macro. */
    public static Path macros() {
        return root().resolve("macros");
    }

    /** The farm book: named places, with the macros that run them. */
    public static Path farmsFile() {
        return root().resolve("farms.json");
    }

    public static Path configFile() {
        return root().resolve("config.json");
    }

    /** Url and token for the local bridge, for a Claude session to pick up. */
    public static Path bridgeFile() {
        return root().resolve("bridge.json");
    }

    /**
     * The directory companion mods drop their descriptors into so Lumi can find them.
     * Shared between mods on purpose: it is the whole discovery mechanism.
     */
    public static Path lumiLinkDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumi-link");
    }

    public static void ensure() {
        try {
            Files.createDirectories(macros());
            Files.createDirectories(lumiLinkDir());
        } catch (IOException e) {
            AutopilotClient.LOG.warn("could not create {}", root(), e);
        }
    }
}
