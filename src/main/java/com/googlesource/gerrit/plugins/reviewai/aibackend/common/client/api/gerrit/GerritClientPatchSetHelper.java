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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.COMMIT_MESSAGE_FILTER_OUT_PREFIXES;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_COMMIT_MESSAGE_PREFIX;
import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.isFileExtensionEnabled;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GerritClientPatchSetHelper {
  private static final Pattern DIFF_START_PATTERN = Pattern.compile("(?m)^diff --git ");
  private static final Pattern EXTRACT_B_FILENAMES_FROM_PATCH_SET =
      Pattern.compile("^diff --git .*? b/(.*)$", Pattern.MULTILINE);
  private static final Pattern GITLINK_MODE_PATTERN =
      Pattern.compile(
          "(?m)^(?:index \\S+ 160000|(?:new|deleted) file mode 160000|(?:old|new) mode 160000)$");
  private static final Pattern OLD_GITLINK_COMMIT_PATTERN =
      Pattern.compile("(?m)^-Subproject commit (\\S+)$");
  private static final Pattern NEW_GITLINK_COMMIT_PATTERN =
      Pattern.compile("(?m)^\\+Subproject commit (\\S+)$");
  private static final String NO_GITLINK_COMMIT = "(none)";
  public static final String GITLINK_LINE_PREFIX = "submodule ";
  private static final String GERRIT_COMMIT_MESSAGE_PATTERN =
      "^.*?" + GERRIT_COMMIT_MESSAGE_PREFIX + "(?:\\[[^\\]]+\\] )?";

  public static String filterPatchWithCommitMessage(String formattedPatch) {
    // Remove Patch heading up to the Date annotation, so that the commit message is included.
    // Additionally, remove
    // the change type between brackets
    Pattern CONFIG_ID_HEADING_PATTERN =
        Pattern.compile(GERRIT_COMMIT_MESSAGE_PATTERN, Pattern.DOTALL);
    String result =
        CONFIG_ID_HEADING_PATTERN.matcher(formattedPatch).replaceAll(GERRIT_COMMIT_MESSAGE_PREFIX);
    log.debug("Patch filtered with commit message: {}", result);
    return result;
  }

  public static String filterPatchWithoutCommitMessage(GerritChange change, String formattedPatch) {
    // Remove Patch heading up to the Change-Id annotation
    Pattern CONFIG_ID_HEADING_PATTERN =
        Pattern.compile(
            "^.*?"
                + COMMIT_MESSAGE_FILTER_OUT_PREFIXES.get("CHANGE_ID")
                + " "
                + change.getChangeKey().get(),
            Pattern.DOTALL);
    String result = CONFIG_ID_HEADING_PATTERN.matcher(formattedPatch).replaceAll("");
    log.debug("Patch filtered without commit message: {}", result);
    return result;
  }

  public static List<String> extractFilesFromPatch(String formattedPatch) {
    Matcher extractFilenameMatcher = EXTRACT_B_FILENAMES_FROM_PATCH_SET.matcher(formattedPatch);
    List<String> files = new ArrayList<>();
    while (extractFilenameMatcher.find()) {
      files.add(extractFilenameMatcher.group(1));
      log.debug("File extracted from patch: {}", extractFilenameMatcher.group(1));
    }
    log.debug("Total files extracted from patch: {}", files.size());
    return files;
  }

  public static String filterPatchByEnabledFileExtensions(
      String formattedPatch,
      List<String> enabledFileExtensions,
      List<String> disabledFileExtensions) {
    return filterPatchByEnabledFileExtensions(
        formattedPatch, enabledFileExtensions, disabledFileExtensions, false);
  }

  /**
   * Filters the patch sections by file extension. When {@code keepGitlinks} is set, sections that
   * update a gitlink (submodule pointer) are kept regardless of the extension filters.
   */
  public static String filterPatchByEnabledFileExtensions(
      String formattedPatch,
      List<String> enabledFileExtensions,
      List<String> disabledFileExtensions,
      boolean keepGitlinks) {
    List<Integer> diffSectionStarts = diffSectionStarts(formattedPatch);
    if (diffSectionStarts.isEmpty()) {
      return formattedPatch;
    }

    StringBuilder filteredPatch = new StringBuilder();
    filteredPatch.append(formattedPatch, 0, diffSectionStarts.getFirst());
    for (String diffSection : diffSections(formattedPatch, diffSectionStarts)) {
      String filename = extractFilenameFromPatchSection(diffSection);
      if (filename != null
          && ((keepGitlinks && isGitlinkSection(diffSection))
              || isFileExtensionEnabled(filename, enabledFileExtensions, disabledFileExtensions))) {
        filteredPatch.append(diffSection);
      }
    }

    String result = filteredPatch.toString();
    log.debug("Patch filtered by enabled file extensions: {}", result);
    return result;
  }

  /**
   * Replaces each gitlink (submodule pointer) diff section with a single readable line of the form
   * {@code submodule <path>: <old-sha> -> <new-sha>}, keeping the {@code diff --git} header so that
   * the section is still attributed to its path.
   */
  public static String renderGitlinkDiffs(String formattedPatch) {
    List<Integer> diffSectionStarts = diffSectionStarts(formattedPatch);
    if (diffSectionStarts.isEmpty()) {
      return formattedPatch;
    }
    StringBuilder renderedPatch = new StringBuilder();
    renderedPatch.append(formattedPatch, 0, diffSectionStarts.getFirst());
    for (String diffSection : diffSections(formattedPatch, diffSectionStarts)) {
      String filename = extractFilenameFromPatchSection(diffSection);
      if (filename == null || !GITLINK_MODE_PATTERN.matcher(diffSection).find()) {
        renderedPatch.append(diffSection);
        continue;
      }
      String header = diffSection.lines().findFirst().orElse("");
      renderedPatch
          .append(header)
          .append('\n')
          .append(GITLINK_LINE_PREFIX)
          .append(filename)
          .append(": ")
          .append(firstGroup(OLD_GITLINK_COMMIT_PATTERN, diffSection))
          .append(" -> ")
          .append(firstGroup(NEW_GITLINK_COMMIT_PATTERN, diffSection))
          .append('\n');
    }
    return renderedPatch.toString();
  }

  private static boolean isGitlinkSection(String diffSection) {
    return GITLINK_MODE_PATTERN.matcher(diffSection).find()
        || diffSection.lines().skip(1).findFirst().orElse("").startsWith(GITLINK_LINE_PREFIX);
  }

  private static String firstGroup(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : NO_GITLINK_COMMIT;
  }

  private static List<Integer> diffSectionStarts(String formattedPatch) {
    Matcher diffStartMatcher = DIFF_START_PATTERN.matcher(formattedPatch);
    List<Integer> diffSectionStarts = new ArrayList<>();
    while (diffStartMatcher.find()) {
      diffSectionStarts.add(diffStartMatcher.start());
    }
    return diffSectionStarts;
  }

  private static List<String> diffSections(String formattedPatch, List<Integer> starts) {
    List<String> diffSections = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) : formattedPatch.length();
      diffSections.add(formattedPatch.substring(starts.get(i), end));
    }
    return diffSections;
  }

  private static String extractFilenameFromPatchSection(String diffSection) {
    Matcher extractFilenameMatcher = EXTRACT_B_FILENAMES_FROM_PATCH_SET.matcher(diffSection);
    if (!extractFilenameMatcher.find()) {
      return null;
    }
    return extractFilenameMatcher.group(1);
  }
}
