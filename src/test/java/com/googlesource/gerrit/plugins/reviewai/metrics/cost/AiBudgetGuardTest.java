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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiUsageStore;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiBudgetGuard.Exhausted;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiBudgetGuard.Limits;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiBudgetGuard.Origin;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.Test;

public class AiBudgetGuardTest extends TestBase {
  private static final long USD = 1_000_000_000L;
  private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

  @Test
  public void unsetBudgetsNeverBlock() {
    Limits unlimited = new Limits(0, 0, 0);

    assertTrue(
        AiBudgetGuard.evaluate(unlimited, 1000 * USD, 1000 * USD, 1000 * USD, Origin.AUTOMATIC)
            .isEmpty());
    assertTrue(
        AiBudgetGuard.evaluate(unlimited, 1000 * USD, 1000 * USD, 1000 * USD, Origin.MANUAL)
            .isEmpty());
  }

  @Test
  public void unsetBudgetsDoNotReadUsage() {
    AiUsageStore store = mock(AiUsageStore.class);

    assertTrue(new AiBudgetGuard(store).check(config(0, 0, 0), "core", Origin.AUTOMATIC).isEmpty());
    verifyNoInteractions(store);
  }

  @Test
  public void automaticRequestsStopAtTheBudget() {
    Limits limits = new Limits(10, 0, 0);

    assertTrue(AiBudgetGuard.evaluate(limits, 10 * USD - 1, 0, 0, Origin.AUTOMATIC).isEmpty());
    Optional<Exhausted> exhausted =
        AiBudgetGuard.evaluate(limits, 10 * USD, 0, 0, Origin.AUTOMATIC);

    assertEquals("daily", exhausted.orElseThrow().budget());
    assertEquals(Configuration.KEY_AI_BUDGET_DAILY_USD, exhausted.orElseThrow().configKey());
    assertEquals(10.0, exhausted.orElseThrow().spentUsd(), 0.0);
    assertEquals(10.0, exhausted.orElseThrow().limitUsd(), 0.0);
  }

  @Test
  public void manualRequestsContinueUntilOneHundredTwentyPercent() {
    Limits limits = new Limits(0, 10, 0);

    assertTrue(AiBudgetGuard.evaluate(limits, 0, 10 * USD, 0, Origin.MANUAL).isEmpty());
    assertTrue(AiBudgetGuard.evaluate(limits, 0, 12 * USD - 1, 0, Origin.MANUAL).isEmpty());
    assertEquals(
        "monthly",
        AiBudgetGuard.evaluate(limits, 0, 12 * USD, 0, Origin.MANUAL).orElseThrow().budget());
  }

  @Test
  public void projectBudgetOnlyCountsTheProject() {
    Limits limits = new Limits(0, 0, 5);

    assertTrue(AiBudgetGuard.evaluate(limits, 0, 100 * USD, 4 * USD, Origin.AUTOMATIC).isEmpty());
    Exhausted exhausted =
        AiBudgetGuard.evaluate(limits, 0, 5 * USD, 5 * USD, Origin.AUTOMATIC).orElseThrow();
    assertEquals("project monthly", exhausted.budget());
    assertEquals(Configuration.KEY_AI_BUDGET_PROJECT_MONTHLY_USD, exhausted.configKey());
  }

  @Test
  public void checksPersistedUsageOfTheCurrentPeriods() {
    AiUsageStore store = new AiUsageStore(getTestReviewAiDb(), Clock.fixed(NOW, ZoneOffset.UTC));
    store.record(Instant.parse("2026-09-27T12:00:00Z"), "core", 3 * USD);
    store.record(NOW, "core", 2 * USD);
    store.record(NOW, "docs", 1 * USD);
    AiBudgetGuard guard = new AiBudgetGuard(store);

    // Daily: 3 USD today, the day before does not count.
    assertTrue(guard.check(config(3.5, 0, 0), "core", Origin.AUTOMATIC).isEmpty());
    assertEquals(
        "daily", guard.check(config(3, 0, 0), "core", Origin.AUTOMATIC).orElseThrow().budget());
    // Monthly: 6 USD this month in total.
    assertEquals(
        "monthly", guard.check(config(0, 6, 0), "docs", Origin.AUTOMATIC).orElseThrow().budget());
    assertTrue(guard.check(config(0, 6, 0), "docs", Origin.MANUAL).isEmpty());
    // Project: core spent 5 USD this month, docs 1 USD.
    assertEquals(
        "project monthly",
        guard.check(config(0, 0, 4), "core", Origin.MANUAL).orElseThrow().budget());
    assertTrue(guard.check(config(0, 0, 4), "docs", Origin.AUTOMATIC).isEmpty());
  }

  @Test
  public void unreadableUsageDoesNotBlock() {
    AiUsageStore store = mock(AiUsageStore.class);
    when(store.clock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
    when(store.usage(any(), any())).thenThrow(new IllegalStateException("db down"));

    assertTrue(new AiBudgetGuard(store).check(config(1, 1, 1), "core", Origin.AUTOMATIC).isEmpty());
  }

  @Test
  public void metricsPersistEstimatedCostForBudgets() {
    AiUsageStore store = new AiUsageStore(getTestReviewAiDb(), Clock.fixed(NOW, ZoneOffset.UTC));
    ReviewAiMetrics metrics = new ReviewAiMetrics();
    metrics.setUsageStore(store);

    metrics.recordAiEstimatedCostNanoUsd("OpenAI", "gpt-5.4", "core", 55_000);

    assertEquals(55_000, store.usage(AiUsageStore.Period.DAY).projectNanoUsd("core"));
  }

  private static Configuration config(double daily, double monthly, double projectMonthly) {
    Configuration config = mock(Configuration.class);
    when(config.getAiBudgetDailyUsd()).thenReturn(daily);
    when(config.getAiBudgetMonthlyUsd()).thenReturn(monthly);
    when(config.getAiBudgetProjectMonthlyUsd()).thenReturn(projectMonthly);
    return config;
  }
}
