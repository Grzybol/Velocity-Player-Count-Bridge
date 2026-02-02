# Dokumentacja techniczna – Velocity Player Count Bridge

## Cel i zakres

Velocity Player Count Bridge jest pluginem dla Velocity 3.4.0, którego zadaniem jest agregowanie liczby graczy z
backendów (Paper/Spigot) i podstawianie wartości `onlinePlayers` w odpowiedzi na ping listy serwerów proxy.
Komunikacja odbywa się przez kanał plugin messaging z użyciem JSON. Plugin weryfikuje protokół, autoryzację
oraz allowlistę i odrzuca nieaktualne dane. 

## Architektura i przepływ danych

1. **Inicjalizacja proxy**
   - Ładowana jest konfiguracja z `config.yml` (tworzona na podstawie zasobu, jeśli jej brakuje).
   - Rejestrowany jest kanał `aiplayers:count` (lub inny z konfiguracji).
   - Jeśli `auth_mode = global` i `global_token` jest puste, plugin dezaktywuje się.

2. **Odbiór wiadomości z backendów**
   - Plugin nasłuchuje `PluginMessageEvent` i akceptuje tylko wiadomości z serwerów backendowych.
   - Treść wiadomości jest parsowana jako JSON do modelu `CountPayload`.
   - Weryfikowane są:
     - `server_id` (niepuste),
     - zgodność pola `protocol`,
     - autoryzacja (`auth`) w trybie globalnym lub per‑server,
     - allowlista (`allowed_server_ids`).
   - Dane są normalizowane (wartości ujemne ustawiane na 0, `online_total` podnoszone co najmniej do sumy
     `online_humans + online_ai`).
   - Aktualizacja stanu serwera jest akceptowana tylko wtedy, gdy `timestamp_ms` nie jest starszy niż poprzedni
     (obsługiwane są aktualizacje idempotentne z tym samym timestampem).

3. **Agregacja podczas pingów**
   - W `ProxyPingEvent` zliczane są tylko serwery „aktywne” (ostatni kontakt w granicach `stale_after_ms`).
   - `onlinePlayers` ustawiane jest na sumę `online_total` wszystkich aktywnych backendów.
   - W zależności od `max_players_mode`, `maximumPlayers` może zostać podniesione do największej wartości
     `max_players_override` z aktywnych serwerów.

## Kluczowe elementy kodu

- **`VelocityPlayerCountBridge`** – rejestracja kanału, obsługa wiadomości, agregacja i podmiana wartości pingu.
- **`ConfigLoader`** – tworzy i ładuje `config.yml`.
- **`BridgeConfig`** – model konfiguracji i enumy trybów pracy.
- **`CountPayload`** – model danych JSON otrzymywanych z backendu.
- **`ServerCountState`** – stan ostatnio zaakceptowanego raportu z backendu.

## Konfiguracja (skrót)

Najważniejsze pola `config.yml`:

- `channel`: kanał plugin messaging (domyślnie `aiplayers:count`).
- `protocol`: identyfikator protokołu payloadu (domyślnie `aiplayers-count-v1`).
- `stale_after_ms`: czas po którym serwer uznawany jest za nieaktywny.
- `auth_mode`: `global` lub `per_server`.
- `global_token` / `server_tokens`: klucze autoryzacji.
- `allowlist_enabled` / `allowed_server_ids`: kontrola dozwolonych backendów.
- `max_players_mode`: `keep` lub `use_max_override`.

## Zachowanie w przypadku błędów

- Niepoprawne JSON-y i nieautoryzowane payloady są odrzucane.
- Ostrzeżenia są rate‑limitowane (co 10 s na typ problemu + `server_id`).
- Jeżeli konfiguracja się nie wczyta lub `auth_mode=global` bez tokenu, plugin wyłącza mostek.

## Ograniczenia

- Plugin messaging wymaga aktywnego połączenia gracza między proxy a backendem, aby backend mógł wysłać wiadomość.

