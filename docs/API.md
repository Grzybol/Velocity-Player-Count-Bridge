# API – Velocity Player Count Bridge

## Kanał i protokół

- **Kanał plugin messaging:** `aiplayers:count` (konfigurowalne w `config.yml`).
- **Protokół payloadu:** `aiplayers-count-v1` (konfigurowalny w `config.yml`).

Backend musi wysyłać komunikaty JSON (UTF‑8) na ten kanał z aktywnym połączeniem gracza do proxy.

## Format payloadu

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

### Opis pól

- `protocol` – identyfikator protokołu, musi być zgodny z `config.yml`.
- `server_id` – unikalny identyfikator backendu; sprawdzany z allowlistą.
- `timestamp_ms` – znacznik czasu w ms; starsze niż ostatnio przyjęty są ignorowane.
- `online_humans` – liczba ludzi online (>= 0).
- `online_ai` – liczba botów AI online (>= 0).
- `online_total` – łączna liczba graczy online (>= 0). Jeśli mniejsza niż `online_humans + online_ai`, zostanie
  podniesiona do tej sumy.
- `max_players_override` – opcjonalny override na max graczy (>= 0), używany gdy `max_players_mode` na to pozwala.
- `auth` – token autoryzacyjny (globalny lub per‑server).

## Autoryzacja

### Tryb globalny

**Konfiguracja proxy (`config.yml`):**

```yaml
auth_mode: "global"
global_token: "GLOBAL_SECRET_ABC123"
```

W payloadach ustaw `auth` na dokładnie ten sam token:

```json
{
  "protocol": "aiplayers-count-v1",
  "server_id": "lobby-1",
  "timestamp_ms": 1730000000000,
  "online_humans": 20,
  "online_ai": 0,
  "online_total": 20,
  "max_players_override": 200,
  "auth": "GLOBAL_SECRET_ABC123"
}
```

### Tryb per‑server

**Konfiguracja proxy (`config.yml`):**

```yaml
auth_mode: "per_server"
server_tokens:
  "lobby-1": "LOBBY_TOKEN_123"
  "survival-1": "SURVIVAL_TOKEN_456"
```

W payloadzie `auth` musi odpowiadać tokenowi przypisanemu do `server_id`:

```json
{
  "protocol": "aiplayers-count-v1",
  "server_id": "survival-1",
  "timestamp_ms": 1730000000000,
  "online_humans": 12,
  "online_ai": 5,
  "online_total": 17,
  "max_players_override": 120,
  "auth": "SURVIVAL_TOKEN_456"
}
```

## Przykład użycia po stronie backendu (Paper/Spigot)

Pseudokod wysyłający payload przez plugin messaging (wymaga aktywnego gracza na serwerze):

```java
String channel = "aiplayers:count";
String json = "{"
  + "\"protocol\":\"aiplayers-count-v1\","
  + "\"server_id\":\"survival-1\","
  + "\"timestamp_ms\":" + System.currentTimeMillis() + ","
  + "\"online_humans\":12,"
  + "\"online_ai\":5,"
  + "\"online_total\":17,"
  + "\"max_players_override\":120,"
  + "\"auth\":\"SURVIVAL_TOKEN_456\""
  + "}";
byte[] data = json.getBytes(StandardCharsets.UTF_8);
player.sendPluginMessage(plugin, channel, data);
```

## Walidacja i obsługa błędów

- Payloady z błędnym JSON-em, protokołem, auth lub `server_id` są ignorowane.
- Jeżeli `allowlist_enabled = true`, akceptowane są tylko `server_id` z listy `allowed_server_ids`.
- Ostrzeżenia są rate‑limitowane w logach, aby uniknąć spamowania.

