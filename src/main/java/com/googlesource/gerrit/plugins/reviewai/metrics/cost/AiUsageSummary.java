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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Token usage and estimated cost of the AI requests of one review, shared by its stages (which
 * run in parallel) and reported once in the review message.
 */
public final class AiUsageSummary {
  private final Set<String> models = new TreeSet<>();
  private int requests;
  private long inputTokens;
  private long outputTokens;
  private long nanoUsd;
  private boolean costUnknown;
  private boolean reported;

  public synchronized void add(
      String model, long inputTokens, long outputTokens, Long estimatedNanoUsd) {
    requests++;
    if (model != null && !model.isBlank()) {
      models.add(model);
    }
    this.inputTokens += Math.max(0, inputTokens);
    this.outputTokens += Math.max(0, outputTokens);
    if (estimatedNanoUsd == null) {
      costUnknown = true;
    } else {
      nanoUsd += estimatedNanoUsd;
    }
  }

  public synchronized int getRequests() {
    return requests;
  }

  public synchronized long getInputTokens() {
    return inputTokens;
  }

  public synchronized long getOutputTokens() {
    return outputTokens;
  }

  public synchronized long getNanoUsd() {
    return nanoUsd;
  }

  /**
   * The usage line for the review message, the first time only: a review group publishes to
   * several changes but spends once.
   *
   * @param format a format with the placeholders models, requests, input tokens, output tokens
   *     and estimated USD
   */
  public synchronized Optional<String> takeReportLine(String format) {
    if (reported || requests == 0 || format == null) {
      return Optional.empty();
    }
    reported = true;
    String cost =
        new BigDecimal(nanoUsd)
                .divide(BigDecimal.valueOf(1_000_000_000L), 3, RoundingMode.HALF_UP)
                .toPlainString()
            + (costUnknown ? "+" : "");
    return Optional.of(
        String.format(
            Locale.ROOT,
            format,
            String.join(", ", models),
            requests,
            tokens(inputTokens),
            tokens(outputTokens),
            cost));
  }

  static String tokens(long count) {
    if (count < 1000) {
      return String.valueOf(count);
    }
    return BigDecimal.valueOf(count)
            .divide(BigDecimal.valueOf(1000), count < 10_000 ? 1 : 0, RoundingMode.HALF_UP)
            .toPlainString()
        + "k";
  }
}
