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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level1;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CommitMessageRepliesTest {
  private static final String OWN = "reviewai-topic-change-1/pilot/";
  private static final String OTHER = "reviewai-topic-change-2/core-libs/";

  private final GerritChange change = mock(GerritChange.class);
  private final GerritChange other = mock(GerritChange.class);

  @Test
  public void singleChangeRepliesAlwaysGoToTheCommitMessage() {
    assertEquals(
        List.of("/COMMIT_MSG", "/COMMIT_MSG", "/COMMIT_MSG", "/COMMIT_MSG"),
        pin(Map.of(), "/COMMIT_MSG", "COMMIT_MSG", "src/main.c", null));
  }

  @Test
  public void singleMemberOfAGroupUsesThePlainCommitMessagePath() {
    // The members listed for context don't include the reviewed change.
    assertEquals(List.of("/COMMIT_MSG"), pin(Map.of(OTHER, other), OTHER + "COMMIT_MSG"));
  }

  @Test
  public void mergedGroupKeepsTheMemberAndDefaultsToTheReviewedChange() {
    Map<String, GerritChange> members = new LinkedHashMap<>();
    members.put(OWN, change);
    members.put(OTHER, other);

    assertEquals(
        List.of(OTHER + "COMMIT_MSG", OTHER + "COMMIT_MSG", OWN + "COMMIT_MSG", OWN + "COMMIT_MSG"),
        pin(members, OTHER + "COMMIT_MSG", OTHER + "src/span.h", "/COMMIT_MSG", null));
  }

  private List<String> pin(Map<String, GerritChange> members, String... filenames) {
    AiResponseContent response = new AiResponseContent("");
    List<AiReplyItem> replies = new ArrayList<>();
    for (String filename : filenames) {
      AiReplyItem reply = AiReplyItem.builder().reply("r").build();
      reply.setFilename(filename);
      replies.add(reply);
    }
    response.setReplies(replies);
    CommitMessageReplies.pin(response, change, members);
    return response.getReplies().stream().map(AiReplyItem::getFilename).toList();
  }
}
