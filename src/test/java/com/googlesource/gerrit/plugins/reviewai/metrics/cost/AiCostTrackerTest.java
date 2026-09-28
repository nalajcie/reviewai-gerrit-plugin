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
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

public class AiCostTrackerTest {
  @Test
  public void recordsPricingMissingForUnknownExactModel() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4-unknown-snapshot"));
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(1, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  @Test
  public void excludesOllamaFromCostAndMissingPricingMetrics() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OLLAMA, "in-house-model"));
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(0, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  @Test
  public void excludesMockModelsFromCostAndMissingPricingMetrics() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "mock-ai"));
    when(config.isSelectedMockAiModelRoute()).thenReturn(true);
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(0, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  @Test
  public void attributesEstimatedCostToProject() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4"));
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response(), "core-libs");

    assertEquals("core-libs", metrics.project);
    assertEquals(55_000, metrics.nanoUsd);
  }

  @Test
  public void addsEachResponseToTheReviewSummaryWithThinkingTokens() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.GEMINI, "gemini-3.8-flash"));
    when(config.getAiPricing())
        .thenReturn(List.of("Gemini/gemini-3.8-flash,input=0.75,cachedInput=0.075,output=3.75"));
    AiUsageSummary summary = new AiUsageSummary();
    ChatResponse response =
        ChatResponse.builder()
            .aiMessage(AiMessage.from("ok"))
            .tokenUsage(new TokenUsage(1000, 100, 1600))
            .build();

    new AiCostTracker(config, new RecordingMetrics()).record(response, "pilot", summary);

    assertEquals(1, summary.getRequests());
    assertEquals(1000, summary.getInputTokens());
    assertEquals(600, summary.getOutputTokens());
    assertEquals(3_000_000L, summary.getNanoUsd());
  }

  @Test
  public void countsUnpricedResponsesInTheSummary() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4-unknown-snapshot"));
    AiUsageSummary summary = new AiUsageSummary();

    new AiCostTracker(config, new RecordingMetrics()).record(response(), null, summary);

    assertEquals(1, summary.getRequests());
    assertTrue(summary.takeReportLine("%s %d %s %s %s").orElseThrow().endsWith("0.000+"));
  }

  private static ChatResponse response() {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from("ok"))
        .tokenUsage(new TokenUsage(10, 2))
        .build();
  }

  private static class RecordingMetrics extends ReviewAiMetrics {
    private long nanoUsd;
    private int pricingMissing;
    private String project;

    @Override
    public void recordAiEstimatedCostNanoUsd(
        String provider, String model, String project, long nanoUsd) {
      this.nanoUsd += nanoUsd;
      this.project = project;
    }

    @Override
    public void recordAiPricingMissing(String provider, String model) {
      pricingMissing++;
    }
  }
}
