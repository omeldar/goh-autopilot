# Autopilot

A Fabric client mod that records what you do at a farm and does it again, for as long as it
makes sense to. Record once by hand, then leave it running; it stops on its own when something
changes.

It is also the hands for [Lumi](https://github.com/omeldar/lumi). Lumi can read the world and
talk about it, but it cannot walk anywhere. With Autopilot installed, "fly to the sugarcane
farm and start it" is one thing you can ask for, and the mod flies there, lands, walks onto
the working block and starts the run.

Built for **Minecraft 1.21.11 / Fabric**. Client-side only: no server install, nothing to
whitelist, and it works on single player the same as anywhere else.

---

## What it is, and what it deliberately is not

The mod holds down your own key bindings, one tick before vanilla reads them. That is the
whole mechanism.

It never moves the player, swings the arm, places a block or sends a packet of its own.
Everything that happens after it presses a key is vanilla doing exactly what it does for a
hand on a keyboard, which means a replay is subject to the same cooldowns, the same reach
checks, the same server-side validation. There is no mixin, no custom rendering and nothing
that reads state the player cannot see on screen.

So there is no mechanic here that a keyboard could not produce. What the mod adds is patience
and attention: it will hold that key for forty minutes, and it will notice the moment it
should stop.

**It cannot invent a macro.** A human has to perform the run once. The mod has no idea what a
sugarcane farm is; it knows that you held right-click for nine seconds while looking at a
particular block, and it can do that again.

## Does it cost frames

No, and that is a design constraint rather than a hope.

- **No rendering of any kind.** Status goes on the action bar above the hotbar, the line
  vanilla already draws for `/title actionbar`. There is no HUD layer, no overlay, no custom
  draw call, so there is nothing to cost a frame.
- **One tick listener.** Idle, it is a few boolean checks per tick and no allocation. Twenty
  times a second, not once per frame - whether you play at 60 fps or 400, the cost is the same
  and it is not measurable.
- **Delta-encoded macros.** Holding a key for nine seconds is two frames in the file, not a
  hundred and eighty, so nothing is iterating a long list every tick.
- **No threads of its own** beyond one daemon thread for the local bridge, which sleeps unless
  something asks it a question.

The honest number: a profiler cannot tell an installed Autopilot from an absent one while it
is idle, and a running macro is a handful of field reads a tick.

## Guards: how a run ends

An unattended macro is only as good as the moment it decides to quit. These are the reasons a
human would have stopped, written down and checked once a tick. Any one of them ends the run
and says which it was.

| It stops when | Default |
| --- | --- |
| You take any damage | on |
| Health drops below a floor | 14 (7 hearts) |
| Food drops below a floor | 7 |
| The inventory fills up | on |
| The held tool wears down | at 8% |
| Another player comes near | off |
| Any screen opens | on |
| Chat says your name | on |
| The dimension changes | on |
| A time ceiling passes | 180 min |
| **You touch a movement key** | always |
| **You press End** | always |

The last two matter most. The takeover check reads the keyboard directly through GLFW rather
than through the key bindings - the bindings are what the mod is holding down, so from their
point of view the key is already pressed, and reading the hardware is the only way to tell
your hand from the mod's. Touch W and the run is over before the next tick.

Every guard is a line in `config/autopilot/config.json`.

## The anchor

A farm macro only means anything from one exact spot. Half a block to the left and the same
presses break the wrong block, or reach into someone's chest.

So a recording stores where it started, and the anchor is checked before the first frame and
again before every repeat. If the player has drifted, the mod walks back to the anchor and
carries on, or stops - `onDrift` in the config decides which. It is never ignored.

A macro that does not end roughly where it began is marked one-shot and the mod refuses to
loop it, because twenty passes of a run that ends a block north walks you off the farm.

## Commands

`/ap` on its own says what is happening. `/autopilot` is the long form of the same thing.

**Recording**

```
/ap rec <name> [note...]     start recording
/ap rec stop                 stop and save      (or End, or /ap stop)
/ap rec cancel               throw it away
/ap list                     every macro: length, loopable, note
/ap del <name>
```

**Replay**

```
/ap play <macro> [loops]     loops defaults to 1
/ap loop <macro>             until a guard stops it
/ap stop                     stop everything, let go of every key
```

**The plain auto-clicker**, for when a macro is more ceremony than the job needs:

```
/ap click <cps> [attack|use|jump|sneak|drop] [seconds]
/ap hold <attack|use|jump|sneak|drop> [seconds]
```

Capped at 20 clicks a second, because the game ticks 20 times a second and anything above
that is a number the game cannot act on.

**Travel**

```
/ap goto <x> <y> <z> [auto|walk|fly|elytra]
```

**Farms**

```
/ap farm list
/ap farm add <id>            writes down where you are standing
/ap farm info <id>
/ap farm start <id>          travel there, land, walk on, start running
/ap farm reload              after hand-editing farms.json
```

**Keys**: `End` stops everything, bound by default. The record toggle is registered unbound so
it cannot collide with whatever you already have - bind it in Controls if you want it.

## Travel

Three ways of getting about, and the mod picks the honest one rather than the clever one.

- **Walk** always works. It faces the target, holds forward, hops a one-block step and swims
  up out of water. It is not a pathfinder and does not pretend to be: it will not dig, bridge
  or cross a gap, and it refuses to walk off a drop further than it can survive. When it
  cannot make progress it stops and says so, rather than improvising into a ravine.
- **Fly** is creative flight. It climbs straight up to cruising height before moving off (so a
  treeline cannot catch it mid-climb), cruises, brakes so the momentum runs out over the target,
  and comes straight down onto the block. Hovering is what makes it precise: tested, it set down
  on the exact recorded block of a hilltop farm 80 blocks away. If something boxes it in the air
  it climbs, and after eight seconds without progress it stops and says where.

Whichever way it travels, a y with nothing to stand on is answered rather than circled: "arrived
over 54 25, but on the ground at y 73 rather than the y 102 asked for".
- **Elytra** only ever *assists* a glide you have already started. It steers, spends rockets
  and brings you down gently - and it will never launch you off a cliff by itself.

That last limit is deliberate, and worth being plain about: an automatic launch that misjudges
a mountain costs the elytra and everything in the inventory, and no amount of care in a few
hundred lines of client code makes that a risk worth taking unattended. Off by default;
`allowElytra` turns the assist on.

## The farm book

`config/autopilot/farms.json` is a plain list of the farms in your world:

```json
{
  "farms": [
    {
      "id": "sugarcane",
      "name": "Sugarcane farm",
      "dimension": "minecraft:overworld",
      "approach": { "x": 214, "y": 72, "z": -488, "yaw": 90, "pitch": 0 },
      "station":  { "x": 219.5, "y": 71, "z": -486.5, "yaw": 178.2, "pitch": 31.4 },
      "startMacro": "sugarcane-lever",
      "runMacro": "sugarcane-run",
      "stopMacro": "",
      "yields": "sugarcane, about a stack a minute",
      "notes": "Approach from the north platform; the south one has a two-block drop."
    }
  ]
}
```

`approach` is somewhere safe to arrive. `station` is the exact block to stand on, and should
match the anchor of `runMacro`. `/ap farm add <id>` fills both in from where you are standing,
and the file is meant to be hand-edited after that.

This is the part that makes a Claude session useful rather than merely chatty. "Go start the
sugarcane farm" is not something a model can work out from a world scan - it needs to know
that the farm exists, where to land, which lever comes first. That is knowledge about your
world, so it lives in a file you own.

## Letting Claude drive it

The mod serves a small MCP endpoint on `127.0.0.1:25598`, guarded by a token.

**With Lumi installed, there is nothing to do.** Autopilot writes a descriptor to
`config/lumi-link/autopilot.json` on startup; Lumi finds it, health-checks it and offers these
tools to your session under an `ap_` prefix alongside its own. See
[docs/lumi-link.md](docs/lumi-link.md) for the protocol - it is a file and an HTTP call, with
nothing compiled against anything, on purpose.

**Without Lumi**, `config/autopilot/bridge.json` contains an `mcpServers` block ready to paste
into a Claude Code `.mcp.json`.

The tools:

| Tool | What it does |
| --- | --- |
| `get_status` | Position, dimension, health, food, flight, what is running, why the last thing stopped |
| `list_macros` | Every recording, with its anchor and whether it loops |
| `list_farms` | The farm book |
| `play_macro` | Replay from the anchor |
| `farm_start` | Travel, land, walk on, run the start macro, loop the run macro |
| `travel_to` | Walk or fly to a coordinate |
| `click` | The auto-clicker |
| `record_start` / `record_stop` | Start and save a recording the player then performs |
| `farm_note` | Write a farm into the book from where the player is standing |
| `stop` | Stop everything |

Two rules run through all of them. Every refusal says what is wrong and what to do instead,
because a bare `false` sends a model round the loop guessing - with a mod that holds keys down,
that is the last thing anyone wants. And nothing there is a new ability: each tool is something
you could do with a command, so a session driving the mod is going through the same door you
do.

One task runs at a time. Asking for a second refuses rather than fighting the first over the
same keys.

## Install

1. Fabric loader for 1.21.11, and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop the jar in `mods/`.
3. Optional: [Lumi](https://github.com/omeldar/lumi), for the Claude side.

Build it yourself with `./gradlew build`; the jar lands in `build/libs/`.

## A word about servers

This is a macro tool. On your own server or in single player that is nobody's business but
yours. Plenty of public servers forbid macros and auto-clickers outright, and some will ban
for them - the mod does nothing to hide itself and no attempt has been made to make it hard to
detect, because that would be a different kind of tool with a different purpose. Read the rules
of the server you are on.

## Licence

MIT. Not made or endorsed by Mojang, Microsoft or Anthropic.
