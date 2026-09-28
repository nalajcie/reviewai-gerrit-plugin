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

import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiCostTracker {
  private final Configuration config;
  private final ReviewAiMetrics metrics;
  private final AiPricingCatalog pricingCatalog;

  public AiCostTracker(Configuration config, ReviewAiMetrics metrics) {
    this.config = config;
    this.metrics = metrics;
    this.pricingCatalog = new AiPricingCatalog(config == null ? null : config.getAiPricing());
  }

  public void record(ChatResponse response) {
    record(response, null);
  }

  /** Records the estimated cost of one provider response, attributed to a Gerrit project. */
  public void record(ChatResponse response, String project) {
    record(response, project, null);
  }

  /** Also adds the response to the usage summary of the review, when there is one. */
  public void record(ChatResponse response, String project, AiUsageSummary usageSummary) {
    if (config == null || metrics == null || response == null) {
      return;
    }
    Long reviewNanoUsd = null;
    try {
      reviewNanoUsd = recordEstimatedCost(response, project);
    } finally {
      if (usageSummary != null) {
        addToSummary(usageSummary, response, reviewNanoUsd);
      }
    }
  }

  private void addToSummary(AiUsageSummary usageSummary, ChatResponse response, Long nanoUsd) {
    TokenUsage usage = response.tokenUsage();
    AiModelRoute route = config.getSelectedAiModelRoute();
    long input = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
    long output = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
    if (usage != null && usage.totalTokenCount() != null) {
      // thinking tokens, as billed (see AiTokenUsageNormalizer)
      output = Math.max(output, usage.totalTokenCount() - input);
    }
    usageSummary.add(route == null ? null : route.model(), input, output, nanoUsd);
  }

  /** Returns the estimated cost in nanoUSD, or null when it can't be estimated. */
  private Long recordEstimatedCost(ChatResponse response, String project) {
    AiModelRoute route = config.getSelectedAiModelRoute();
    if (route == null
        || route.provider() == AiProviderType.OLLAMA
        || config.isSelectedMockAiModelRoute()) {
      return null;
    }

    Optional<ModelPricing> pricing = pricingCatalog.find(route);
    if (pricing.isEmpty()) {
      metrics.recordAiPricingMissing(route.providerRoute(), route.model());
      return null;
    }

    try {
      OptionalLong nanoUsd =
          AiCostCalculator.calculateNanoUsd(pricing.get(), response.tokenUsage());
      if (nanoUsd.isPresent()) {
        TokenUsage usage = response.tokenUsage();
        log.info(
            "AI usage for {} ({}): input={}, output={}, total={} tokens, estimated cost {} nanoUSD",
            route,
            project,
            usage.inputTokenCount(),
            usage.outputTokenCount(),
            usage.totalTokenCount(),
            nanoUsd.getAsLong());
        metrics.recordAiEstimatedCostNanoUsd(
            route.providerRoute(), route.model(), project, nanoUsd.getAsLong());
        return nanoUsd.getAsLong();
      } else {
        log.debug("AI response has no complete token usage for cost calculation: {}", route);
      }
    } catch (ArithmeticException e) {
      log.warn("AI cost exceeds the supported nanoUSD counter range for route {}", route, e);
    }
    return null;
  }
}
