package com.velocityplayercountbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.velocityplayercountbridge.config.BridgeConfig;
import com.velocityplayercountbridge.config.ConfigLoader;
import com.velocityplayercountbridge.model.CountPayload;
import com.velocityplayercountbridge.model.ServerCountState;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.slf4j.Logger;

@Plugin(
    id = "velocity-player-count-bridge",
    name = "Velocity Player Count Bridge",
    version = "1.0.0",
    description = "Bridges backend AI player counts into Velocity proxy ping.")
public class VelocityPlayerCountBridge {
  private static final long WARN_COOLDOWN_MS = 10_000L;

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final Gson gson = new Gson();

  private final Map<String, ServerCountState> serverStates = new ConcurrentHashMap<>();
  private final Map<String, Long> warnTimestamps = new ConcurrentHashMap<>();

  private BridgeConfig config;
  private ChannelIdentifier channelIdentifier;
  private volatile boolean bridgeEnabled = true;

  @Inject
  public VelocityPlayerCountBridge(ProxyServer proxy, Logger logger, @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    ConfigLoader loader = new ConfigLoader(dataDirectory, logger);
    try {
      config = loader.load();
    } catch (IOException exception) {
      bridgeEnabled = false;
      logger.error("Failed to load config.toml; bridge disabled.", exception);
      return;
    }

    if (config.authMode() == BridgeConfig.AuthMode.GLOBAL && config.globalToken().isEmpty()) {
      bridgeEnabled = false;
      logger.error("auth_mode is set to global but global_token is empty. Bridge disabled until configured.");
    }

    channelIdentifier = MinecraftChannelIdentifier.from(config.channel());
    proxy.getChannelRegistrar().register(channelIdentifier);
    logger.info("Velocity Player Count Bridge initialized on channel {}.", config.channel());
  }

  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (!bridgeEnabled || config == null) {
      return;
    }
    if (!Objects.equals(event.getIdentifier(), channelIdentifier)) {
      return;
    }
    if (!(event.getSource() instanceof ServerConnection)) {
      return;
    }

    String payloadText = new String(event.getData(), StandardCharsets.UTF_8);
    CountPayload payload;
    try {
      payload = gson.fromJson(payloadText, CountPayload.class);
    } catch (JsonSyntaxException exception) {
      warnRateLimited("invalid-json", "Invalid JSON payload on channel {}", config.channel());
      return;
    }

    if (payload == null) {
      warnRateLimited("null-payload", "Empty payload received on channel {}", config.channel());
      return;
    }

    String serverId = payload.server_id == null ? "" : payload.server_id.trim();
    if (serverId.isEmpty()) {
      warnRateLimited("missing-server-id", "Payload missing server_id; ignoring.");
      return;
    }

    if (!Objects.equals(config.protocol(), payload.protocol)) {
      warnRateLimited("protocol-" + serverId, "Protocol mismatch for server {}", serverId);
      return;
    }

    if (!isAuthorized(serverId, payload.auth)) {
      warnRateLimited("auth-" + serverId, "Rejected payload for server {} due to auth failure.", serverId);
      return;
    }

    if (config.allowlistEnabled() && !config.allowedServerIds().contains(serverId)) {
      warnRateLimited("allowlist-" + serverId, "Server {} not in allowlist; ignoring payload.", serverId);
      return;
    }

    int onlineHumans = Math.max(0, payload.online_humans);
    int onlineAi = Math.max(0, payload.online_ai);
    int onlineTotal = Math.max(0, payload.online_total);
    int maxPlayersOverride = Math.max(0, payload.max_players_override);
    int minTotal = onlineHumans + onlineAi;
    if (onlineTotal < minTotal) {
      onlineTotal = minTotal;
    }

    long now = System.currentTimeMillis();
    ServerCountState state = serverStates.computeIfAbsent(serverId, ServerCountState::new);
    synchronized (state) {
      if (payload.timestamp_ms < state.lastTimestampMs()) {
        if (config.debug()) {
          logger.debug("Out-of-order payload for {} ignored ({} < {}).", serverId, payload.timestamp_ms,
              state.lastTimestampMs());
        }
        return;
      }
      // Accept idempotent updates where timestamp_ms is equal to lastTimestampMs.
      state.update(now, payload.timestamp_ms, onlineHumans, onlineAi, onlineTotal, maxPlayersOverride);
    }

    if (config.debug()) {
      logger.debug("Accepted payload for {}: total={}, humans={}, ai={}, max_override={}", serverId, onlineTotal,
          onlineHumans, onlineAi, maxPlayersOverride);
    }
  }

  @Subscribe
  public void onProxyPing(ProxyPingEvent event) {
    if (!bridgeEnabled || config == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long staleAfterMs = config.staleAfterMs();
    long onlineTotalSum = 0L;
    int maxOverride = 0;

    for (ServerCountState state : serverStates.values()) {
      boolean active;
      synchronized (state) {
        active = (now - state.lastSeenMs()) <= staleAfterMs;
        if (active) {
          onlineTotalSum += state.onlineTotal();
          maxOverride = Math.max(maxOverride, state.maxPlayersOverride());
        }
      }
    }

    int onlineTotal = (int) Math.min(Integer.MAX_VALUE, onlineTotalSum);
    ServerPing.Builder builder = event.getPing().asBuilder().onlinePlayers(onlineTotal);

    if (config.maxPlayersMode() == BridgeConfig.MaxPlayersMode.USE_MAX_OVERRIDE && maxOverride > 0) {
      builder.maximumPlayers(maxOverride);
    }

    event.setPing(builder.build());
  }

  private boolean isAuthorized(String serverId, String auth) {
    if (config.authMode() == BridgeConfig.AuthMode.GLOBAL) {
      return !config.globalToken().isEmpty() && Objects.equals(config.globalToken(), auth);
    }
    String expected = config.serverTokens().get(serverId);
    return expected != null && Objects.equals(expected, auth);
  }

  private void warnRateLimited(String key, String message, Object... args) {
    long now = System.currentTimeMillis();
    Long last = warnTimestamps.get(key);
    if (last != null && (now - last) < WARN_COOLDOWN_MS) {
      return;
    }
    warnTimestamps.put(key, now);
    logger.warn(message, args);
  }
}
