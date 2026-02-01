# Velocity Player Count Bridge

A Velocity 3.4.0 plugin that aggregates player counts from backend Paper/Spigot servers and replaces the
proxy server list ping count with the total. Backends send JSON payloads over the `aiplayers:count`
plugin messaging channel.

## Features

- Aggregates AI + human player counts from multiple backend servers.
- Validates payload protocol, auth token, allowlist, and out-of-order timestamps.
- Ignores stale server data after a configurable timeout.
- Optional max player override behavior for server list ping.

## Installation

1. Build the plugin with Maven:
   ```bash
   mvn package
   ```
2. Drop the resulting JAR into your Velocity `plugins/` folder.
3. Start Velocity once to generate `config.toml`, then edit it as needed.

## Configuration (`config.toml`)

```toml
channel = "aiplayers:count"
protocol = "aiplayers-count-v1"
stale_after_ms = 30000
debug = false

auth_mode = "per_server" # allowed: "global", "per_server"
global_token = ""        # used if auth_mode=global
server_tokens = { "lobby-1" = "token1", "survival-1" = "token2" }

allowlist_enabled = true
allowed_server_ids = ["lobby-1", "survival-1"]

max_players_mode = "keep" # allowed: "keep", "use_max_override", "max_of_overrides"
```

### Auth notes

- **Global**: set `auth_mode = "global"` and provide `global_token`.
- **Per-server**: set `auth_mode = "per_server"` and define `server_tokens` map.
- If `auth_mode = "global"` and `global_token` is empty, the bridge will disable itself until fixed.

### Allowlist

If `allowlist_enabled = true`, only `server_id` values in `allowed_server_ids` are accepted. Others are
ignored and warn-logged (rate-limited).

### Stale handling

Servers are considered **active** only if the last message was received within `stale_after_ms`.
Inactive servers are excluded from aggregation.

### Max players behavior

- `keep`: leave Velocity's default max players untouched.
- `use_max_override` / `max_of_overrides`: use the maximum `max_players_override` reported by any
  active server if it is greater than zero.

## Payload format

Backends should send JSON (UTF-8) on the plugin messaging channel:

```json
{
  "protocol": "aiplayers-count-v1",
  "server_id": "survival-1",
  "timestamp_ms": 1730000000000,
  "online_humans": 12,
  "online_ai": 5,
  "online_total": 17,
  "max_players_override": 0,
  "auth": "TOKEN"
}
```

## Known limitations

- Plugin messaging requires an active player connection between the proxy and backend server.
  If no player is connected to a backend, it cannot send plugin messages to Velocity.

## Troubleshooting

- Enable `debug = true` to log accepted and rejected payloads.
- Verify the backend `protocol` string matches the proxy configuration.
- Confirm each `server_id` has the correct auth token configured.
