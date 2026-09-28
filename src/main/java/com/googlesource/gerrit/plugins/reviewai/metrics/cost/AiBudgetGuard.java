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

package com.googlesource.gerrit.plugins.reviewai.metrics.cost;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiUsageStore;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether an AI request may run given the configured budgets and the persisted estimated
 * cost. Automatic requests stop at 100 % of a budget; manual requests may continue up to {@link
 * #MANUAL_OVERRUN_FACTOR} times the budget. A budget that is unset or zero never blocks.
 */
@Slf4j
@Singleton
public class AiBudgetGuard {
  public static final double MANUAL_OVERRUN_FACTOR = 1.2;
  private static final double NANO_USD_PER_USD = 1_000_000_000d;

  private final AiUsageStore usageStore;

  public enum Origin {
    AUTOMATIC,
    MANUAL
  }

  /** Configured budgets in USD; a value of zero means no limit. */
  public record Limits(double dailyUsd, double monthlyUsd, double projectMonthlyUsd) {
    public static Limits from(Configuration config) {
      return new Limits(
          config.getAiBudgetDailyUsd(),
          config.getAiBudgetMonthlyUsd(),
          config.getAiBudgetProjectMonthlyUsd());
    }

    public boolean unlimited() {
      return dailyUsd <= 0 && monthlyUsd <= 0 && projectMonthlyUsd <= 0;
    }
  }

  /** The budget that blocks a request, with the spent amount and the budget in USD. */
  public record Exhausted(String budget, String configKey, double spentUsd, double limitUsd) {}

  @Inject
  public AiBudgetGuard(AiUsageStore usageStore) {
    this.usageStore = usageStore;
  }

  /**
   * Returns the exhausted budget that blocks a request of the given origin in the given project, or
   * empty when the request may run. If the usage cannot be read, the request is allowed.
   */
  public Optional<Exhausted> check(Configuration config, String project, Origin origin) {
    Limits limits = Limits.from(config);
    if (limits.unlimited()) {
      return Optional.empty();
    }
    try {
      Instant now = usageStore.clock().instant();
      long dayNanoUsd =
          limits.dailyUsd() > 0 ? usageStore.usage(AiUsageStore.Period.DAY, now).totalNanoUsd() : 0;
      AiUsageStore.Usage month =
          limits.monthlyUsd() > 0 || limits.projectMonthlyUsd() > 0
              ? usageStore.usage(AiUsageStore.Period.MONTH, now)
              : null;
      return evaluate(
          limits,
          dayNanoUsd,
          month == null ? 0 : month.totalNanoUsd(),
          month == null ? 0 : month.projectNanoUsd(project),
          origin);
    } catch (RuntimeException e) {
      log.warn("Could not read AI usage; not enforcing AI budgets for project {}", project, e);
      return Optional.empty();
    }
  }

  @VisibleForTesting
  static Optional<Exhausted> evaluate(
      Limits limits, long dayNanoUsd, long monthNanoUsd, long projectMonthNanoUsd, Origin origin) {
    double factor = origin == Origin.AUTOMATIC ? 1.0 : MANUAL_OVERRUN_FACTOR;
    Optional<Exhausted> exhausted =
        exhausted(
            "daily", Configuration.KEY_AI_BUDGET_DAILY_USD, limits.dailyUsd(), dayNanoUsd, factor);
    if (exhausted.isEmpty()) {
      exhausted =
          exhausted(
              "monthly",
              Configuration.KEY_AI_BUDGET_MONTHLY_USD,
              limits.monthlyUsd(),
              monthNanoUsd,
              factor);
    }
    if (exhausted.isEmpty()) {
      exhausted =
          exhausted(
              "project monthly",
              Configuration.KEY_AI_BUDGET_PROJECT_MONTHLY_USD,
              limits.projectMonthlyUsd(),
              projectMonthNanoUsd,
              factor);
    }
    return exhausted;
  }

  public static double toUsd(long nanoUsd) {
    return nanoUsd / NANO_USD_PER_USD;
  }

  private static Optional<Exhausted> exhausted(
      String budget, String configKey, double limitUsd, long spentNanoUsd, double factor) {
    if (limitUsd <= 0) {
      return Optional.empty();
    }
    if (spentNanoUsd < Math.round(limitUsd * factor * NANO_USD_PER_USD)) {
      return Optional.empty();
    }
    return Optional.of(new Exhausted(budget, configKey, toUsd(spentNanoUsd), limitUsd));
  }
}
