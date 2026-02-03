package com.velocityplayercountbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.velocityplayercountbridge.config.BridgeConfig;
import com.velocityplayercountbridge.config.ConfigLoader;
import com.velocityplayercountbridge.logging.BridgeDebugLogger;
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
import com.velocitypowered.api.proxy.services.RegisteredServiceProvider;

import org.betterbox.elasticbuffer_velocity.logging.LogBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
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
  private BridgeDebugLogger debugLogger;
  private LogBuffer logBuffer;

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
      logger.error("Failed to load config.yml; bridge disabled.", exception);
      return;
    }

    if (config.authMode() == BridgeConfig.AuthMode.GLOBAL && config.globalToken().isEmpty()) {
      bridgeEnabled = false;
      logger.error("auth_mode is set to global but global_token is empty. Bridge disabled until configured.");
    }

    logBuffer = proxy.getServicesManager()
        .getProvider(LogBuffer.class)
        .map(RegisteredServiceProvider::getProvider)
        .orElse(null);
    if (logBuffer != null) {
      logger.info("ElasticBuffer detected; bridge debug logs will be forwarded.");
    } else {
      logger.info("ElasticBuffer not detected; bridge debug logs will stay on disk.");
    }

    Path logsDirectory = dataDirectory.resolve("logs");
    try {
      debugLogger = new BridgeDebugLogger(logger, logsDirectory, Instant.now(), logBuffer, "VelocityPlayerCountBridge");
      logger.info("Bridge debug logs will be written to {}.", debugLogger.logFile());
    } catch (IOException exception) {
      logger.warn("Failed to initialize bridge debug log file in {}; continuing without file logging.",
          logsDirectory, exception);
    }

    channelIdentifier = MinecraftChannelIdentifier.from(config.channel());
    proxy.getChannelRegistrar().register(channelIdentifier);
    logger.info("Velocity Player Count Bridge initialized on channel {}.", config.channel());

    logDebug("Proxy initialized. channel={} protocol={} auth_mode={} allowlist_enabled={} max_players_mode={} max_players_override={} stale_after_ms={}",
        config.channel(),
        config.protocol(),
        config.authMode(),
        config.allowlistEnabled(),
        config.maxPlayersMode(),
        config.maxPlayersOverride(),
        config.staleAfterMs());
  }

  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (!bridgeEnabled || config == null) {
      return;
    }
    if (!Objects.equals(event.getIdentifier(), channelIdentifier)) {
      logDebug("PluginMessage ignored: channel mismatch. received_channel={} expected_channel={}",
          event.getIdentifier().getId(), config.channel());
      return;
    }
    if (!(event.getSource() instanceof ServerConnection)) {
      logDebug("PluginMessage ignored: source not ServerConnection. source_type={}",
          event.getSource() == null ? "null" : event.getSource().getClass().getName());
      return;
    }

    String payloadText = new String(event.getData(), StandardCharsets.UTF_8);
    logDebug("PluginMessage received. channel={} bytes={} payload={}", config.channel(), event.getData().length,
        payloadText);
    CountPayload payload;
    try {
      payload = gson.fromJson(payloadText, CountPayload.class);
    } catch (JsonSyntaxException exception) {
      warnRateLimited("invalid-json", "Invalid JSON payload on channel {}", config.channel());
      logDebug("PluginMessage rejected: invalid JSON. error={}", exception.getMessage());
      return;
    }

    if (payload == null) {
      warnRateLimited("null-payload", "Empty payload received on channel {}", config.channel());
      logDebug("PluginMessage rejected: payload deserialized to null.");
      return;
    }

    String serverId = payload.server_id == null ? "" : payload.server_id.trim();
    if (serverId.isEmpty()) {
      warnRateLimited("missing-server-id", "Payload missing server_id; ignoring.");
      logDebug("PluginMessage rejected: missing server_id. payload={}", payloadText);
      return;
    }

    if (!Objects.equals(config.protocol(), payload.protocol)) {
      warnRateLimited("protocol-" + serverId, "Protocol mismatch for server {}", serverId);
      logDebug("PluginMessage rejected: protocol mismatch. server_id={} expected_protocol={} received_protocol={}",
          serverId, config.protocol(), payload.protocol);
      return;
    }

    if (!isAuthorized(serverId, payload.auth)) {
      warnRateLimited("auth-" + serverId, "Rejected payload for server {} due to auth failure.", serverId);
      logDebug("PluginMessage rejected: auth failure. server_id={} auth_mode={} auth_present={} auth_preview={}",
          serverId, config.authMode(), payload.auth != null, maskAuth(payload.auth));
      return;
    }

    if (config.allowlistEnabled() && !config.allowedServerIds().contains(serverId)) {
      warnRateLimited("allowlist-" + serverId, "Server {} not in allowlist; ignoring payload.", serverId);
      logDebug("PluginMessage rejected: server not in allowlist. server_id={}", serverId);
      return;
    }

    logDebug(
        "PluginMessage validated. server_id={} timestamp_ms={} online_humans={} online_ai={} online_total={} max_players_override={}",
        serverId, payload.timestamp_ms, payload.online_humans, payload.online_ai, payload.online_total,
        payload.max_players_override);

    if (payload.online_humans < 0 || payload.online_ai < 0 || payload.online_total < 0
        || payload.max_players_override < 0) {
      warnRateLimited("negative-values-" + serverId,
          "Negative player counts reported by {} (humans={}, ai={}, total={}, max_override={}).",
          serverId, payload.online_humans, payload.online_ai, payload.online_total, payload.max_players_override);
    }

    int onlineHumans = Math.max(0, payload.online_humans);
    int onlineAi = Math.max(0, payload.online_ai);
    int onlineTotal = Math.max(0, payload.online_total);
    int maxPlayersOverride = Math.max(0, payload.max_players_override);
    int minTotal = onlineHumans + onlineAi;
    if (onlineTotal < minTotal) {
      warnRateLimited("total-underflow-" + serverId,
          "Payload total lower than humans+ai for {} (total={}, humans={}, ai={}); correcting to {}.",
          serverId, onlineTotal, onlineHumans, onlineAi, minTotal);
      onlineTotal = minTotal;
    }

    if (config.maxPlayersOverride() > 0 && onlineTotal > config.maxPlayersOverride()) {
      warnRateLimited("total-over-max-" + serverId,
          "Reported total for {} ({}) exceeds configured max_players_override ({}).",
          serverId, onlineTotal, config.maxPlayersOverride());
    }

    long now = System.currentTimeMillis();
    ServerCountState state = serverStates.computeIfAbsent(serverId, ServerCountState::new);
    synchronized (state) {
      if (payload.timestamp_ms < state.lastTimestampMs()) {
        if (config.debug()) {
          logger.debug("Out-of-order payload for {} ignored ({} < {}).", serverId, payload.timestamp_ms,
              state.lastTimestampMs());
        }
        logDebug("PluginMessage ignored: out-of-order timestamp. server_id={} incoming={} last={}", serverId,
            payload.timestamp_ms, state.lastTimestampMs());
        return;
      }
      // Accept idempotent updates where timestamp_ms is equal to lastTimestampMs.
      state.update(now, payload.timestamp_ms, onlineHumans, onlineAi, onlineTotal, maxPlayersOverride);
    }

    if (config.debug()) {
      logger.debug("Accepted payload for {}: total={}, humans={}, ai={}, max_override={}", serverId, onlineTotal,
          onlineHumans, onlineAi, maxPlayersOverride);
    }
    logDebug("PluginMessage accepted. server_id={} total={} humans={} ai={} max_override={}", serverId, onlineTotal,
        onlineHumans, onlineAi, maxPlayersOverride);
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

    if (config.maxPlayersMode() == BridgeConfig.MaxPlayersMode.USE_MAX_OVERRIDE) {
      int effectiveMaxOverride = maxOverride;
      if (config.maxPlayersOverride() > 0) {
        effectiveMaxOverride = config.maxPlayersOverride();
      }
      if (effectiveMaxOverride > 0) {
        builder.maximumPlayers(effectiveMaxOverride);
      }
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

  private void logDebug(String message, Object... args) {
    if (debugLogger == null) {
      return;
    }
    debugLogger.log(message, args);
  }

  private String maskAuth(String auth) {
    if (auth == null || auth.isEmpty()) {
      return "<empty>";
    }
    if (auth.length() <= 4) {
      return "****";
    }
    return auth.substring(0, 2) + "..." + auth.substring(auth.length() - 2);
  }
}
