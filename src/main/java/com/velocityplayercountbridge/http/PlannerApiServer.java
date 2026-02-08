package com.velocityplayercountbridge.http;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.velocityplayercountbridge.config.BridgeConfig;
import com.velocityplayercountbridge.logging.BridgeDebugLogger;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlannerApiServer {
  private final BridgeConfig.PlannerApiConfig config;
  private final Logger logger;
  private final BridgeDebugLogger debugLogger;
  private final Gson gson;
  private HttpServer server;
  private ExecutorService executor;

  public PlannerApiServer(BridgeConfig.PlannerApiConfig config, Logger logger, BridgeDebugLogger debugLogger, Gson gson) {
    this.config = config;
    this.logger = logger;
    this.debugLogger = debugLogger;
    this.gson = gson;
  }

  public void start() throws IOException {
    if (!config.enabled()) {
      return;
    }
    InetSocketAddress address = new InetSocketAddress(config.bindAddress(), config.port());
    server = HttpServer.create(address, 0);
    executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);

    PlannerHandler handler = new PlannerHandler();
    server.createContext(config.planPath(), handler);
    server.createContext(config.engagementPath(), handler);
    server.start();
    logDebug("Planner API started. bind_address={} port={} plan_path={} engagement_path={}",
        config.bindAddress(), config.port(), config.planPath(), config.engagementPath());
    logger.info("Planner API listening on {}:{} (plan_path={}, engagement_path={}).",
        config.bindAddress(), config.port(), config.planPath(), config.engagementPath());
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  private void logDebug(String message, Object... args) {
    if (debugLogger == null) {
      return;
    }
    debugLogger.log(message, args);
  }

  private class PlannerHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "method_not_allowed");
        return;
      }
      if (!isAuthorized(exchange.getRequestHeaders())) {
        respond(exchange, 401, "unauthorized");
        return;
      }
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (body.isBlank()) {
        respond(exchange, 400, "invalid");
        return;
      }
      PlannerRequest request;
      try {
        request = gson.fromJson(body, PlannerRequest.class);
      } catch (JsonSyntaxException exception) {
        respond(exchange, 400, "invalid");
        return;
      }
      PlannerResponse response = new PlannerResponse();
      response.request_id = request == null ? null : request.request_id;
      response.actions = Collections.emptyList();
      respond(exchange, 200, gson.toJson(response), "application/json");
    }

    private boolean isAuthorized(Headers headers) {
      String token = config.authToken();
      if (token.isEmpty()) {
        return true;
      }
      String authorization = headers.getFirst("Authorization");
      if (authorization != null && authorization.equals("Bearer " + token)) {
        return true;
      }
      String headerToken = headers.getFirst("X-Auth-Token");
      return headerToken != null && headerToken.equals(token);
    }

    private void respond(HttpExchange exchange, int status, String message) throws IOException {
      respond(exchange, status, message, "text/plain");
    }

    private void respond(HttpExchange exchange, int status, String message, String contentType) throws IOException {
      byte[] payload = message.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
      exchange.sendResponseHeaders(status, payload.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(payload);
      } finally {
        exchange.close();
      }
    }
  }

  private static class PlannerRequest {
    public String request_id;
  }

  private static class PlannerResponse {
    public String request_id;
    public java.util.List<PlannerAction> actions;
  }

  private static class PlannerAction {
    public String bot_id;
    public long send_after_ms;
    public String message;
    public String visibility;
  }
}
