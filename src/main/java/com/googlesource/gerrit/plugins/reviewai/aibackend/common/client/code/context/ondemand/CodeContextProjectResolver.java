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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CodeContextProject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Resolves the {@code codeContextProject} entries to commits that the AI user is allowed to read.
 * Entries the AI user cannot read, or that do not resolve, are skipped with a warning.
 */
@Slf4j
public class CodeContextProjectResolver {
  private static final String DEFAULT_REF = "refs/heads/master";
  private static final String REFS_PREFIX = "refs/";
  private static final String HEADS_PREFIX = "refs/heads/";

  private final GitRepositoryManager repositoryManager;
  private final PermissionBackend permissionBackend;

  @Inject
  public CodeContextProjectResolver(
      GitRepositoryManager repositoryManager, PermissionBackend permissionBackend) {
    this.repositoryManager = repositoryManager;
    this.permissionBackend = permissionBackend;
  }

  /** Returns the readable context projects of {@code config}, or none without ON_DEMAND. */
  public List<CodeContextProject> resolve(Configuration config) {
    if (config.getCodeContextPolicy() != CodeContextPolicies.ON_DEMAND) {
      return List.of();
    }
    List<CodeContextProject> projects = new ArrayList<>();
    Set<String> seenProjects = new HashSet<>();
    for (String entry : config.getCodeContextProject()) {
      parse(entry)
          // The first entry of a project wins, so that a project is exposed at one ref only.
          .filter(projectRef -> seenProjects.add(projectRef.project()))
          .flatMap(projectRef -> resolve(config, projectRef))
          .ifPresent(projects::add);
    }
    return projects;
  }

  private Optional<CodeContextProject> resolve(Configuration config, ProjectRef projectRef) {
    Project.NameKey projectName = Project.nameKey(projectRef.project());
    try {
      if (!permissionBackend
          .absentUser(config.getUserId())
          .project(projectName)
          .ref(projectRef.ref())
          .test(RefPermission.READ)) {
        log.warn(
            "Skipping code context project {}: the AI user cannot read {}",
            projectRef.project(),
            projectRef.ref());
        return Optional.empty();
      }
      try (Repository repository = repositoryManager.openRepository(projectName)) {
        ObjectId commitId = repository.resolve(projectRef.ref() + "^{commit}");
        if (commitId == null) {
          log.warn(
              "Skipping code context project {}: ref {} does not exist",
              projectRef.project(),
              projectRef.ref());
          return Optional.empty();
        }
        return Optional.of(
            new CodeContextProject(projectRef.project(), projectRef.ref(), commitId.name()));
      }
    } catch (Exception e) {
      log.warn("Skipping code context project {} at {}", projectRef.project(), projectRef.ref(), e);
      return Optional.empty();
    }
  }

  /**
   * Parses {@code <project>[:<ref>]}. The ref defaults to {@code refs/heads/master}, and a bare
   * branch name is expanded to {@code refs/heads/<branch>}.
   */
  @VisibleForTesting
  static Optional<ProjectRef> parse(String entry) {
    if (entry == null || entry.isBlank()) {
      return Optional.empty();
    }
    String value = entry.trim();
    // Git refs cannot contain a colon, so the last colon separates the project from the ref.
    int separator = value.lastIndexOf(':');
    String project = separator < 0 ? value : value.substring(0, separator).trim();
    String ref = separator < 0 ? "" : value.substring(separator + 1).trim();
    project = project.replaceAll("^/+", "").replaceAll("/+$", "");
    if (project.isEmpty()) {
      log.warn("Ignoring invalid code context project entry '{}'", entry);
      return Optional.empty();
    }
    if (ref.isEmpty()) {
      ref = DEFAULT_REF;
    } else if (!ref.startsWith(REFS_PREFIX)) {
      ref = HEADS_PREFIX + ref;
    }
    return Optional.of(new ProjectRef(project, ref));
  }

  @VisibleForTesting
  record ProjectRef(String project, String ref) {}
}
