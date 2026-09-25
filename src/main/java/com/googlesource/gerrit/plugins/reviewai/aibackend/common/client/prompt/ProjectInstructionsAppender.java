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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithSpace;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptConcernReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptNewIssueFinder;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends repository-level project instructions after prompt creation.
 *
 * <p>Project instructions are loaded from files in the target repository. That requires {@link
 * GitRepoFiles}, which in production is backed by Gerrit's {@code GitRepositoryManager}. Keeping
 * that repository-access dependency here lets prompt classes and {@code AiPromptFactory} stay
 * focused on prompt text construction, instead of threading {@link GitRepoFiles} through many
 * prompt constructors that do not need it.
 *
 * <p>The {@link #append(IAiPrompt, GerritChange, String)} guard also preserves the previous
 * behavior: by default, project instructions are appended only for request prompts, not for
 * suggest, router, collector, or specialized-agent prompt types. When {@code
 * aiProjectInstructionsInReviews} is enabled, they are also appended, as a separate section, to the
 * prompts that review a Patch Set.
 */
public class ProjectInstructionsAppender {
  static final String PROJECT_INSTRUCTIONS_SECTION_TITLE = "Project Instructions";

  private final GitRepoFiles gitRepoFiles;
  private final Configuration config;

  public ProjectInstructionsAppender(GitRepoFiles gitRepoFiles) {
    this(gitRepoFiles, null);
  }

  public ProjectInstructionsAppender(GitRepoFiles gitRepoFiles, Configuration config) {
    this.gitRepoFiles = gitRepoFiles;
    this.config = config;
  }

  public String append(IAiPrompt prompt, GerritChange change, String systemInstructions) {
    if (gitRepoFiles == null || systemInstructions == null) {
      return systemInstructions;
    }
    if (prompt instanceof AiPromptRequests) {
      List<String> instructions = new ArrayList<>();
      instructions.add(systemInstructions);
      new ProjectInstructions(change, gitRepoFiles).addProjectInstructions(instructions);
      return joinWithSpace(instructions);
    }
    if (isProjectInstructionsInReviewsEnabled() && isReviewPrompt(prompt)) {
      List<String> projectInstructions = new ArrayList<>();
      new ProjectInstructions(change, gitRepoFiles).addProjectInstructions(projectInstructions);
      if (projectInstructions.isEmpty()) {
        return systemInstructions;
      }
      return joinWithDoubleNewLine(
          List.of(
              systemInstructions,
              AiPromptSections.buildSection(
                  PROJECT_INSTRUCTIONS_SECTION_TITLE, projectInstructions.getFirst())));
    }
    return systemInstructions;
  }

  private boolean isProjectInstructionsInReviewsEnabled() {
    return config != null && config.getAiProjectInstructionsInReviews();
  }

  private static boolean isReviewPrompt(IAiPrompt prompt) {
    return (prompt instanceof AiPromptReview && !(prompt instanceof AiPromptSuggest))
        || prompt instanceof AiPromptConcernReview
        || prompt instanceof AiPromptNewIssueFinder;
  }
}
