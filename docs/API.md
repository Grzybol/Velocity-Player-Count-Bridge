# HTTP API – backend → Velocity (polling)

## Cel

W trybie pollingu HTTP plugin Velocity cyklicznie odpytuje backendy o liczby graczy i aktualizuje stan `serverStates`
bez potrzeby aktywnych połączeń graczy na danym serwerze.

## Konfiguracja pollingu w `config.yml`

```yaml
polling:
  enabled: true
  interval_seconds: 10
  request_timeout_ms: 2000
  endpoints:
    "lobby-1":
      url: "http://127.0.0.1:8080/ai-players"
      auth_header: "Bearer token1"
    "survival-1":
      url: "http://127.0.0.1:8080/ai-players"
      auth_header: "Bearer token2"
```

- `endpoints` mapuje `server_id` → endpoint HTTP.
- `auth_header` jest opcjonalny i trafia jako nagłówek `Authorization`.

## Request

Plugin wysyła `GET` z nagłówkiem:

```
Accept: application/json
Authorization: Bearer token1
```

Przykładowy request:

```
GET http://127.0.0.1:8080/ai-players
Accept: application/json
Authorization: Bearer token1
```

## Response (JSON)

Backend zwraca payload zgodny z `CountPayload`:

```json
{
  "protocol": "aiplayers-count-v1",
  "server_id": "lobby-1",
  "timestamp_ms": 1717359501000,
  "online_humans": 2,
  "online_ai": 18,
  "online_total": 20,
  "max_players_override": 100,
  "auth": "token1"
}
```

### Wymagania walidacyjne

- `protocol` musi być zgodny z `config.yml`.
- `auth` musi pasować do `auth_mode` (`global` lub `per_server`).
- `server_id` musi być na allowliście, jeśli `allowlist_enabled = true`.
- `timestamp_ms` nie może być starszy od ostatnio przyjętej wartości dla danego serwera.

Jeśli `server_id` w odpowiedzi jest pusty, plugin użyje klucza `endpoints` jako identyfikatora serwera.
