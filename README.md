# <img src="./src/main/resources/assets/server-replay/icon.png" align="center" width="64px"/> Death Cam Recorder

**English** | [中文](./README_cn.md)

A server-side death POV capture mod for Minecraft. This mod automatically records players in survival mode with a continuous 1-minute rolling buffer and saves the recording when a player dies.

**Perfect for UHC servers, competitive gameplay, and eliminating "IT WAS LAG!!" excuses.**

[![Modrinth download](https://img.shields.io/modrinth/dt/server-replay?label=Download%20on%20Modrinth&style=for-the-badge)](https://modrinth.com/mod/server-replay)

## Features

- **Continuous Rolling Buffer**: Always keeps the last 1 minute of gameplay recorded
- **Automatic Death Capture**: Saves recording 2 seconds after a player dies (to capture the death moment)
- **Game Mode Aware**: Only records players in Survival mode
- **Smart Transitions**: Properly handles death → spectator transitions without losing death footage
- **15-Second Validation**: Auto-starts recording when a player stays in Survival for 15+ seconds
- **Zero Configuration**: Works out of the box - just install and go

## How It Works

1. **Player joins in Survival mode** → 15-second countdown begins
2. **After 15 seconds in Survival** → Recording starts with 1-minute rolling buffer
3. **Player dies** → Recording saved 2 seconds after death
4. **Player respawns in Survival** → New 15-second countdown begins
5. **Player switches to Creative/Spectator** → Recording discarded (unless it's a death transition)

## Installation

This mod requires:
- Fabric Launcher
- Fabric API
- Fabric Kotlin

Just drop the mod into your server's `mods` folder and start the server.

## Commands

All commands require operator permissions by default.

| Command | Description |
|---------|-------------|
| `/deathcam status` | Show current recording status for all players |
| `/deathcam reload` | Reload the configuration file |
| `/deathcam start <players>` | Force start recording for specified players |
| `/deathcam stop <players> [save]` | Force stop recording for players (optionally save) |
| `/deathcam view <name> <replay>` | View a saved death replay in-game |
| `/deathcam download <name> <replay>` | Get download link for a replay |
| `/deathcam encoding set <format>` | Change replay format (replaymod/flashback) |

### Examples

```
/deathcam status
/deathcam start @a
/deathcam stop senseiwells true
/deathcam view senseiwells recording_2024-12-30_15-30-45
```

## Configuration

Configuration file is located at `config/server-replay/config.json`:

```json
{
  "debug": false,
  "default_encoding": "replaymod",
  "world_name": "World",
  "server_name": "Server",
  "player_recording_path": "./recordings/deathcams",
  "notify_admins_of_status": true,
  "allow_downloading_replays": false,
  "include_resource_packs": true,
  "ignore_sound_packets": false,
  "ignore_light_packets": true,
  "ignore_chat_packets": false,
  "optimize_explosion_packets": true,
  "optimize_entity_packets": false,
  "record_voice_chat": false
}
```

### Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `debug` | Enable debug logging | `false` |
| `default_encoding` | Replay format: `"replaymod"` or `"flashback"` | `"replaymod"` |
| `world_name` | World name shown in replay | `"World"` |
| `server_name` | Server name shown in replay | `"Server"` |
| `player_recording_path` | Where death recordings are saved | `"./recordings/deathcams"` |
| `notify_admins_of_status` | Notify operators of recording status | `true` |
| `allow_downloading_replays` | Enable download command | `false` |
| `include_resource_packs` | Include server resource packs in replay | `true` |
| `ignore_sound_packets` | Don't record sounds | `false` |
| `ignore_light_packets` | Don't record light updates | `true` |
| `ignore_chat_packets` | Don't record chat | `false` |
| `optimize_explosion_packets` | Optimize explosion packet recording | `true` |
| `optimize_entity_packets` | Optimize entity packet recording | `false` |
| `record_voice_chat` | Record voice chat (requires mod) | `false` |

## Recording Structure

Recordings are saved at:
```
recordings/
  deathcams/
    <player_name>/
      recording_2024-12-30_15-30-45.mcpr  (death recording)
      recording_2024-12-30_16-45-22.mcpr  (another death)
```

## Viewing Replays

1. Copy the `.mcpr` file to your client's `replay_recordings` folder
2. Open Minecraft with Replay Mod or Flashback installed
3. Select the replay from the replay viewer

Alternatively, use `/deathcam view <player> <replay>` to view directly on the server (if supported).

## Use Cases

- **UHC Tournaments**: Capture every death for review and dispute resolution
- **Competitive Servers**: Provide evidence for reports and appeals
- **Content Creation**: Automatically capture death moments for highlights
- **Server Administration**: Review suspicious deaths or hacking accusations

## Permissions

The mod supports LuckPerms/Fabric Permissions API:

| Permission | Description |
|------------|-------------|
| `server-replay.commands.deathcam` | Access to all deathcam commands |

## Known Limitations

- Recording only works for real players (not carpet bots or NPCs)
- Some heavily modded servers with custom packets may have compatibility issues
- The 1-minute buffer is hardcoded (configurable buffer duration coming soon)

## License

This project is licensed under the MIT License - see the [LICENSE](./LICENSE) file for details.
