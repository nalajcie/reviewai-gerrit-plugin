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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import org.junit.Test;

public class TopicReviewReplyMapperTest {
  private static final String PREFIX = "reviewai-topic-change-1/pilot/";
  private final TopicReviewReplyMapper mapper = new TopicReviewReplyMapper();

  @Test
  public void memberCommitMessageMapsToGerritCommitMessagePath() {
    assertEquals("/COMMIT_MSG", map(PREFIX + "COMMIT_MSG"));
  }

  @Test
  public void memberFileLosesThePrefix() {
    assertEquals("src/main.c", map(PREFIX + "src/main.c"));
  }

  @Test
  public void otherMemberFileIsNotForThisChange() {
    AiReplyItem reply = reply("reviewai-topic-change-2/core-libs/src/span.h");
    assertTrue(mapper.replyForChange(reply, PREFIX).isEmpty());
  }

  private String map(String filename) {
    return mapper.replyForChange(reply(filename), PREFIX).orElseThrow().getFilename();
  }

  private static AiReplyItem reply(String filename) {
    AiReplyItem reply = AiReplyItem.builder().reply("r").build();
    reply.setFilename(filename);
    return reply;
  }
}
