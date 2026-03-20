package com.velocityplayercountbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.velocityplayercountbridge.config.BridgeConfig;
import com.velocityplayercountbridge.config.ConfigLoader;
import com.velocityplayercountbridge.logging.BridgeDebugLogger;
import com.velocityplayercountbridge.model.CountPayload;
import com.velocityplayercountbridge.model.ServerCountState;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.betterbox.elasticbuffer_velocity.logging.LogBuffer;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
  private volatile boolean bridgeEnabled = true;
  private BridgeDebugLogger debugLogger;
  private LogBuffer logBuffer;
  private volatile boolean socketRunning;
  private AFUNIXServerSocket serverSocket;
  private Thread socketThread;
  private Path socketPath;

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

    socketPath = Path.of(config.socketPath());
    if (bridgeEnabled) {
      startSocketServer();
      logger.info("Velocity Player Count Bridge initialized (socket_path={}).", socketPath);
    } else {
      logger.warn("Velocity Player Count Bridge disabled; socket server will not start.");
    }

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

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    shutdownSocketServer();
  }

  private void startSocketServer() {
    socketRunning = true;
    socketThread = new Thread(this::runSocketServer, "VelocityPlayerCountBridge-UDS");
    socketThread.setDaemon(true);
    socketThread.start();
  }

  private void runSocketServer() {
    try {
      Files.deleteIfExists(socketPath);
    } catch (IOException exception) {
      logger.warn("Failed to delete existing socket file at {}.", socketPath, exception);
    }

    try (AFUNIXServerSocket socket = AFUNIXServerSocket.newInstance()) {
      socket.bind(AFUNIXSocketAddress.of(socketPath));
      serverSocket = socket;
      logDebug("Socket server started. socket_path={}", socketPath);
      while (socketRunning) {
        try (AFUNIXSocket client = socket.accept()) {
          handleClient(client);
        } catch (IOException exception) {
          if (socketRunning) {
            logger.warn("Socket accept failed.", exception);
          }
        }
      }
    } catch (IOException exception) {
      logger.error("Failed to start socket server at {}.", socketPath, exception);
    } finally {
      cleanupSocketFile();
    }
  }

  private void handleClient(AFUNIXSocket client) {
    String remote = client.getRemoteSocketAddress() == null ? "local" : client.getRemoteSocketAddress().toString();
    logDebug("Socket client connected. remote={}", remote);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
      String line;
      while (socketRunning && (line = reader.readLine()) != null) {
        PayloadResult result = handlePayloadLine(line);
        writer.write(result.response());
        writer.newLine();
        writer.flush();
      }
    } catch (IOException exception) {
      if (socketRunning) {
        logDebug("Socket client connection error. error={}", exception.getMessage());
      }
    }
  }

  private PayloadResult handlePayloadLine(String payloadText) {
    if (!bridgeEnabled || config == null) {
      return PayloadResult.INVALID;
    }
    if (payloadText == null || payloadText.isBlank()) {
      warnRateLimited("invalid-empty", "Empty payload received on socket {}", socketPath);
      logDebug("Socket payload rejected: empty line.");
      return PayloadResult.INVALID;
    }

    logDebug("Socket payload received. bytes={} payload={}", payloadText.getBytes(StandardCharsets.UTF_8).length,
        payloadText);
    CountPayload payload;
    try {
      payload = gson.fromJson(payloadText, CountPayload.class);
    } catch (JsonSyntaxException exception) {
      warnRateLimited("invalid-json", "Invalid JSON payload on socket {}", socketPath);
      logDebug("Socket payload rejected: invalid JSON. error={}", exception.getMessage());
      return PayloadResult.INVALID;
    }

    if (payload == null) {
      warnRateLimited("null-payload", "Empty payload received on socket {}", socketPath);
      logDebug("Socket payload rejected: payload deserialized to null.");
      return PayloadResult.INVALID;
    }

    String serverId = payload.server_id == null ? "" : payload.server_id.trim();
    if (serverId.isEmpty()) {
      warnRateLimited("missing-server-id", "Payload missing server_id; ignoring.");
      logDebug("Socket payload rejected: missing server_id. payload={}", payloadText);
      return PayloadResult.INVALID;
    }

    if (!Objects.equals(config.protocol(), payload.protocol)) {
      warnRateLimited("protocol-" + serverId, "Protocol mismatch for server {}", serverId);
      logDebug("Socket payload rejected: protocol mismatch. server_id={} expected_protocol={} received_protocol={}",
          serverId, config.protocol(), payload.protocol);
      return PayloadResult.PROTOCOL_MISMATCH;
    }

    if (!isAuthorized(serverId, payload.auth)) {
      warnRateLimited("auth-" + serverId, "Rejected payload for server {} due to auth failure.", serverId);
      logDebug("Socket payload rejected: auth failure. server_id={} auth_mode={} auth_present={} auth_preview={}",
          serverId, config.authMode(), payload.auth != null, maskAuth(payload.auth));
      return PayloadResult.UNAUTHORIZED;
    }

    if (config.allowlistEnabled() && !config.allowedServerIds().contains(serverId)) {
      warnRateLimited("allowlist-" + serverId, "Server {} not in allowlist; ignoring payload.", serverId);
      logDebug("Socket payload rejected: server not in allowlist. server_id={}", serverId);
      return PayloadResult.NOT_ALLOWLISTED;
    }

    logDebug(
        "Socket payload validated. server_id={} timestamp_ms={} online_humans={} online_ai={} online_total={} max_players_override={}",
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
        logDebug("Socket payload ignored: out-of-order timestamp. server_id={} incoming={} last={}", serverId,
            payload.timestamp_ms, state.lastTimestampMs());
        return PayloadResult.OK;
      }
      // Accept idempotent updates where timestamp_ms is equal to lastTimestampMs.
      state.update(now, payload.timestamp_ms, onlineHumans, onlineAi, onlineTotal, maxPlayersOverride);
    }

    if (config.debug()) {
      logger.debug("Accepted payload for {}: total={}, humans={}, ai={}, max_override={}", serverId, onlineTotal,
          onlineHumans, onlineAi, maxPlayersOverride);
    }
    logDebug("Socket payload accepted. server_id={} total={} humans={} ai={} max_override={}", serverId, onlineTotal,
        onlineHumans, onlineAi, maxPlayersOverride);
    return PayloadResult.OK;
  }

  private void shutdownSocketServer() {
    socketRunning = false;
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (IOException exception) {
        logger.warn("Failed to close socket server.", exception);
      }
    }
    if (socketThread != null) {
      socketThread.interrupt();
      try {
        socketThread.join(2000L);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    cleanupSocketFile();
  }

  private void cleanupSocketFile() {
    if (socketPath == null) {
      return;
    }
    try {
      Files.deleteIfExists(socketPath);
    } catch (IOException exception) {
      logger.warn("Failed to delete socket file at {}.", socketPath, exception);
    }
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

  private enum PayloadResult {
    OK("ok"),
    UNAUTHORIZED("unauthorized"),
    INVALID("invalid"),
    PROTOCOL_MISMATCH("protocol_mismatch"),
    NOT_ALLOWLISTED("not_allowlisted");

    private final String response;

    PayloadResult(String response) {
      this.response = response;
    }

    public String response() {
      return response;
    }
  }
}
