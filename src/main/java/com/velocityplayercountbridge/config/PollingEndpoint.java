package com.velocityplayercountbridge.config;

import java.util.Objects;

public class PollingEndpoint {
  private final String url;
  private final String authHeader;

  public PollingEndpoint(String url, String authHeader) {
    this.url = Objects.requireNonNull(url, "url").trim();
    this.authHeader = authHeader == null ? "" : authHeader.trim();
  }

  public String url() {
    return url;
  }

  public String authHeader() {
    return authHeader;
  }
}
