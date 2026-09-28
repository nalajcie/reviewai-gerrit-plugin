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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the replies of the commit-message agent to the commit message. The agent only reviews
 * commit messages, but models name the file inconsistently (`COMMIT_MSG`, a code file, nothing),
 * and such replies ended up at patch-set level.
 */
final class CommitMessageReplies {
  static final String COMMIT_MESSAGE_PATH = "/COMMIT_MSG";
  private static final String COMMIT_MESSAGE_NAME = "COMMIT_MSG";
  private static final Gson GSON = new Gson();
  private static final String ORIGIN_MARKER = "ReviewAI origin: ";
  // 'quoted', `quoted` or "quoted" text of at least 8 characters
  private static final Pattern QUOTED =
      Pattern.compile("'([^'\\n]{8,})'|`([^`\\n]{8,})`|\"([^\"\\n]{8,})\"");

  private CommitMessageReplies() {}

  /**
   * @param change the change the request was made for
   * @param changesByPrefix review group members by their origin prefix; in a merged group review
   *     it contains {@code change}, whose prefix is used for replies without one
   */
  static void pin(
      AiResponseContent response, GerritChange change, Map<String, GerritChange> changesByPrefix) {
    pin(response, change, changesByPrefix, null);
  }

  /**
   * @param patchSet the merged patch of a review group; used to find the member whose commit
   *     message a reply without a member prefix is about
   */
  static void pin(
      AiResponseContent response,
      GerritChange change,
      Map<String, GerritChange> changesByPrefix,
      String patchSet) {
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
    Map<String, String> commitMessagesByPrefix =
        ownPrefix == null ? Map.of() : commitMessagesByPrefix(patchSet, changesByPrefix);
    List<AiReplyItem> pinned = new ArrayList<>();
    for (AiReplyItem reply : response.getReplies()) {
      AiReplyItem copy = GSON.fromJson(GSON.toJson(reply), AiReplyItem.class);
      copy.setFilename(
          commitMessagePath(reply, ownPrefix, changesByPrefix, commitMessagesByPrefix));
      pinned.add(copy);
    }
    response.setReplies(pinned);
  }

  private static String commitMessagePath(
      AiReplyItem reply,
      String ownPrefix,
      Map<String, GerritChange> changesByPrefix,
      Map<String, String> commitMessagesByPrefix) {
    if (ownPrefix == null) {
      return COMMIT_MESSAGE_PATH;
    }
    String filename = reply.getFilename();
    if (filename != null) {
      for (String prefix : changesByPrefix.keySet()) {
        if (filename.startsWith(prefix)) {
          return prefix + COMMIT_MESSAGE_NAME;
        }
      }
    }
    return quotedMember(reply, commitMessagesByPrefix).orElse(ownPrefix) + COMMIT_MESSAGE_NAME;
  }

  /**
   * The member whose commit message contains the reply's code snippet or, failing that, most of
   * the text the reply quotes. Models often write plain {@code /COMMIT_MSG} in a group review
   * while quoting the subject they mean.
   */
  private static Optional<String> quotedMember(
      AiReplyItem reply, Map<String, String> commitMessagesByPrefix) {
    String snippet = reply.getCodeSnippet() == null ? "" : reply.getCodeSnippet().strip();
    if (!snippet.isEmpty()) {
      List<String> matches =
          commitMessagesByPrefix.entrySet().stream()
              .filter(entry -> entry.getValue().contains(snippet))
              .map(Map.Entry::getKey)
              .toList();
      if (matches.size() == 1) {
        return Optional.of(matches.getFirst());
      }
    }
    List<String> quotes = new ArrayList<>();
    Matcher matcher = QUOTED.matcher(reply.getReply() == null ? "" : reply.getReply());
    while (matcher.find()) {
      for (int group = 1; group <= matcher.groupCount(); group++) {
        if (matcher.group(group) != null) {
          quotes.add(matcher.group(group).strip());
        }
      }
    }
    String best = null;
    long bestHits = 0;
    boolean tie = false;
    for (Map.Entry<String, String> entry : commitMessagesByPrefix.entrySet()) {
      long hits = quotes.stream().filter(quote -> entry.getValue().contains(quote)).count();
      if (hits > bestHits) {
        best = entry.getKey();
        bestHits = hits;
        tie = false;
      } else if (hits == bestHits && hits > 0) {
        tie = true;
      }
    }
    return tie ? Optional.empty() : Optional.ofNullable(best);
  }

  /** The commit message part (before the first diff) of each member section of a merged patch. */
  private static Map<String, String> commitMessagesByPrefix(
      String patchSet, Map<String, GerritChange> changesByPrefix) {
    Map<String, String> messages = new LinkedHashMap<>();
    if (patchSet == null) {
      return messages;
    }
    for (String section : patchSet.split("\n(?=" + ORIGIN_MARKER + ")")) {
      if (!section.startsWith(ORIGIN_MARKER)) {
        continue;
      }
      int lineEnd = section.indexOf('\n');
      String prefix =
          section.substring(ORIGIN_MARKER.length(), lineEnd < 0 ? section.length() : lineEnd).strip();
      if (!changesByPrefix.containsKey(prefix)) {
        continue;
      }
      int diff = section.indexOf("\ndiff --git ");
      messages.put(prefix, diff < 0 ? section : section.substring(0, diff));
    }
    return messages;
  }
}
