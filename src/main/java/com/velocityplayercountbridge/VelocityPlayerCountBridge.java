package com.velocityplayercountbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.velocityplayercountbridge.config.BridgeConfig;
import com.velocityplayercountbridge.config.ConfigLoader;
import com.velocityplayercountbridge.config.PollingEndpoint;
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
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.betterbox.elasticbuffer_velocity.logging.LogBuffer;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
  private final HttpClient httpClient = HttpClient.newBuilder().build();

  private final Map<String, ServerCountState> serverStates = new ConcurrentHashMap<>();
  private final Map<String, Long> warnTimestamps = new ConcurrentHashMap<>();

  private BridgeConfig config;
  private ChannelIdentifier channelIdentifier;
  private volatile boolean bridgeEnabled = true;
  private BridgeDebugLogger debugLogger;
  private LogBuffer logBuffer;
  private ScheduledTask pollingTask;

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

    logBuffer = resolveLogBuffer();
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

    startPolling();
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

    handlePayload(payload, payloadText, "plugin-message", null);
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

  private LogBuffer resolveLogBuffer() {
    try {
      Method getServicesManager = proxy.getClass().getMethod("getServicesManager");
      Object servicesManager = getServicesManager.invoke(proxy);
      if (servicesManager == null) {
        return null;
      }
      Method getProvider = servicesManager.getClass().getMethod("getProvider", Class.class);
      Object providerOptional = getProvider.invoke(servicesManager, LogBuffer.class);
      if (!(providerOptional instanceof Optional<?> optional)) {
        return null;
      }
      Object provider = optional.orElse(null);
      if (provider == null) {
        return null;
      }
      Method getProviderMethod = provider.getClass().getMethod("getProvider");
      Object resolved = getProviderMethod.invoke(provider);
      if (resolved instanceof LogBuffer buffer) {
        return buffer;
      }
    } catch (NoSuchMethodException ignored) {
      return null;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      logger.warn("Failed to resolve ElasticBuffer service provider.", exception);
      return null;
    }
    return null;
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

  private void startPolling() {
    if (!bridgeEnabled || config == null) {
      return;
    }
    if (!config.pollingEnabled()) {
      return;
    }
    if (config.pollingEndpoints().isEmpty()) {
      logger.warn("Polling enabled but no endpoints configured; skipping scheduler.");
      return;
    }
    long intervalSeconds = config.pollingIntervalSeconds();
    pollingTask = proxy.getScheduler().buildTask(this, this::pollEndpoints)
        .repeat(intervalSeconds, TimeUnit.SECONDS)
        .schedule();
    logger.info("HTTP polling enabled: {} endpoints every {}s.", config.pollingEndpoints().size(), intervalSeconds);
  }

  private void pollEndpoints() {
    if (!bridgeEnabled || config == null) {
      return;
    }
    for (Map.Entry<String, PollingEndpoint> entry : config.pollingEndpoints().entrySet()) {
      pollEndpoint(entry.getKey(), entry.getValue());
    }
  }

  private void pollEndpoint(String endpointId, PollingEndpoint endpoint) {
    URI uri;
    try {
      uri = URI.create(endpoint.url());
    } catch (IllegalArgumentException exception) {
      warnRateLimited("poll-url-" + endpointId, "Invalid polling URL for {}: {}", endpointId, endpoint.url());
      logDebug("Polling rejected: invalid URL. endpoint_id={} url={} error={}", endpointId, endpoint.url(),
          exception.getMessage());
      return;
    }

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(config.pollingRequestTimeoutMs()))
        .GET()
        .header("Accept", "application/json");
    if (!endpoint.authHeader().isEmpty()) {
      requestBuilder.header("Authorization", endpoint.authHeader());
    }

    httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .whenComplete((response, error) -> {
          if (error != null) {
            warnRateLimited("poll-error-" + endpointId, "Polling failed for {}: {}", endpointId, error.getMessage());
            logDebug("Polling error. endpoint_id={} error={}", endpointId, error.getMessage());
            return;
          }
          int status = response.statusCode();
          if (status < 200 || status >= 300) {
            warnRateLimited("poll-status-" + endpointId, "Polling status {} for {}.", status, endpointId);
            logDebug("Polling rejected: HTTP status. endpoint_id={} status={} body={}", endpointId, status,
                response.body());
            return;
          }
          String payloadText = response.body();
          CountPayload payload;
          try {
            payload = gson.fromJson(payloadText, CountPayload.class);
          } catch (JsonSyntaxException exception) {
            warnRateLimited("poll-json-" + endpointId, "Invalid JSON payload from {}.", endpointId);
            logDebug("Polling rejected: invalid JSON. endpoint_id={} error={}", endpointId, exception.getMessage());
            return;
          }
          if (payload == null) {
            warnRateLimited("poll-null-" + endpointId, "Empty payload received from {}.", endpointId);
            logDebug("Polling rejected: payload deserialized to null. endpoint_id={}", endpointId);
            return;
          }
          handlePayload(payload, payloadText, "http:" + endpointId, endpointId);
        });
  }

  private void handlePayload(CountPayload payload, String payloadText, String source, String fallbackServerId) {
    String serverId = payload.server_id == null ? "" : payload.server_id.trim();
    if (serverId.isEmpty() && fallbackServerId != null && !fallbackServerId.isBlank()) {
      serverId = fallbackServerId.trim();
    }
    if (serverId.isEmpty()) {
      warnRateLimited("missing-server-id-" + source, "Payload missing server_id; ignoring.");
      logDebug("Payload rejected: missing server_id. source={} payload={}", source, payloadText);
      return;
    }

    if (!Objects.equals(config.protocol(), payload.protocol)) {
      warnRateLimited("protocol-" + serverId, "Protocol mismatch for server {}", serverId);
      logDebug("Payload rejected: protocol mismatch. source={} server_id={} expected_protocol={} received_protocol={}",
          source, serverId, config.protocol(), payload.protocol);
      return;
    }

    if (!isAuthorized(serverId, payload.auth)) {
      warnRateLimited("auth-" + serverId, "Rejected payload for server {} due to auth failure.", serverId);
      logDebug("Payload rejected: auth failure. source={} server_id={} auth_mode={} auth_present={} auth_preview={}",
          source, serverId, config.authMode(), payload.auth != null, maskAuth(payload.auth));
      return;
    }

    if (config.allowlistEnabled() && !config.allowedServerIds().contains(serverId)) {
      warnRateLimited("allowlist-" + serverId, "Server {} not in allowlist; ignoring payload.", serverId);
      logDebug("Payload rejected: server not in allowlist. source={} server_id={}", source, serverId);
      return;
    }

    logDebug(
        "Payload validated. source={} server_id={} timestamp_ms={} online_humans={} online_ai={} online_total={} max_players_override={}",
        source, serverId, payload.timestamp_ms, payload.online_humans, payload.online_ai, payload.online_total,
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
    int aiPlayersCap = config.maxPlayersOverride() > 0 ? config.maxPlayersOverride() : maxPlayersOverride;

    if (aiPlayersCap > 0 && onlineAi > aiPlayersCap) {
      warnRateLimited("ai-over-cap-" + serverId,
          "Reported AI count for {} ({}) exceeds max_players_override ({}); capping.",
          serverId, onlineAi, aiPlayersCap);
      onlineAi = aiPlayersCap;
    }

    int minTotal = onlineHumans + onlineAi;
    if (onlineTotal < minTotal) {
      warnRateLimited("total-underflow-" + serverId,
          "Payload total lower than humans+ai for {} (total={}, humans={}, ai={}); correcting to {}.",
          serverId, onlineTotal, onlineHumans, onlineAi, minTotal);
      onlineTotal = minTotal;
    }
    if (aiPlayersCap > 0 && onlineTotal > minTotal) {
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
        logDebug("Payload ignored: out-of-order timestamp. source={} server_id={} incoming={} last={}", source,
            serverId, payload.timestamp_ms, state.lastTimestampMs());
        return;
      }
      // Accept idempotent updates where timestamp_ms is equal to lastTimestampMs.
      state.update(now, payload.timestamp_ms, onlineHumans, onlineAi, onlineTotal, maxPlayersOverride);
    }

    if (config.debug()) {
      logger.debug("Accepted payload for {}: total={}, humans={}, ai={}, max_override={}", serverId, onlineTotal,
          onlineHumans, onlineAi, maxPlayersOverride);
    }
    logDebug("Payload accepted. source={} server_id={} total={} humans={} ai={} max_override={}", source, serverId,
        onlineTotal, onlineHumans, onlineAi, maxPlayersOverride);
  }
}
