package com.omeldar.autopilot.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.omeldar.autopilot.AutopilotClient;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lumi Link: how a companion mod tells Lumi it exists.
 *
 * <p>The problem it solves: Lumi already runs an MCP server that a Claude Code session talks
 * to. Every companion mod could run its own and be registered by hand, but then the player
 * maintains a list of ports in two places and a session started anywhere else sees none of
 * them. What is wanted is for Lumi to hand the session every tool in the pack.
 *
 * <p>The obvious way to do that is a shared Java interface the companion implements and Lumi
 * looks up. That was rejected: it makes every companion compile against a particular Lumi
 * version, and the first time one of the two updates the other stops loading - the one thing
 * a mod pack cannot afford.
 *
 * <p>So the link is a file and an HTTP call instead. The companion writes a descriptor into
 * {@code config/lumi-link/}, saying what it is called, where its MCP endpoint is and what
 * token to use. Lumi reads the directory, health-checks whatever it finds and proxies those
 * tools under a prefix. Nothing is compiled against anything; a version mismatch is at worst
 * a stale file that fails a health check and is skipped. And with Lumi absent the mod is
 * exactly as useful on its own, because the descriptor is just a file nobody read.
 *
 * <p>Protocol version 1. Fields may be added; existing ones will not change meaning.
 */
public final class LumiLink {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int VERSION = 1;

    private LumiLink() {}

    public static void announce() {
        Config cfg = Config.get();
        try {
            Workspace.ensure();
            JsonObject d = new JsonObject();
            d.addProperty("lumiLink", VERSION);
            d.addProperty("modId", "autopilot");
            d.addProperty("name", "Autopilot");
            d.addProperty("version", versionOf());
            d.addProperty("summary",
                    "Records the player's key presses and replays them on a loop, travels to "
                  + "coordinates, and runs the farms in the player's farm book.");
            // The prefix Lumi namespaces these tools under, so two companions cannot both
            // claim a name like get_status.
            d.addProperty("prefix", "ap");
            d.addProperty("mcpUrl", "http://127.0.0.1:" + cfg.bridgePort + "/mcp");
            d.addProperty("healthUrl", "http://127.0.0.1:" + cfg.bridgePort + "/health");
            d.addProperty("tokenHeader", "X-Autopilot-Token");
            d.addProperty("token", cfg.bridgeToken);
            d.addProperty("hint",
                    "Tools for automating a farm by replaying what the player recorded. Read "
                  + "ap_list_farms before offering to start one, and ap_get_status before "
                  + "anything positional.");

            JsonArray tools = new JsonArray();
            for (var el : Mcp.toolList()) {
                tools.add(el.getAsJsonObject().get("name").getAsString());
            }
            d.add("tools", tools);

            Path file = Workspace.lumiLinkDir().resolve("autopilot.json");
            Files.writeString(file, GSON.toJson(d), StandardCharsets.UTF_8);
            AutopilotClient.LOG.info("announced to lumi-link at {}", file);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("could not write the lumi-link descriptor", e);
        }
    }

    /** Clear the descriptor, so a stale port is not left behind for next time. */
    public static void withdraw() {
        try {
            Files.deleteIfExists(Workspace.lumiLinkDir().resolve("autopilot.json"));
        } catch (Exception ignored) {
            // Nothing useful to do about it; a stale file only fails a health check.
        }
    }

    private static String versionOf() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("autopilot")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }
}
