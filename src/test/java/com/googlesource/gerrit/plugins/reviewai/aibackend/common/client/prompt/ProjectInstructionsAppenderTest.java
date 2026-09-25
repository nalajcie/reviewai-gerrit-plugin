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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.FileNotFoundException;
import org.junit.Before;
import org.junit.Test;

public class ProjectInstructionsAppenderTest {
  private static final String INSTRUCTIONS_FILE = ".gerrit/ai-instructions.md";
  private static final String SYSTEM_INSTRUCTIONS = "# Role\n\nReview the patch.";

  private final GitRepoFiles gitRepoFiles = mock(GitRepoFiles.class);
  private final GerritChange change = mock(GerritChange.class);
  private final Configuration config = mock(Configuration.class);

  @Before
  public void setUp() throws FileNotFoundException {
    when(gitRepoFiles.getFileContent(change, INSTRUCTIONS_FILE))
        .thenReturn("  Prefer std::span over raw pointers.\n");
  }

  @Test
  public void requestPromptsAlwaysReceiveProjectInstructions() {
    String instructions =
        new ProjectInstructionsAppender(gitRepoFiles, config)
            .append(mock(AiPromptRequests.class), change, "Answer the question.");

    assertEquals("Answer the question. Prefer std::span over raw pointers.", instructions);
  }

  @Test
  public void reviewPromptsSkipProjectInstructionsByDefault() throws FileNotFoundException {
    String instructions =
        new ProjectInstructionsAppender(gitRepoFiles, config)
            .append(mock(AiPromptReview.class), change, SYSTEM_INSTRUCTIONS);

    assertEquals(SYSTEM_INSTRUCTIONS, instructions);
    verify(gitRepoFiles, never()).getFileContent(any(), any());
  }

  @Test
  public void reviewPromptsReceiveProjectInstructionsSectionWhenEnabled() {
    when(config.getAiProjectInstructionsInReviews()).thenReturn(true);
    ProjectInstructionsAppender appender = new ProjectInstructionsAppender(gitRepoFiles, config);
    String expected =
        SYSTEM_INSTRUCTIONS
            + "\n\n"
            + AiPromptSections.buildSection(
                ProjectInstructionsAppender.PROJECT_INSTRUCTIONS_SECTION_TITLE,
                "Prefer std::span over raw pointers.");

    assertEquals(
        expected, appender.append(mock(AiPromptReview.class), change, SYSTEM_INSTRUCTIONS));
    assertEquals(
        expected,
        appender.append(mock(AiPromptSpecializedReviewAgent.class), change, SYSTEM_INSTRUCTIONS));
  }

  @Test
  public void suggestPromptsSkipProjectInstructionsWhenEnabledForReviews() {
    when(config.getAiProjectInstructionsInReviews()).thenReturn(true);

    String instructions =
        new ProjectInstructionsAppender(gitRepoFiles, config)
            .append(mock(AiPromptSuggest.class), change, SYSTEM_INSTRUCTIONS);

    assertEquals(SYSTEM_INSTRUCTIONS, instructions);
  }

  @Test
  public void reviewPromptsAreUnchangedWhenProjectInstructionsAreMissing()
      throws FileNotFoundException {
    when(config.getAiProjectInstructionsInReviews()).thenReturn(true);
    when(gitRepoFiles.getFileContent(change, INSTRUCTIONS_FILE))
        .thenThrow(new FileNotFoundException(INSTRUCTIONS_FILE));

    String instructions =
        new ProjectInstructionsAppender(gitRepoFiles, config)
            .append(mock(AiPromptReview.class), change, SYSTEM_INSTRUCTIONS);

    assertEquals(SYSTEM_INSTRUCTIONS, instructions);
  }
}
