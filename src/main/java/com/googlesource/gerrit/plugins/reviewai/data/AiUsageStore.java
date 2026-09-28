/*
 * Copyright (c) 2026. The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.reviewai.data;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists the estimated AI cost per UTC day and Gerrit project in the ReviewAI database, so that
 * budgets and usage reports survive plugin reloads and Gerrit restarts.
 */
@Slf4j
@Singleton
public class AiUsageStore {
  public static final String UNKNOWN_PROJECT = "unknown";

  private final ReviewAiDb db;
  private final Clock clock;
  private volatile boolean schemaReady;

  public enum Period {
    DAY,
    MONTH
  }

  /** Estimated cost of one period: its start ({@code yyyy-MM-dd} or {@code yyyy-MM}) and totals. */
  public record Usage(String period, long totalNanoUsd, Map<String, Long> projectNanoUsd) {
    public Usage {
      projectNanoUsd = Collections.unmodifiableMap(new TreeMap<>(projectNanoUsd));
    }

    public long projectNanoUsd(String project) {
      return projectNanoUsd.getOrDefault(projectKey(project), 0L);
    }
  }

  @Inject
  public AiUsageStore(ReviewAiDb db) {
    this(db, Clock.systemUTC());
  }

  public AiUsageStore(ReviewAiDb db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Clock clock() {
    return clock;
  }

  /** Adds an estimated cost to the current UTC day. Failures are logged, never thrown. */
  public void record(String project, long nanoUsd) {
    record(clock.instant(), project, nanoUsd);
  }

  public void record(Instant at, String project, long nanoUsd) {
    if (nanoUsd <= 0) {
      return;
    }
    String day = day(at);
    String projectKey = projectKey(project);
    try {
      ensureSchema();
      try (Connection connection = db.getConnection()) {
        if (addToExistingRow(connection, day, projectKey, nanoUsd, at)) {
          return;
        }
        try {
          insertRow(connection, day, projectKey, nanoUsd, at);
        } catch (SQLException e) {
          // Another writer inserted the row first; add to it instead.
          if (!addToExistingRow(connection, day, projectKey, nanoUsd, at)) {
            throw e;
          }
        }
      }
    } catch (SQLException | RuntimeException e) {
      log.warn("Failed to persist estimated AI cost for project {} on {}", projectKey, day, e);
    }
  }

  /** Returns the usage of the period that contains {@code at}. */
  public Usage usage(Period period, Instant at) {
    LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
    String first;
    String last;
    String label;
    if (period == Period.DAY) {
      first = date.toString();
      last = first;
      label = first;
    } else {
      YearMonth month = YearMonth.from(date);
      first = month.atDay(1).toString();
      last = month.atEndOfMonth().toString();
      label = month.toString();
    }
    Map<String, Long> projects = new TreeMap<>();
    long total = 0;
    try {
      ensureSchema();
      try (Connection connection = db.getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT project, SUM(cost_nano_usd) FROM ai_usage_costs"
                      + " WHERE usage_day >= ? AND usage_day <= ? GROUP BY project")) {
        statement.setString(1, first);
        statement.setString(2, last);
        try (ResultSet results = statement.executeQuery()) {
          while (results.next()) {
            long cost = results.getLong(2);
            projects.put(results.getString(1), cost);
            total += cost;
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read estimated AI usage", e);
    }
    return new Usage(label, total, projects);
  }

  public Usage usage(Period period) {
    return usage(period, clock.instant());
  }

  static String projectKey(String project) {
    return project == null || project.isBlank() ? UNKNOWN_PROJECT : project;
  }

  private static String day(Instant at) {
    return at.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private void ensureSchema() throws SQLException {
    if (!schemaReady) {
      synchronized (this) {
        if (!schemaReady) {
          db.initAiUsageSchema();
          schemaReady = true;
        }
      }
    }
  }

  private static boolean addToExistingRow(
      Connection connection, String day, String project, long nanoUsd, Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE ai_usage_costs SET cost_nano_usd = cost_nano_usd + ?, updated_at_millis = ?"
                + " WHERE usage_day = ? AND project = ?")) {
      statement.setLong(1, nanoUsd);
      statement.setLong(2, at.toEpochMilli());
      statement.setString(3, day);
      statement.setString(4, project);
      return statement.executeUpdate() > 0;
    }
  }

  private static void insertRow(
      Connection connection, String day, String project, long nanoUsd, Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ai_usage_costs(usage_day, project, cost_nano_usd, updated_at_millis)"
                + " VALUES (?, ?, ?, ?)")) {
      statement.setString(1, day);
      statement.setString(2, project);
      statement.setLong(3, nanoUsd);
      statement.setLong(4, at.toEpochMilli());
      statement.executeUpdate();
    }
  }
}
