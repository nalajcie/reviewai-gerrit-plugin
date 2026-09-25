/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SpecializedReviewTopicVerificationTest {
  @Test
  public void splitsReviewGroupPatchByProjectPrefixedOrigins() {
    String patchSet =
        String.join(
            "\n",
            "Review group intro",
            "",
            "ReviewAI origin: reviewai-topic-change-0/superproject/",
            "diff --git a/reviewai-topic-change-0/superproject/core-libs b/x",
            "",
            "ReviewAI origin: reviewai-topic-change-1/platform/core-libs/",
            "diff --git a/reviewai-topic-change-1/platform/core-libs/a.c b/y");

    List<SpecializedReviewTopicVerification.TopicVerificationPatch> patches =
        SpecializedReviewTopicVerification.topicVerificationPatches(patchSet);

    assertEquals(
        List.of(
            "reviewai-topic-change-0/superproject/", "reviewai-topic-change-1/platform/core-libs/"),
        patches.stream()
            .map(SpecializedReviewTopicVerification.TopicVerificationPatch::prefix)
            .toList());
    assertTrue(patches.get(1).patchSet().startsWith("Review group intro"));
    assertEquals(
        "reviewai-topic-change-1_platform_core-libs",
        SpecializedReviewTopicVerification.verificationConversationSuffix(patches.get(1)));
  }
}
