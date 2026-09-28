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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level1;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pins the replies of the commit-message agent to the commit message. The agent only reviews
 * commit messages, but models name the file inconsistently (`COMMIT_MSG`, a code file, nothing),
 * and such replies ended up at patch-set level.
 */
final class CommitMessageReplies {
  static final String COMMIT_MESSAGE_PATH = "/COMMIT_MSG";
  private static final String COMMIT_MESSAGE_NAME = "COMMIT_MSG";
  private static final Gson GSON = new Gson();

  private CommitMessageReplies() {}

  /**
   * @param change the change the request was made for
   * @param changesByPrefix review group members by their origin prefix; in a merged group review
   *     it contains {@code change}, whose prefix is used for replies without one
   */
  static void pin(
      AiResponseContent response, GerritChange change, Map<String, GerritChange> changesByPrefix) {
    if (response == null || response.getReplies() == null) {
      return;
    }
    String ownPrefix = null;
    if (changesByPrefix != null) {
      for (Map.Entry<String, GerritChange> member : changesByPrefix.entrySet()) {
        if (member.getValue() == change) {
          ownPrefix = member.getKey();
        }
      }
    }
    // Copies: a reply object may also be referenced from another stage's response.
    List<AiReplyItem> pinned = new ArrayList<>();
    for (AiReplyItem reply : response.getReplies()) {
      AiReplyItem copy = GSON.fromJson(GSON.toJson(reply), AiReplyItem.class);
      copy.setFilename(commitMessagePath(reply.getFilename(), ownPrefix, changesByPrefix));
      pinned.add(copy);
    }
    response.setReplies(pinned);
  }

  private static String commitMessagePath(
      String filename, String ownPrefix, Map<String, GerritChange> changesByPrefix) {
    if (ownPrefix == null) {
      return COMMIT_MESSAGE_PATH;
    }
    if (filename != null) {
      for (String prefix : changesByPrefix.keySet()) {
        if (filename.startsWith(prefix)) {
          return prefix + COMMIT_MESSAGE_NAME;
        }
      }
    }
    return ownPrefix + COMMIT_MESSAGE_NAME;
  }
}
