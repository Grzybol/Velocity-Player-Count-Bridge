package com.velocityplayercountbridge.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;

import io.leangen.geantyref.TypeToken;

public class ConfigLoader {
  private static final String DEFAULT_RESOURCE = "/config.yml";

  private final Path dataDirectory;
  private final Logger logger;

  public ConfigLoader(Path dataDirectory, Logger logger) {
    this.dataDirectory = dataDirectory;
    this.logger = logger;
  }

  public BridgeConfig load() throws IOException {
    Files.createDirectories(dataDirectory);
    Path configPath = dataDirectory.resolve("config.yml");
    if (Files.notExists(configPath)) {
      writeDefaultConfig(configPath);
    }

    ConfigurationLoader<ConfigurationNode> loader = YamlConfigurationLoader.builder()
        .path(configPath)
        .defaultOptions(options -> options.serializers(TypeSerializerCollection.defaults()))
        .build();
    ConfigurationNode root = loader.load();

    String channel = root.node("channel").getString("aiplayers:count");
    String protocol = root.node("protocol").getString("aiplayers-count-v1");
    long staleAfterMs = root.node("stale_after_ms").getLong(30000L);
    boolean debug = root.node("debug").getBoolean(false);

    String authModeRaw = root.node("auth_mode").getString("per_server");
    String globalToken = root.node("global_token").getString("");
    Map<String, String> serverTokens = root.node("server_tokens")
        .get(new TypeToken<Map<String, String>>() {}, Collections.emptyMap());

    boolean allowlistEnabled = root.node("allowlist_enabled").getBoolean(true);
    List<String> allowedServerIds = root.node("allowed_server_ids")
        .getList(String.class, Collections.emptyList());

    String maxPlayersModeRaw = root.node("max_players_mode").getString("keep");

    return new BridgeConfig(
        channel,
        protocol,
        staleAfterMs,
        debug,
        BridgeConfig.AuthMode.fromString(authModeRaw),
        globalToken,
        serverTokens,
        allowlistEnabled,
        allowedServerIds,
        BridgeConfig.MaxPlayersMode.fromString(maxPlayersModeRaw));
  }

  private void writeDefaultConfig(Path configPath) throws IOException {
    try (InputStream inputStream = getClass().getResourceAsStream(DEFAULT_RESOURCE)) {
      if (inputStream == null) {
        logger.warn("Default config resource {} not found; creating minimal config.", DEFAULT_RESOURCE);
        Files.writeString(configPath, "channel: \"aiplayers:count\"\n", StandardCharsets.UTF_8);
        return;
      }
      Files.copy(inputStream, configPath);
    }
  }
}
