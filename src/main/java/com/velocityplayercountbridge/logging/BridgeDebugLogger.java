package com.velocityplayercountbridge.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

import org.betterbox.elasticbuffer_velocity.logging.LogBuffer;

import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

public final class BridgeDebugLogger {
  private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final DateTimeFormatter LINE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private final Logger fallbackLogger;
  private final Path logFile;
  private final AtomicBoolean writeFailed = new AtomicBoolean(false);
  private final AtomicBoolean bufferFailed = new AtomicBoolean(false);
  private final LogBuffer logBuffer;
  private final String pluginName;

  public BridgeDebugLogger(Logger fallbackLogger, Path logsDirectory, Instant startInstant, LogBuffer logBuffer,
      String pluginName) throws IOException {
    this.fallbackLogger = fallbackLogger;
    this.logBuffer = logBuffer;
    this.pluginName = pluginName;
    Files.createDirectories(logsDirectory);
    String filename = "velocity-bridge-" + FILE_TIMESTAMP_FORMAT.format(LocalDateTime.ofInstant(startInstant, ZoneId.systemDefault())) + ".log";
    this.logFile = logsDirectory.resolve(filename);
    Files.writeString(logFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  public Path logFile() {
    return logFile;
  }

  public void log(String message, Object... args) {
    String formatted = MessageFormatter.arrayFormat(message, args).getMessage();
    if (logBuffer != null && !bufferFailed.get()) {
      try {
        logBuffer.add(
            formatted,
            "DEBUG",
            pluginName,
            System.currentTimeMillis(),
            "N/A",
            "N/A",
            "N/A",
            0.0);
        return;
      } catch (Exception exception) {
        if (bufferFailed.compareAndSet(false, true)) {
          fallbackLogger.warn("Failed to send debug logs to ElasticBuffer; falling back to file logging.", exception);
        }
      }
    }

    String line = LINE_TIMESTAMP_FORMAT.format(LocalDateTime.now()) + " " + formatted + System.lineSeparator();
    try {
      synchronized (this) {
        Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      }
    } catch (IOException exception) {
      if (writeFailed.compareAndSet(false, true)) {
        fallbackLogger.warn("Failed to write debug log to {}.", logFile, exception);
      }
    }
  }
}
