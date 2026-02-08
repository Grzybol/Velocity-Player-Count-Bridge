package com.velocityplayercountbridge.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class BridgeConfig {
  private final String channel;
  private final String protocol;
  private final long staleAfterMs;
  private final boolean debug;
  private final String socketPath;
  private final AuthMode authMode;
  private final String globalToken;
  private final Map<String, String> serverTokens;
  private final boolean allowlistEnabled;
  private final Set<String> allowedServerIds;
  private final MaxPlayersMode maxPlayersMode;
  private final int maxPlayersOverride;
  private final PlannerApiConfig plannerApi;

  public BridgeConfig(
      String channel,
      String protocol,
      long staleAfterMs,
      boolean debug,
      String socketPath,
      AuthMode authMode,
      String globalToken,
      Map<String, String> serverTokens,
      boolean allowlistEnabled,
      List<String> allowedServerIds,
      MaxPlayersMode maxPlayersMode,
      int maxPlayersOverride,
      PlannerApiConfig plannerApi) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    this.staleAfterMs = staleAfterMs;
    this.debug = debug;
    this.socketPath = socketPath == null || socketPath.isBlank()
        ? "/tmp/velocity-player-count-bridge.sock"
        : socketPath.trim();
    this.authMode = Objects.requireNonNull(authMode, "authMode");
    this.globalToken = globalToken == null ? "" : globalToken;
    this.serverTokens = serverTokens == null ? Collections.emptyMap() : Map.copyOf(serverTokens);
    this.allowlistEnabled = allowlistEnabled;
    this.allowedServerIds = allowedServerIds == null
        ? Collections.emptySet()
        : allowedServerIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    this.maxPlayersMode = Objects.requireNonNull(maxPlayersMode, "maxPlayersMode");
    this.maxPlayersOverride = Math.max(0, maxPlayersOverride);
    this.plannerApi = plannerApi == null ? PlannerApiConfig.disabled() : plannerApi;
  }

  public String channel() {
    return channel;
  }

  public String protocol() {
    return protocol;
  }

  public long staleAfterMs() {
    return staleAfterMs;
  }

  public boolean debug() {
    return debug;
  }

  public String socketPath() {
    return socketPath;
  }

  public AuthMode authMode() {
    return authMode;
  }

  public String globalToken() {
    return globalToken;
  }

  public Map<String, String> serverTokens() {
    return serverTokens;
  }

  public boolean allowlistEnabled() {
    return allowlistEnabled;
  }

  public Set<String> allowedServerIds() {
    return allowedServerIds;
  }

  public MaxPlayersMode maxPlayersMode() {
    return maxPlayersMode;
  }

  public int maxPlayersOverride() {
    return maxPlayersOverride;
  }

  public PlannerApiConfig plannerApi() {
    return plannerApi;
  }

  public enum AuthMode {
    GLOBAL,
    PER_SERVER;

    public static AuthMode fromString(String value) {
      if (value == null) {
        return PER_SERVER;
      }
      return switch (value.trim().toLowerCase()) {
        case "global" -> GLOBAL;
        case "per_server" -> PER_SERVER;
        default -> PER_SERVER;
      };
    }
  }

  public enum MaxPlayersMode {
    KEEP,
    USE_MAX_OVERRIDE;

    public static MaxPlayersMode fromString(String value) {
      if (value == null) {
        return KEEP;
      }
      return switch (value.trim().toLowerCase()) {
        case "keep" -> KEEP;
        case "use_max_override", "max_of_overrides" -> USE_MAX_OVERRIDE;
        default -> KEEP;
      };
    }
  }

  public static final class PlannerApiConfig {
    private final boolean enabled;
    private final String bindAddress;
    private final int port;
    private final String authToken;
    private final String planPath;
    private final String engagementPath;

    public PlannerApiConfig(boolean enabled, String bindAddress, int port, String authToken, String planPath,
        String engagementPath) {
      this.enabled = enabled;
      this.bindAddress = (bindAddress == null || bindAddress.isBlank()) ? "0.0.0.0" : bindAddress.trim();
      this.port = Math.max(1, Math.min(65535, port));
      this.authToken = authToken == null ? "" : authToken;
      this.planPath = normalizePath(planPath, "/v1/plan");
      this.engagementPath = normalizePath(engagementPath, "/v1/engagement");
    }

    public static PlannerApiConfig disabled() {
      return new PlannerApiConfig(false, "0.0.0.0", 8080, "", "/v1/plan", "/v1/engagement");
    }

    public boolean enabled() {
      return enabled;
    }

    public String bindAddress() {
      return bindAddress;
    }

    public int port() {
      return port;
    }

    public String authToken() {
      return authToken;
    }

    public String planPath() {
      return planPath;
    }

    public String engagementPath() {
      return engagementPath;
    }

    private static String normalizePath(String path, String fallback) {
      String normalized = path == null || path.isBlank() ? fallback : path.trim();
      if (!normalized.startsWith("/")) {
        normalized = "/" + normalized;
      }
      return normalized;
    }
  }
}
