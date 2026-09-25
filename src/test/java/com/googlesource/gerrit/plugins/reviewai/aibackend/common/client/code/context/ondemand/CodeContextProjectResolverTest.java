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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.CodeContextProjectResolver.ProjectRef;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CodeContextProject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CodeContextProjectResolverTest {
  private static final Account.Id AI_USER = Account.id(1000);

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private final GitRepositoryManager repositoryManager = mock(GitRepositoryManager.class);
  private final PermissionBackend permissionBackend =
      mock(PermissionBackend.class, RETURNS_DEEP_STUBS);
  private final Configuration config = mock(Configuration.class);

  @Test
  public void parsesProjectAndOptionalRef() {
    assertEquals(
        Optional.of(new ProjectRef("libs", "refs/heads/master")),
        CodeContextProjectResolver.parse("libs"));
    assertEquals(
        Optional.of(new ProjectRef("libs", "refs/heads/main")),
        CodeContextProjectResolver.parse(" libs:main "));
    assertEquals(
        Optional.of(new ProjectRef("platform/libs", "refs/tags/v1.0")),
        CodeContextProjectResolver.parse("platform/libs:refs/tags/v1.0"));
    assertEquals(
        Optional.of(new ProjectRef("libs", "refs/heads/release/2.0")),
        CodeContextProjectResolver.parse("libs:release/2.0"));
    assertEquals(Optional.empty(), CodeContextProjectResolver.parse(":main"));
  }

  @Test
  public void resolvesReadableProjectsToCommits() throws Exception {
    try (Git git = Git.init().setDirectory(tempFolder.newFolder("libs")).call()) {
      RevCommit commit =
          git.commit()
              .setAllowEmpty(true)
              .setMessage("Initial commit")
              .setAuthor("Test", "test@example.com")
              .call();
      when(repositoryManager.openRepository(Project.nameKey("libs")))
          .thenReturn(git.getRepository());
      when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
      when(config.getUserId()).thenReturn(AI_USER);
      when(config.getCodeContextProject()).thenReturn(List.of("libs:master", "hidden", "libs"));
      when(permissionBackend
              .absentUser(AI_USER)
              .project(Project.nameKey("libs"))
              .ref("refs/heads/master")
              .test(RefPermission.READ))
          .thenReturn(true);
      when(permissionBackend
              .absentUser(AI_USER)
              .project(Project.nameKey("hidden"))
              .ref("refs/heads/master")
              .test(RefPermission.READ))
          .thenReturn(false);

      List<CodeContextProject> projects =
          new CodeContextProjectResolver(repositoryManager, permissionBackend).resolve(config);

      assertEquals(
          List.of(new CodeContextProject("libs", "refs/heads/master", commit.name())), projects);
    }
  }

  @Test
  public void resolvesNothingWithoutOnDemandCodeContext() throws Exception {
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.NONE);
    when(config.getCodeContextProject()).thenReturn(List.of("libs"));

    assertTrue(
        new CodeContextProjectResolver(repositoryManager, permissionBackend)
            .resolve(config)
            .isEmpty());
    verifyNoInteractions(repositoryManager);
  }

  @Test
  public void skipsProjectsWhoseRefDoesNotExist() throws Exception {
    try (Git git = Git.init().setDirectory(tempFolder.newFolder("empty")).call()) {
      when(repositoryManager.openRepository(any(Project.NameKey.class)))
          .thenReturn(git.getRepository());
      when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
      when(config.getUserId()).thenReturn(AI_USER);
      when(config.getCodeContextProject()).thenReturn(List.of("empty:missing"));
      when(permissionBackend
              .absentUser(AI_USER)
              .project(Project.nameKey("empty"))
              .ref("refs/heads/missing")
              .test(RefPermission.READ))
          .thenReturn(true);

      assertTrue(
          new CodeContextProjectResolver(repositoryManager, permissionBackend)
              .resolve(config)
              .isEmpty());
    }
  }
}
