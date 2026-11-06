package com.omeldar.autopilot.farm;

import com.omeldar.autopilot.Chat;
import com.omeldar.autopilot.Config;
import com.omeldar.autopilot.Keys;
import com.omeldar.autopilot.Task;
import com.omeldar.autopilot.pilot.Pilot;
import com.omeldar.autopilot.pilot.Walk;
import com.omeldar.autopilot.record.Macro;
import com.omeldar.autopilot.record.MacroStore;
import com.omeldar.autopilot.replay.Replayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Locale;

/**
 * "Go and start the sugarcane farm."
 *
 * <p>One task that strings the others together: travel to the approach point, walk the last
 * few blocks onto the working block, run the start macro once if there is one, then loop the
 * run macro. Each leg can refuse, and a refusal stops the whole thing with a reason a person
 * or a model can act on - there is no step that shrugs and carries on, because the next step
 * would then be pressing keys in the wrong place.
 *
 * <p>It is a single {@link Task} on purpose, so the one watchdog covers the flight, the walk
 * and the farming alike. Take damage on the way in and the whole errand stops.
 */
public final class FarmRunner implements Task {
    private enum Phase { TRAVEL, STATION, START_MACRO, RUN_MACRO, DONE }

    private final Farm farm;
    private final int loops;
    private final Pilot.Mode travelMode;

    private Phase phase = Phase.TRAVEL;
    private Task leg;
    private final Walk walk = new Walk();
    private int stationTicks;
    private String note = "";

    public FarmRunner(Farm farm, int loops, Pilot.Mode travelMode) {
        this.farm = farm;
        this.loops = loops;
        this.travelMode = travelMode;
    }

    @Override
    public String name() {
        return "farm " + farm.id;
    }

    @Override
    public String start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "not in a world";

        String here = mc.world.getRegistryKey().getValue().toString();
        if (farm.dimension != null && !farm.dimension.isBlank() && !farm.dimension.equals(here)) {
            return farm.id + " is in " + farm.dimension + " and you are in " + here
                 + ". This mod does not use portals for you - get there first.";
        }
        if (farm.runMacro == null || farm.runMacro.isBlank()) {
            return farm.id + " has no runMacro set in farms.json, so there is nothing to run.";
        }
        if (!MacroStore.exists(farm.runMacro)) {
            return farm.id + " points at a macro called " + farm.runMacro
                 + ", which does not exist. Record it, or fix farms.json.";
        }
        if (farm.startMacro != null && !farm.startMacro.isBlank()
                && !MacroStore.exists(farm.startMacro)) {
            return farm.id + " points at a start macro called " + farm.startMacro
                 + ", which does not exist.";
        }
        if (farm.station == null) {
            return farm.id + " has no station in farms.json - the mod would not know where "
                 + "to stand.";
        }

        // Skip the flight when already on site. A model asking twice should not take off.
        double flat = Math.hypot(farm.station.x - p.getX(), farm.station.z - p.getZ());
        if (flat < 24) {
            phase = Phase.STATION;
            walk.reset(p);
            Chat.info("already on site, walking to the station");
            return null;
        }

        Farm.Spot to = farm.approach != null ? farm.approach : farm.station;
        Pilot pilot = new Pilot(to.x, to.y, to.z, travelMode, farm.id);
        String refusal = pilot.start(mc);
        if (refusal != null) return refusal;
        leg = pilot;
        phase = Phase.TRAVEL;
        return null;
    }

    @Override
    public boolean tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;

        switch (phase) {
            case TRAVEL -> {
                if (leg != null && leg.tick(mc)) return true;
                if (leg != null) leg.stop(mc, "leg done");
                leg = null;
                double flat = Math.hypot(farm.station.x - p.getX(), farm.station.z - p.getZ());
                if (flat > 48) {
                    note = "travel stopped " + (int) flat + " blocks short of " + farm.id;
                    return false;
                }
                phase = Phase.STATION;
                walk.reset(p);
                return true;
            }
            case STATION -> {
                if (++stationTicks > 1200) {
                    note = "could not reach the station at " + farm.id;
                    walk.release(mc);
                    return false;
                }
                Walk.Step step = walk.step(mc, farm.station.x, farm.station.y, farm.station.z,
                        Math.max(0.35, Config.get().anchorTolerance * 0.6));
                if (step == Walk.Step.BLOCKED) {
                    note = "the walk onto the station at " + farm.id + " is blocked";
                    return false;
                }
                if (step == Walk.Step.ARRIVED) {
                    walk.release(mc);
                    Keys.releaseAll(mc);
                    p.setYaw(farm.station.yaw);
                    p.setPitch(farm.station.pitch);
                    return nextMacroPhase(mc);
                }
                return true;
            }
            case START_MACRO -> {
                if (leg != null && leg.tick(mc)) return true;
                if (leg != null) leg.stop(mc, "start macro done");
                leg = null;
                return beginRunMacro(mc);
            }
            case RUN_MACRO -> {
                if (leg != null && leg.tick(mc)) return true;
                if (leg != null) {
                    leg.stop(mc, "run macro done");
                    note = ((Replayer) leg).outcome();
                }
                leg = null;
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean nextMacroPhase(MinecraftClient mc) {
        if (farm.startMacro != null && !farm.startMacro.isBlank()) {
            try {
                Macro macro = MacroStore.load(farm.startMacro);
                Replayer once = new Replayer(macro, 1);
                String refusal = once.start(mc);
                if (refusal != null) {
                    note = "start macro refused: " + refusal;
                    return false;
                }
                leg = once;
                phase = Phase.START_MACRO;
                Chat.info("running the start macro for " + farm.id);
                return true;
            } catch (Exception e) {
                note = "could not load the start macro: " + e.getMessage();
                return false;
            }
        }
        return beginRunMacro(mc);
    }

    private boolean beginRunMacro(MinecraftClient mc) {
        try {
            Macro macro = MacroStore.load(farm.runMacro);
            Replayer run = new Replayer(macro, loops);
            String refusal = run.start(mc);
            if (refusal != null) {
                note = "run macro refused: " + refusal;
                return false;
            }
            leg = run;
            phase = Phase.RUN_MACRO;
            Chat.good(farm.id + " is running"
                    + (loops == 0 ? " - it will keep going until a guard stops it" : ""));
            return true;
        } catch (Exception e) {
            note = "could not load the run macro: " + e.getMessage();
            return false;
        }
    }

    @Override
    public void stop(MinecraftClient mc, String reason) {
        if (leg != null) {
            leg.stop(mc, reason);
            leg = null;
        }
        walk.release(mc);
        Keys.releaseAll(mc);

        // Put the farm back the way it was found, if the player wrote down how. Best effort
        // on purpose: this runs while something has already gone wrong.
        if (farm.stopMacro != null && !farm.stopMacro.isBlank() && MacroStore.exists(farm.stopMacro)) {
            Chat.info("stop macro " + farm.stopMacro + " is set - run it with "
                    + "/ap play " + farm.stopMacro + " when you are back at the station");
        }
        if (!note.isBlank()) Chat.info(note);
    }

    @Override
    public String status() {
        String inner = leg == null ? "" : "  " + leg.status();
        return String.format(Locale.ROOT, "%s: %s%s",
                farm.id, phase.name().toLowerCase(Locale.ROOT).replace('_', ' '), inner);
    }

    public String outcome() {
        return note;
    }
}
