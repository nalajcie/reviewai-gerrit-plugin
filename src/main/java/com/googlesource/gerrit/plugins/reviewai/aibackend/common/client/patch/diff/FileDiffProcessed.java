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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPatchSetFileDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.code.patch.CodeFinderDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.patch.diff.DiffContent;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;

@Slf4j
public class FileDiffProcessed {
  private static final int MIN_RANDOM_PLACEHOLDER_VARIABLE_LENGTH = 1;

  private final Configuration config;
  private final boolean isCommitMessage;
  @Getter private List<CodeFinderDiff> codeFinderDiffs;
  @Getter private List<String> newContent;
  @Getter private List<DiffContent> reviewDiffContent;
  @Getter private String randomPlaceholder;
  @Getter private Optional<GerritCodeRange> commitMessageRange = Optional.empty();
  // Lines of the commit message file as Gerrit numbers them (line n at index n - 1)
  private List<String> commitMessageLines = List.of();
  private int lineNum;
  private DiffContent diffContentItem;
  private DiffContent reviewDiffContentItem;
  private TreeMap<Integer, Integer> charToLineMapItem;

  public FileDiffProcessed(
      Configuration config,
      boolean isCommitMessage,
      GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    this.config = config;
    this.isCommitMessage = isCommitMessage;

    if (isCommitMessage) {
      commitMessageRange = findCommitMessageRange(gerritPatchSetFileDiff);
    }
    updateContent(gerritPatchSetFileDiff);
    updateRandomPlaceholder(gerritPatchSetFileDiff);
    log.debug(
        "FileDiffProcessed initialized for {}", isCommitMessage ? "commit message" : "file diff");
  }

  private Optional<GerritCodeRange> findCommitMessageRange(
      GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    List<String> content = new ArrayList<>();
    if (gerritPatchSetFileDiff.getContent() == null) {
      return Optional.empty();
    }
    for (GerritPatchSetFileDiff.Content contentItem : gerritPatchSetFileDiff.getContent()) {
      if (contentItem.ab != null) {
        content.addAll(contentItem.ab);
      }
      if (contentItem.b != null) {
        content.addAll(contentItem.b);
      }
    }
    if (content.isEmpty()) {
      return Optional.empty();
    }
    commitMessageLines = content;

    int lastHeaderLine = -1;
    for (int i = 0; i < content.size(); i++) {
      String line = content.get(i);
      if (isImmutableCommitHeader(line)) {
        lastHeaderLine = i;
      } else if (lastHeaderLine >= 0) {
        break;
      }
    }
    int firstMessageLine = lastHeaderLine + 1;
    while (firstMessageLine < content.size() && content.get(firstMessageLine).isEmpty()) {
      firstMessageLine++;
    }
    if (firstMessageLine >= content.size()) {
      return Optional.empty();
    }

    int lastMessageLine = content.size() - 1;
    return Optional.of(
        GerritCodeRange.builder()
            .startLine(firstMessageLine + 1)
            .startCharacter(0)
            .endLine(lastMessageLine + 1)
            .endCharacter(content.get(lastMessageLine).length())
            .build());
  }

  /**
   * Returns the commit-message lines that contain the given snippet, or the whole message when the
   * snippet is missing or not found. Models quote the subject or a trailer they comment on; a
   * range over the whole message would hide which line the comment is about.
   */
  public Optional<GerritCodeRange> findCommitMessageRange(String codeSnippet) {
    if (commitMessageRange.isEmpty() || codeSnippet == null) {
      return commitMessageRange;
    }
    List<String> snippetLines =
        codeSnippet.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    if (snippetLines.isEmpty()) {
      return commitMessageRange;
    }
    int first = commitMessageRange.get().startLine - 1;
    for (int start = first; start < commitMessageLines.size(); start++) {
      int end = matchSnippetAt(start, snippetLines);
      if (end >= 0) {
        return Optional.of(
            GerritCodeRange.builder()
                .startLine(start + 1)
                .startCharacter(0)
                .endLine(end + 1)
                .endCharacter(commitMessageLines.get(end).length())
                .build());
      }
    }
    return commitMessageRange;
  }

  /** Returns the index of the last line matched when the snippet starts at start, or -1. */
  private int matchSnippetAt(int start, List<String> snippetLines) {
    int line = start;
    for (int i = 0; i < snippetLines.size(); i++) {
      if (i > 0) {
        line++;
        while (line < commitMessageLines.size() && commitMessageLines.get(line).isBlank()) {
          line++;
        }
      }
      if (line >= commitMessageLines.size()
          || !commitMessageLines.get(line).contains(snippetLines.get(i))) {
        return -1;
      }
    }
    return line;
  }

  private boolean isImmutableCommitHeader(String line) {
    return Settings.COMMIT_MESSAGE_FILTER_OUT_PREFIXES.entrySet().stream()
        .filter(entry -> !"CHANGE_ID".equals(entry.getKey()))
        .map(java.util.Map.Entry::getValue)
        .anyMatch(line::startsWith);
  }

  private void updateContent(GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    newContent = new ArrayList<>();
    newContent.add("DUMMY LINE #0");
    lineNum = 1;
    reviewDiffContent = new ArrayList<>();
    codeFinderDiffs = new ArrayList<>();
    List<GerritPatchSetFileDiff.Content> patchSetDiffContent = gerritPatchSetFileDiff.getContent();
    log.debug("Updating content from patch set diff content.");
    // Iterate over the items of the diff content
    for (GerritPatchSetFileDiff.Content patchSetContentItem : patchSetDiffContent) {
      diffContentItem = new DiffContent();
      reviewDiffContentItem = new DiffContent();
      charToLineMapItem = new TreeMap<>();
      // Iterate over the fields `a`, `b` and `ab` of each diff content
      for (Field patchSetDiffField : GerritPatchSetFileDiff.Content.class.getDeclaredFields()) {
        processFileDiffItem(patchSetDiffField, patchSetContentItem);
      }
      reviewDiffContent.add(reviewDiffContentItem);
      codeFinderDiffs.add(new CodeFinderDiff(diffContentItem, charToLineMapItem));
    }
  }

  private void updateRandomPlaceholder(GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    int placeholderVariableLength = MIN_RANDOM_PLACEHOLDER_VARIABLE_LENGTH;
    do {
      randomPlaceholder = '#' + RandomStringUtils.random(placeholderVariableLength, true, false);
      placeholderVariableLength++;
    } while (gerritPatchSetFileDiff.toString().contains(randomPlaceholder));
    log.debug("Generated random placeholder: {}", randomPlaceholder);
  }

  private void filterCommitMessageContent(List<String> fieldValue) {
    fieldValue.removeIf(
        s ->
            s.isEmpty()
                || Settings.COMMIT_MESSAGE_FILTER_OUT_PREFIXES.values().stream()
                    .anyMatch(s::startsWith));
    log.debug("Filtered commit message content.");
  }

  private void updateCodeEntities(Field diffField, List<String> diffLines)
      throws IllegalAccessException {
    String diffType = diffField.getName();
    String content = joinWithNewLine(diffLines);
    diffField.set(diffContentItem, content);
    log.debug("Updated code entities for field: {}", diffType);
    // If the lines modified in the PatchSet are not deleted, they are utilized to populate
    // newContent and
    // charToLineMapItem
    if (diffType.contains("b")) {
      int diffCharPointer = -1;
      for (String diffLine : diffLines) {
        // Increase of 1 to take into account of the newline character
        diffCharPointer++;
        charToLineMapItem.put(diffCharPointer, lineNum);
        diffCharPointer += diffLine.length();
        lineNum++;
      }
      // Add the last line to charToLineMapItem
      charToLineMapItem.put(diffCharPointer + 1, lineNum);
      newContent.addAll(diffLines);
    }
    // If the lines modified in the PatchSet are deleted, they are mapped in charToLineMapItem to
    // current lineNum
    else {
      int startingPosition = charToLineMapItem.isEmpty() ? 0 : content.length();
      charToLineMapItem.put(startingPosition, lineNum);
    }

    if (config.getAiFullFileReview() || !diffType.equals("ab")) {
      // Store the new field's value in the diff content for the Patch Set review
      // `reviewDiffContentItem`
      diffField.set(reviewDiffContentItem, content);
    }
  }

  private void processFileDiffItem(
      Field patchSetDiffField, GerritPatchSetFileDiff.Content contentItem) {
    try {
      // Get the `a`, `b` or `ab` field's value from the Patch Set diff content
      @SuppressWarnings("unchecked")
      List<String> diffLines = (List<String>) patchSetDiffField.get(contentItem);
      if (diffLines == null) {
        return;
      }
      if (isCommitMessage) {
        filterCommitMessageContent(diffLines);
      }
      // Get the corresponding `a`, `b` or `ab` field from the DiffContent class
      Field diffField = DiffContent.class.getDeclaredField(patchSetDiffField.getName());
      updateCodeEntities(diffField, diffLines);

    } catch (IllegalAccessException | NoSuchFieldException e) {
      log.error("Error processing file diff item (field: {})", patchSetDiffField.getName(), e);
    }
  }
}
