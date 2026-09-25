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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.GITLINK_LINE_PREFIX;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TopicPatchSetReviewMerger {
  private static final String TOPIC_PATCH_SET_PREFIX = "reviewai-topic-change-";
  private static final Pattern GITLINK_LINE_PATTERN =
      Pattern.compile("^" + GITLINK_LINE_PREFIX + "(.+): (\\S+) -> (\\S+)$");

  public TopicReviewPatchSet patchSet(GerritChange change, int index, String patchSet) {
    return new TopicReviewPatchSet(change, TOPIC_PATCH_SET_PREFIX + index + "/", patchSet);
  }

  /**
   * Returns a review group patch set whose prefix also names the member's project, for example
   * {@code reviewai-topic-change-2/core-libs/}.
   */
  public TopicReviewPatchSet reviewGroupPatchSet(GerritChange change, int index, String patchSet) {
    return new TopicReviewPatchSet(
        change, TOPIC_PATCH_SET_PREFIX + index + "/" + change.getProjectName() + "/", patchSet);
  }

  public String buildMergedPatchSet(List<TopicReviewPatchSet> patchSets) {
    List<String> parts = new ArrayList<>();
    parts.add(
        "Review these Gerrit patch sets as one topic push. Each diff filename is prefixed with "
            + "a ReviewAI origin path. Use that exact prefixed filename in every inline reply so "
            + "the review can be published back to the original patch set.");
    for (TopicReviewPatchSet patchSet : patchSets) {
      GerritChange change = patchSet.change();
      parts.add(
          String.join(
              "\n",
              "ReviewAI origin: " + patchSet.prefix(),
              "Gerrit change: " + change.getFullChangeId(),
              "Patch set: " + change.getPatchSetAttribute().map(ps -> ps.number).orElse(null),
              prefixPatchFilenames(patchSet.patchSet(), patchSet.prefix())));
    }
    return String.join("\n\n", parts);
  }

  /**
   * Builds the patch of a multi-project review group: the member list followed by the prefixed
   * patch of each member. Gitlink updates that point at the current revision of another member are
   * annotated with that member.
   */
  public String buildMergedReviewGroupPatchSet(
      List<ReviewGroupMember> members, List<TopicReviewPatchSet> patchSets) {
    Map<String, GerritChange> changesByRevision = changesByRevision(members);
    List<String> parts = new ArrayList<>();
    parts.add(
        "Review these Gerrit patch sets, which Gerrit submits together and which may belong to "
            + "different projects, as one review group. Each diff filename is prefixed with a "
            + "ReviewAI origin path of the form `reviewai-topic-change-<N>/<project>/`. Use that "
            + "exact prefixed filename in every inline reply, and `<origin>COMMIT_MSG` for a commit "
            + "message, so the review can be published back to the original change. Report no "
            + "issues on changes marked as context only.");
    parts.add(buildReviewGroupHeader(members, patchSets));
    for (TopicReviewPatchSet patchSet : patchSets) {
      GerritChange change = patchSet.change();
      parts.add(
          String.join(
              "\n",
              "ReviewAI origin: " + patchSet.prefix(),
              "Gerrit change: " + change.getFullChangeId(),
              "Project: " + change.getProjectName(),
              "Patch set: " + change.getPatchSetAttribute().map(ps -> ps.number).orElse(null),
              annotateGitlinks(
                  prefixPatchFilenames(patchSet.patchSet(), patchSet.prefix()),
                  changesByRevision)));
    }
    return String.join("\n\n", parts);
  }

  /** Lists the review group members with their project, change number, branch and subject. */
  public String buildReviewGroupHeader(
      List<ReviewGroupMember> members, List<TopicReviewPatchSet> patchSets) {
    Map<GerritChange, String> prefixes = new HashMap<>();
    patchSets.forEach(patchSet -> prefixes.put(patchSet.change(), patchSet.prefix()));
    List<String> lines = new ArrayList<>();
    lines.add("ReviewAI review group members:");
    for (ReviewGroupMember member : members) {
      GerritChange change = member.change();
      StringBuilder line = new StringBuilder("- ");
      Optional.ofNullable(prefixes.get(change))
          .ifPresent(prefix -> line.append(prefix).append(": "));
      line.append("change ")
          .append(change.getChangeNumber().map(String::valueOf).orElse("?"))
          .append(" in project ")
          .append(change.getProjectName())
          .append(", branch ")
          .append(change.getBranchNameKey() == null ? "?" : change.getBranchNameKey().shortName());
      change.getSubject().ifPresent(subject -> line.append(": \"").append(subject).append('"'));
      if (!member.reviewable()) {
        line.append(" (context only: no comments are published to this change)");
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  private static Map<String, GerritChange> changesByRevision(List<ReviewGroupMember> members) {
    Map<String, GerritChange> changesByRevision = new HashMap<>();
    for (ReviewGroupMember member : members) {
      String revision = member.change().getPatchSetRevision();
      if (revision != null && !revision.isBlank()) {
        changesByRevision.put(revision, member.change());
      }
    }
    return changesByRevision;
  }

  private static String annotateGitlinks(
      String patchSet, Map<String, GerritChange> changesByRevision) {
    if (changesByRevision.isEmpty()) {
      return patchSet;
    }
    return String.join(
        "\n",
        Arrays.stream(patchSet.split("\n", -1))
            .map(line -> annotateGitlink(line, changesByRevision))
            .toList());
  }

  private static String annotateGitlink(String line, Map<String, GerritChange> changesByRevision) {
    Matcher matcher = GITLINK_LINE_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return line;
    }
    GerritChange target = changesByRevision.get(matcher.group(3));
    if (target == null) {
      return line;
    }
    return line
        + " (= change "
        + target.getChangeNumber().map(String::valueOf).orElse("?")
        + " in "
        + target.getProjectName()
        + ")";
  }

  private String prefixPatchFilenames(String patchSet, String prefix) {
    return String.join(
        "\n",
        Arrays.stream(patchSet.split("\n", -1))
            .map(line -> prefixPatchLine(line, prefix))
            .toList());
  }

  private String prefixPatchLine(String line, String prefix) {
    if (line.startsWith("diff --git a/")) {
      String[] parts = line.split(" ", 4);
      if (parts.length == 4 && parts[2].startsWith("a/") && parts[3].startsWith("b/")) {
        return String.join(
            " ",
            parts[0],
            parts[1],
            "a/" + prefix + parts[2].substring(2),
            "b/" + prefix + parts[3].substring(2));
      }
    }
    if (line.startsWith("--- a/")) {
      return "--- a/" + prefix + line.substring("--- a/".length());
    }
    if (line.startsWith("+++ b/")) {
      return "+++ b/" + prefix + line.substring("+++ b/".length());
    }
    if (line.startsWith("rename from ")) {
      return "rename from " + prefix + line.substring("rename from ".length());
    }
    if (line.startsWith("rename to ")) {
      return "rename to " + prefix + line.substring("rename to ".length());
    }
    if (line.startsWith("copy from ")) {
      return "copy from " + prefix + line.substring("copy from ".length());
    }
    if (line.startsWith("copy to ")) {
      return "copy to " + prefix + line.substring("copy to ".length());
    }
    return line;
  }
}
