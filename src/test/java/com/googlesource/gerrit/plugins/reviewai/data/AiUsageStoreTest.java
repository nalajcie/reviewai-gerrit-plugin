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

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class AiUsageStoreTest extends TestBase {
  private AiUsageStore store;

  @Before
  public void setUp() {
    store = new AiUsageStore(getTestReviewAiDb());
  }

  @Test
  public void aggregatesCostPerUtcDayAndProject() {
    store.record(Instant.parse("2026-09-28T00:00:00Z"), "core", 100);
    store.record(Instant.parse("2026-09-28T23:59:59Z"), "core", 50);
    store.record(Instant.parse("2026-09-28T12:00:00Z"), "docs", 7);
    // 23:30 on the 28th in UTC-2 is already the 29th in UTC.
    store.record(Instant.parse("2026-09-29T01:30:00Z"), "core", 1000);

    AiUsageStore.Usage day =
        store.usage(AiUsageStore.Period.DAY, Instant.parse("2026-09-28T08:00:00Z"));

    assertEquals("2026-09-28", day.period());
    assertEquals(157, day.totalNanoUsd());
    assertEquals(Map.of("core", 150L, "docs", 7L), day.projectNanoUsd());
    assertEquals(
        1000,
        store.usage(AiUsageStore.Period.DAY, Instant.parse("2026-09-29T00:00:00Z")).totalNanoUsd());
  }

  @Test
  public void aggregatesCostPerUtcMonthAndRollsOver() {
    store.record(Instant.parse("2026-09-01T00:00:00Z"), "core", 10);
    store.record(Instant.parse("2026-09-30T23:59:59Z"), "core", 20);
    store.record(Instant.parse("2026-09-15T10:00:00Z"), "docs", 5);
    store.record(Instant.parse("2026-10-01T00:00:00Z"), "core", 400);
    store.record(Instant.parse("2026-08-31T23:59:59Z"), "core", 3000);

    AiUsageStore.Usage september =
        store.usage(AiUsageStore.Period.MONTH, Instant.parse("2026-09-20T00:00:00Z"));
    AiUsageStore.Usage october =
        store.usage(AiUsageStore.Period.MONTH, Instant.parse("2026-10-02T00:00:00Z"));

    assertEquals("2026-09", september.period());
    assertEquals(35, september.totalNanoUsd());
    assertEquals(30, september.projectNanoUsd("core"));
    assertEquals(5, september.projectNanoUsd("docs"));
    assertEquals(0, september.projectNanoUsd("other"));
    assertEquals("2026-10", october.period());
    assertEquals(Map.of("core", 400L), october.projectNanoUsd());
  }

  @Test
  public void attributesCostWithoutProjectToUnknown() {
    Instant at = Instant.parse("2026-09-28T10:00:00Z");
    store.record(at, null, 3);
    store.record(at, " ", 4);

    assertEquals(
        Map.of(AiUsageStore.UNKNOWN_PROJECT, 7L),
        store.usage(AiUsageStore.Period.DAY, at).projectNanoUsd());
  }

  @Test
  public void ignoresNonPositiveCost() {
    Instant at = Instant.parse("2026-09-28T10:00:00Z");
    store.record(at, "core", 0);
    store.record(at, "core", -5);

    assertEquals(Map.of(), store.usage(AiUsageStore.Period.DAY, at).projectNanoUsd());
  }

  @Test
  public void keepsUsageAcrossStoreAndDatabaseInstances() throws Exception {
    Instant at = Instant.parse("2026-09-28T10:00:00Z");
    store.record(at, "core", 42);

    Path dataDir = tempFolder.getRoot().toPath();
    AiUsageStore reloaded =
        new AiUsageStore(new ReviewAiDb(dataDir, buildEmbeddedTestJdbcUrl(dataDir)));

    assertEquals(42, reloaded.usage(AiUsageStore.Period.MONTH, at).projectNanoUsd("core"));
  }
}
