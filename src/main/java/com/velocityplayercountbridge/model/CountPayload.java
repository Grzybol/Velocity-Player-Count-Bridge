package com.velocityplayercountbridge.model;

public class CountPayload {
  public String protocol;
  public String server_id;
  public long timestamp_ms;
  public int online_humans;
  public int online_ai;
  public int online_total;
  public int max_players_override;
  public String auth;
}
