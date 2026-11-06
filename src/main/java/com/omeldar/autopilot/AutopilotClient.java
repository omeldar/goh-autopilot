package com.omeldar.autopilot;

import com.omeldar.autopilot.bridge.ApiServer;
import com.omeldar.autopilot.bridge.LumiLink;
import com.omeldar.autopilot.farm.FarmBook;
import com.omeldar.autopilot.guard.Watchdog;
import com.omeldar.autopilot.record.Recorder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Autopilot: record what you do, replay it on a loop, and let a Claude session drive it.
 *
 * <p>Client-side only, and deliberately small in what it touches. It holds the player's own
 * key bindings and sets the look angle; everything that follows from that is vanilla doing
 * what it always does. No mixins, no custom rendering, no packets of its own, nothing that
 * reaches for information the player cannot see on screen. That is both why it costs no
 * frames and why nothing it does changes a game mechanic.
 */
public final class AutopilotClient implements ClientModInitializer {
    public static final String MOD_ID = "autopilot";
    public static final Logger LOG = LoggerFactory.getLogger("autopilot");

    @Override
    public void onInitializeClient() {
        Workspace.ensure();
        Config.load();
        FarmBook.reload();
        Keybinds.register();
        Commands.register();

        // One tick listener for the whole mod. Each half returns immediately when there is
        // nothing to do, so an installed-but-unused Autopilot is a few boolean checks a tick.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            Recorder.onTick(mc);
            Keybinds.onTick(mc);
            Scheduler.onTick(mc);
        });

        // Anyone saying your name is a reason to stop and look at the screen.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) noteIfMentioned(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, received) ->
                noteIfMentioned(message.getString()));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Scheduler.end(client, "disconnected");
            Recorder.cancel();
        });

        if (Config.get().bridgeEnabled) {
            ApiServer.start();
            if (Config.get().lumiLink) LumiLink.announce();
        }
        LOG.info("autopilot ready");
    }

    private static void noteIfMentioned(String line) {
        if (line == null || line.isEmpty()) return;
        if (!Scheduler.busy()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        String me = mc.player.getGameProfile().name();
        if (me == null || me.isEmpty()) return;
        // Skip the player's own lines, or a macro that types in chat would stop itself.
        if (line.startsWith("<" + me + ">")) return;
        if (line.toLowerCase(Locale.ROOT).contains(me.toLowerCase(Locale.ROOT))) {
            Watchdog.noteMention(line);
        }
    }
}
