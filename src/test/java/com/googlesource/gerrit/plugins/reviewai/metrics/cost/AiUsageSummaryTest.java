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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import org.junit.Test;

public class AiUsageSummaryTest {
  private static final String FORMAT = "%s | %d | %s | %s | $%s";

  @Test
  public void reportsTheTotalsOnce() {
    AiUsageSummary summary = new AiUsageSummary();
    summary.add("gemini-3.8-flash", 21_778, 1_375, 18_000_000L);
    summary.add("gemini-3.8-flash", 900, 40, 1_234_567L);

    assertEquals(
        "gemini-3.8-flash | 2 | 23k | 1.4k | $0.019", summary.takeReportLine(FORMAT).orElseThrow());
    assertTrue(summary.takeReportLine(FORMAT).isEmpty());
  }

  @Test
  public void marksAnIncompleteCostEstimate() {
    AiUsageSummary summary = new AiUsageSummary();
    summary.add("gemini-3.8-flash", 10, 2, 5_000_000L);
    summary.add("unpriced-model", 10, 2, null);

    assertEquals(
        "gemini-3.8-flash, unpriced-model | 2 | 20 | 4 | $0.005+",
        summary.takeReportLine(FORMAT).orElseThrow());
  }

  @Test
  public void nothingToReportWithoutRequests() {
    assertTrue(new AiUsageSummary().takeReportLine(FORMAT).isEmpty());
  }

  @Test
  public void changeSetDataCopiesShareTheSummary() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.copy().getAiUsageSummary().add("m", 1, 1, 1L);

    assertEquals(1, changeSetData.getAiUsageSummary().getRequests());
  }
}
