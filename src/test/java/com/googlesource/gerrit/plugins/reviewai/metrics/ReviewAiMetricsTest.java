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

package com.googlesource.gerrit.plugins.reviewai.metrics;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.metrics.Counter3;
import com.google.gerrit.metrics.MetricMaker;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import org.junit.Test;

public class ReviewAiMetricsTest {
  @Test
  public void specializedAgentStageIncludesAgentName() {
    assertEquals(
        "REVIEW_SPECIALIZED_AGENT_CODE_QUALITY",
        ReviewAiMetrics.aiRequestStageLabel(
            ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT, "CODE_QUALITY"));
  }

  @Test
  public void specializedAgentStageWithoutAgentNameUsesGenericStage() {
    assertEquals(
        "REVIEW_SPECIALIZED_AGENT",
        ReviewAiMetrics.aiRequestStageLabel(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT, " "));
  }

  @Test
  public void nonSpecializedStageIgnoresAgentName() {
    assertEquals(
        "REVIEW_SPECIALIZED_TRIAGE",
        ReviewAiMetrics.aiRequestStageLabel(
            ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE, "CODE_QUALITY"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void recordsProjectDimension() {
    MetricMaker metricMaker = mock(MetricMaker.class, RETURNS_MOCKS);
    Counter3<String, String, String> reviewRunCount = mock(Counter3.class);
    Counter3<String, String, String> aiRequestProjectCount = mock(Counter3.class);
    Counter3<String, String, String> estimatedCost = mock(Counter3.class);
    when(metricMaker.newCounter(eq("reviewai/review_run/count"), any(), any(), any(), any()))
        .thenReturn((Counter3) reviewRunCount);
    when(metricMaker.newCounter(
            eq("reviewai/ai_request/project_count"), any(), any(), any(), any()))
        .thenReturn((Counter3) aiRequestProjectCount);
    when(metricMaker.newCounter(
            eq("reviewai/ai_request/estimated_cost_nanousd"), any(), any(), any(), any()))
        .thenReturn((Counter3) estimatedCost);
    ReviewAiMetrics metrics = new ReviewAiMetrics(metricMaker);

    metrics.startReviewRun("patchset-created", "core-libs").complete();
    metrics
        .startAiRequest(
            AiProviderType.OPENAI, "gpt-5.4", ReviewAssistantStage.REVIEW_CODE, null, "core-libs")
        .fail();
    metrics.recordAiEstimatedCostNanoUsd("OpenAI", "gpt-5.4", null, 42);

    verify(reviewRunCount).increment("patchset-created", "completed", "core-libs");
    verify(aiRequestProjectCount).increment("core-libs", "OPENAI", "error");
    verify(estimatedCost).incrementBy("OpenAI", "gpt-5.4", "unknown", 42);
  }
}
