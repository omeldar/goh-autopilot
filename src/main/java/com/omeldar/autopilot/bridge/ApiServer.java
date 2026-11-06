package com.omeldar.autopilot.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.omeldar.autopilot.AutopilotClient;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Scheduler;
import com.omeldar.autopilot.Workspace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * A small loopback HTTP server speaking MCP, so a Claude Code session already running in a
 * terminal can drive the mod.
 *
 * <p>Bound to 127.0.0.1 and nothing else, and every call carries a token written to a file
 * only this machine's user can read. That is not belt and braces: this endpoint can hold the
 * player's keys down, and the one thing it must never be is reachable.
 */
public final class ApiServer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PROTOCOL = "2025-06-18";
    private static HttpServer server;

    private ApiServer() {}

    public static synchronized void start() {
        Config cfg = Config.get();
        if (server != null) return;
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), cfg.bridgePort), 0);
            server.createContext("/health", ApiServer::health);
            server.createContext("/mcp", ApiServer::mcp);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "autopilot-bridge");
                t.setDaemon(true);
                return t;
            }));
            server.start();

            Workspace.ensure();
            JsonObject info = new JsonObject();
            info.addProperty("url", "http://127.0.0.1:" + cfg.bridgePort + "/mcp");
            info.addProperty("header", "X-Autopilot-Token");
            info.addProperty("token", cfg.bridgeToken);
            info.add("mcpServers", mcpSnippet(cfg));
            Files.writeString(Workspace.bridgeFile(), GSON.toJson(info), StandardCharsets.UTF_8);

            AutopilotClient.LOG.info("bridge listening on 127.0.0.1:{}", cfg.bridgePort);
        } catch (Exception e) {
            AutopilotClient.LOG.warn("bridge could not start on port {}", cfg.bridgePort, e);
            server = null;
        }
    }

    /** Ready to paste into a Claude Code .mcp.json, for anyone not using Lumi. */
    private static JsonObject mcpSnippet(Config cfg) {
        JsonObject headers = new JsonObject();
        headers.addProperty("X-Autopilot-Token", cfg.bridgeToken);
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "http");
        entry.addProperty("url", "http://127.0.0.1:" + cfg.bridgePort + "/mcp");
        entry.add("headers", headers);
        JsonObject servers = new JsonObject();
        servers.add("autopilot", entry);
        return servers;
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ routes

    private static void health(HttpExchange ex) throws IOException {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("mod", "autopilot");
        o.addProperty("inWorld", mc != null && mc.world != null);
        o.addProperty("busy", Scheduler.busy());
        respond(ex, 200, GSON.toJson(o));
    }

    private static void mcp(HttpExchange ex) throws IOException {
        if (!authorised(ex)) {
            respond(ex, 401, error(null, -32001, "bad token"));
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, error(null, -32600, "use POST"));
            return;
        }

        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject req;
        try {
            req = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            respond(ex, 400, error(null, -32700, "parse error"));
            return;
        }

        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonElement id = req.get("id");
        if (id == null || id.isJsonNull()) {
            // A notification wants no answer.
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }

        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(req);
                case "tools/list" -> toolsResult();
                case "tools/call" -> callTool(req.getAsJsonObject("params"));
                case "ping" -> new JsonObject();
                default -> null;
            };
            if (result == null) {
                respond(ex, 200, error(id, -32601, "unknown method: " + method));
                return;
            }
            JsonObject envelope = new JsonObject();
            envelope.addProperty("jsonrpc", "2.0");
            envelope.add("id", id);
            envelope.add("result", result);
            respond(ex, 200, GSON.toJson(envelope));
        } catch (Exception e) {
            AutopilotClient.LOG.warn("mcp call failed", e);
            respond(ex, 200, error(id, -32603,
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static JsonObject initialize(JsonObject req) {
        String requested = PROTOCOL;
        try {
            JsonObject params = req.getAsJsonObject("params");
            if (params != null && params.has("protocolVersion")) {
                requested = params.get("protocolVersion").getAsString();
            }
        } catch (Exception ignored) {
            // An odd handshake is not worth refusing over; answer with our own version.
        }

        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "autopilot");
        info.addProperty("version", "0.1.0");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested);
        result.add("capabilities", caps);
        result.add("serverInfo", info);
        result.addProperty("instructions",
                "Autopilot replays the player's own recorded key presses. It cannot invent a "
              + "macro: a human has to perform the run once first. Call get_status before "
              + "anything positional, and list_farms before offering to start a farm - the "
              + "farm book is the only thing that knows where they are. One task runs at a "
              + "time; starting another refuses rather than fighting it. Everything stops on "
              + "damage, a full inventory, an open screen, chat saying the player's name, or "
              + "the player touching a key, and get_status reports which.");
        return result;
    }

    private static JsonObject toolsResult() {
        JsonObject result = new JsonObject();
        result.add("tools", Mcp.toolList());
        return result;
    }

    private static JsonObject callTool(JsonObject params) throws Exception {
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        String text = Mcp.call(name, args);

        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(item);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    // ---------------------------------------------------------------- plumbing

    private static boolean authorised(HttpExchange ex) {
        String token = Config.get().bridgeToken;
        if (token == null || token.isBlank()) return false;
        String header = ex.getRequestHeaders().getFirst("X-Autopilot-Token");
        if (header != null && header.equals(token)) return true;
        // Lumi forwards the token it read from the descriptor under its own header name.
        String lumi = ex.getRequestHeaders().getFirst("X-Lumi-Token");
        return lumi != null && lumi.equals(token);
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        if (id != null) envelope.add("id", id);
        envelope.add("error", err);
        return GSON.toJson(envelope);
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
