package com.velocityplayercountbridge.model;

public class ServerCountState {
  private final String serverId;
  private long lastSeenMs;
  private long lastTimestampMs;
  private int onlineHumans;
  private int onlineAi;
  private int onlineTotal;
  private int maxPlayersOverride;

  public ServerCountState(String serverId) {
    this.serverId = serverId;
  }

  public String serverId() {
    return serverId;
  }

  public long lastSeenMs() {
    return lastSeenMs;
  }

  public long lastTimestampMs() {
    return lastTimestampMs;
  }

  public int onlineHumans() {
    return onlineHumans;
  }

  public int onlineAi() {
    return onlineAi;
  }

  public int onlineTotal() {
    return onlineTotal;
  }

  public int maxPlayersOverride() {
    return maxPlayersOverride;
  }

  public void update(long nowMs, long timestampMs, int onlineHumans, int onlineAi, int onlineTotal,
                     int maxPlayersOverride) {
    this.lastSeenMs = nowMs;
    this.lastTimestampMs = timestampMs;
    this.onlineHumans = onlineHumans;
    this.onlineAi = onlineAi;
    this.onlineTotal = onlineTotal;
    this.maxPlayersOverride = maxPlayersOverride;
  }
}
