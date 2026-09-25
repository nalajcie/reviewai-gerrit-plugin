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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OnDemandCodeContextToolsTest extends TestBase {
  private static final Path BASE_PATH = TestResourceLoader.getTestResourcePath();
  private static final String CONTEXT_FILE = "__files/openai/contextPatchOriginal.py";
  private static final String SMALL_TREE_FILE = "__files/ondemand/treeSmall.txt";
  private static final String LARGE_TREE_FILE = "__files/ondemand/treeLarge.txt";

  @Mock private Configuration config;
  @Mock private GitRepoFiles gitRepoFiles;

  private GerritChange change;
  private OnDemandCodeContextTools tools;

  @Before
  public void setUp() {
    change = getGerritChange();
    tools = new OnDemandCodeContextTools(config, change, gitRepoFiles);
  }

  @Test
  public void treeReturnsRepositoryPathsFromSubdir() throws Exception {
    List<String> paths = readTestFileLines(SMALL_TREE_FILE);
    when(gitRepoFiles.getPatchSetFileTree(config, change, "src")).thenReturn(paths);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("tree", "{\"subdir\":\"src\"}");

    assertEquals(String.join("\n", paths), output);
  }

  @Test
  public void treeCompressesLargeRepositoryPaths() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(readTestFileLines(LARGE_TREE_FILE));
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("tree", "{}");

    assertEquals("docs/README.md\nsrc/...", output);
    assertTrue(output.length() <= TreeOutputCompressor.DEFAULT_MAX_LENGTH);
  }

  @Test
  public void getContentReturnsFileContentFromProjectRoot() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "context.py")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("get_content", "{\"file_path\":\"context.py\"}");

    assertEquals(content, output);
  }

  @Test
  public void getContentRejectsVirtualCommitMessagePaths() {
    assertEquals(
        "CONTEXT NOT PROVIDED", tools.execute("get_content", "{\"file_path\":\"COMMIT_MSG\"}"));
    assertEquals(
        "CONTEXT NOT PROVIDED", tools.execute("get_content", "{\"file_path\":\"/COMMIT_MSG\"}"));
    assertEquals(
        "CONTEXT NOT PROVIDED",
        tools.execute("get_content", "{\"file_path\":\"reviewai-topic-change-1/COMMIT_MSG\"}"));
    verifyNoInteractions(gitRepoFiles);
  }

  @Test
  public void getContentAllowsCommitMessageFilenameInRepositorySubdirectory() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "docs/COMMIT_MSG")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("get_content", "{\"file_path\":\"docs/COMMIT_MSG\"}");

    assertEquals(content, output);
  }

  @Test
  public void grepReturnsMatches() throws Exception {
    String firstLine = readTestFile(CONTEXT_FILE).split("\\R", 2)[0];
    String match = "context.py:1: " + firstLine;
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);
    when(gitRepoFiles.grepPatchSet(config, change, "typing", null)).thenReturn(List.of(match));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    assertEquals(match, output);
  }

  @Test
  public void treeFiltersToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(List.of("changed.py", "pre_existing.py"));
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));

    String output = tools.execute("tree", "{}");

    assertEquals("changed.py", output);
  }

  @Test
  public void getContentMarksPreexistingFiles() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "context.py")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));

    String output = tools.execute("get_content", "{\"file_path\":\"context.py\"}");

    assertTrue(output.startsWith("NOTE: This file is pre-existing repository context"));
    assertTrue(output.endsWith(content));
  }

  @Test
  public void grepFiltersToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("changed.py")))
        .thenReturn(List.of("changed.py:1: match"));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    assertEquals("changed.py:1: match", output);
  }

  @Test
  public void grepPreservesColonInChangedFilePath() throws Exception {
    String match = "schemas/v1:beta.py:1: match";
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("schemas/v1:beta.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("schemas/v1:beta.py")))
        .thenReturn(List.of(match));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    assertEquals(match, output);
  }

  @Test
  public void prefixedPathsResolveToReviewGroupMemberRepository() throws Exception {
    GerritChange coreLibs = reviewGroupMember("core-libs", 11);
    OnDemandCodeContextTools groupTools = reviewGroupTools(coreLibs);
    when(gitRepoFiles.getPatchSetFileContent(coreLibs, "src/span.h")).thenReturn("span");
    when(gitRepoFiles.getPatchSetChangedFiles(coreLibs)).thenReturn(Set.of("src/span.h"));

    String output =
        groupTools.execute(
            "get_content", "{\"file_path\":\"reviewai-topic-change-1/core-libs/src/span.h\"}");

    assertEquals("span", output);
  }

  @Test
  public void unprefixedPathsResolveToPrimaryChangeInReviewGroup() throws Exception {
    OnDemandCodeContextTools groupTools = reviewGroupTools(reviewGroupMember("core-libs", 11));
    when(gitRepoFiles.getPatchSetFileContent(change, "context.py")).thenReturn("primary");
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    assertEquals("primary", groupTools.execute("get_content", "{\"file_path\":\"context.py\"}"));
  }

  @Test
  public void treeOfReviewGroupMemberIsReportedWithItsPrefix() throws Exception {
    GerritChange coreLibs = reviewGroupMember("core-libs", 11);
    OnDemandCodeContextTools groupTools = reviewGroupTools(coreLibs);
    when(gitRepoFiles.getPatchSetFileTree(config, coreLibs, "src"))
        .thenReturn(List.of("src/span.h", "src/other.h"));
    when(gitRepoFiles.getPatchSetChangedFiles(coreLibs)).thenReturn(Set.of("src/span.h"));

    String output =
        groupTools.execute("tree", "{\"subdir\":\"reviewai-topic-change-1/core-libs/src\"}");

    assertEquals("reviewai-topic-change-1/core-libs/src/span.h", output);
  }

  @Test
  public void grepSearchesEveryReviewGroupMemberWithPrefixes() throws Exception {
    GerritChange coreLibs = reviewGroupMember("core-libs", 11);
    OnDemandCodeContextTools groupTools = reviewGroupTools(coreLibs);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("main.c"));
    when(gitRepoFiles.getPatchSetChangedFiles(coreLibs)).thenReturn(Set.of("src/span.h"));
    when(gitRepoFiles.grepPatchSet(config, change, "span", Set.of("main.c")))
        .thenReturn(List.of("main.c:3: use_span();"));
    when(gitRepoFiles.grepPatchSet(config, coreLibs, "span", Set.of("src/span.h")))
        .thenReturn(List.of("src/span.h:1: void use_span();"));

    String output = groupTools.execute("grep", "{\"string\":\"span\"}");

    assertEquals(
        "reviewai-topic-change-0/PRIME/main.c:3: use_span();\n"
            + "reviewai-topic-change-1/core-libs/src/span.h:1: void use_span();",
        output);
  }

  @Test
  public void unsupportedToolReturnsEmptyOutput() {
    assertEquals("", tools.execute("get_context", "{}"));
  }

  private OnDemandCodeContextTools reviewGroupTools(GerritChange member) {
    Map<String, GerritChange> changesByPrefix = new LinkedHashMap<>();
    changesByPrefix.put("reviewai-topic-change-0/PRIME/", change);
    changesByPrefix.put("reviewai-topic-change-1/" + member.getProjectName() + "/", member);
    return new OnDemandCodeContextTools(config, change, gitRepoFiles, changesByPrefix);
  }

  private static GerritChange reviewGroupMember(String project, int number) {
    GerritChange member =
        new GerritChange(
            Project.nameKey(project),
            BranchNameKey.create(Project.nameKey(project), "master"),
            Change.key("I" + number));
    member.setChangeNumber(number);
    member.setPatchSetNumber(1);
    return member;
  }

  private String readTestFile(String filename) throws Exception {
    return Files.readString(BASE_PATH.resolve(filename));
  }

  private List<String> readTestFileLines(String filename) throws Exception {
    return Files.readAllLines(BASE_PATH.resolve(filename));
  }
}
