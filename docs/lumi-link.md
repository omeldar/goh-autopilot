# Lumi Link, version 1

How a companion mod tells [Lumi](https://github.com/omeldar/lumi) that it exists, so a Claude
Code session gets every tool in the pack from one place.

## The problem

Lumi already runs an MCP server on `127.0.0.1:25599` that a Claude Code session talks to. Each
companion mod could run its own and be registered by hand, but then the player maintains a list
of ports in two files, and a session started from anywhere else sees none of them. What is
wanted is for Lumi to hand the session the whole pack.

## What was rejected, and why

The obvious design is a shared Java interface: Lumi publishes `LumiToolProvider`, companions
implement it, Lumi looks them up through a Fabric entrypoint.

That was rejected. It makes every companion compile against a particular Lumi version, so the
first time either side updates, the other fails to load - and a hard load failure in a mod pack
is the one outcome that is worse than the feature being missing. It also means a companion
cannot be installed without Lumi at all.

## The design

A file and an HTTP call. Nothing is compiled against anything.

1. **The companion runs its own MCP endpoint** on loopback, on its own port, guarded by its own
   token. This is what it would do anyway; it is independently useful with Lumi absent.

2. **On startup it writes a descriptor** to `config/lumi-link/<modid>.json`.

3. **Lumi reads that directory**, health-checks each descriptor it finds, and merges the tools
   into its own `tools/list` under the descriptor's `prefix`. A `tools/call` for a prefixed
   name is forwarded to that endpoint with its token.

A version mismatch is at worst a stale file that fails a health check and is skipped. A missing
Lumi means the descriptor is a file nobody read.

## The descriptor

`config/lumi-link/autopilot.json`:

```json
{
  "lumiLink": 1,
  "modId": "autopilot",
  "name": "Autopilot",
  "version": "0.1.0",
  "summary": "Records the player's key presses and replays them on a loop, travels to coordinates, and runs the farms in the player's farm book.",
  "prefix": "ap",
  "mcpUrl": "http://127.0.0.1:25598/mcp",
  "healthUrl": "http://127.0.0.1:25598/health",
  "tokenHeader": "X-Autopilot-Token",
  "token": "…",
  "hint": "Tools for automating a farm by replaying what the player recorded. Read ap_list_farms before offering to start one, and ap_get_status before anything positional.",
  "tools": ["get_status", "list_macros", "…"]
}
```

| Field | Meaning |
| --- | --- |
| `lumiLink` | Protocol version. Fields may be added; existing ones will not change meaning. |
| `modId` | Fabric mod id. One descriptor per mod; the filename should match. |
| `prefix` | Namespace for the tools, so two companions cannot both claim `get_status`. Lumi exposes them as `<prefix>_<tool>`. |
| `mcpUrl` | JSON-RPC 2.0 MCP over POST. Must be loopback. |
| `healthUrl` | `GET`, no auth, cheap. `{"ok":true,...}` means alive. |
| `tokenHeader`, `token` | What to send. Lumi forwards it verbatim. |
| `hint` | One or two sentences for the session: when to reach for these tools. |
| `tools` | Names, for a listing without a round trip. Advisory; `tools/list` is the truth. |

## Rules for a companion

- **Bind to loopback only.** These endpoints hold the player's keys down. The one thing they
  must never be is reachable.
- **Generate the token on first run**, into the mod's own config, and never log it.
- **Accept the token under your own header name**, and also under `X-Lumi-Token`, since that is
  what a proxying Lumi may send.
- **Write the descriptor on startup, every startup.** Ports change; a stale file is worse than
  none.
- **Work without Lumi.** The descriptor is an announcement, not a dependency.
- **Namespace nothing yourself.** Report bare tool names; the prefix is applied by whoever is
  proxying, so the endpoint stays usable on its own.
- **Say why, not just no.** A refusal that reads "no macro called x, call list_macros" ends a
  loop a bare `false` would keep going.

## Rules for the host side

- **Health-check before offering a companion's tools**, with a short timeout, and skip a
  descriptor that does not answer rather than failing the whole list.
- **Never shadow your own tools.** If a prefix collides with something of yours, drop the
  companion's and say so in the log.
- **Pass refusals through unchanged.** They are written for the model that is reading them.
- **Treat the descriptor as a local file written by a local mod**, which is all it is - and
  still do not follow a `mcpUrl` that is not loopback.
