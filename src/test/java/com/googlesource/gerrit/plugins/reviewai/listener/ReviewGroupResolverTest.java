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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewPermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ReviewGroupResolverTest {
  private final ConfigCreator configCreator = mock(ConfigCreator.class);
  private final AiReviewPermission aiReviewPermission = mock(AiReviewPermission.class);
  private final GerritClient gerritClient = mock(GerritClient.class);
  private final Configuration triggerConfig = mock(Configuration.class);
  private final Configuration coreLibsConfig = mock(Configuration.class);
  private final Configuration primeConfig = mock(Configuration.class);
  private final Map<Configuration, AiReviewApplicabilityChecker> checkers = new HashMap<>();

  private GerritChange superproject;
  private GerritChange coreLibs;
  private GerritChange prime;
  private ReviewGroupResolver resolver;

  @Before
  public void setUp() throws Exception {
    superproject = change("superproject", 10);
    coreLibs = change("core-libs", 11);
    prime = change("PRIME", 12);
    when(gerritClient.getSubmittedTogetherChanges(superproject))
        .thenReturn(List.of(change("superproject", 10), coreLibs, prime));
    when(configCreator.createConfig(coreLibs.getProjectNameKey(), coreLibs.getChangeKey()))
        .thenReturn(coreLibsConfig);
    when(configCreator.createConfig(prime.getProjectNameKey(), prime.getChangeKey()))
        .thenReturn(primeConfig);
    when(coreLibsConfig.getAiReviewPatchSet()).thenReturn(true);
    when(primeConfig.getAiReviewPatchSet()).thenReturn(true);
    when(aiReviewPermission.isAiReviewConfigured(Project.nameKey("core-libs"))).thenReturn(true);
    when(aiReviewPermission.isAiReviewConfigured(Project.nameKey("PRIME"))).thenReturn(false);
    for (Configuration config : List.of(triggerConfig, coreLibsConfig, primeConfig)) {
      checkers.put(config, mock(AiReviewApplicabilityChecker.class));
      when(config.getAiReviewApplicableIf()).thenReturn("label:Verified=+1");
    }
    resolver =
        new ReviewGroupResolver(configCreator, aiReviewPermission) {
          @Override
          protected AiReviewApplicabilityChecker newApplicabilityChecker(Configuration config) {
            return checkers.get(config);
          }
        };
  }

  @Test
  public void resolvesSubmittedTogetherChangesStartingWithTriggeringChange() {
    List<ReviewGroupMember> group = resolver.resolve(triggerConfig, gerritClient, superproject);

    assertEquals(3, group.size());
    assertSame(superproject, group.get(0).change());
    assertSame(triggerConfig, group.get(0).config());
    assertTrue(group.get(0).reviewable());
    assertSame(coreLibs, group.get(1).change());
    assertSame(coreLibsConfig, group.get(1).config());
    assertTrue(group.get(1).reviewable());
  }

  @Test
  public void membersOfProjectsWithoutAiReviewAreContextOnly() {
    List<ReviewGroupMember> group = resolver.resolve(triggerConfig, gerritClient, superproject);

    assertSame(prime, group.get(2).change());
    assertFalse(group.get(2).reviewable());
  }

  @Test
  public void membersWithPatchSetReviewDisabledAreContextOnly() {
    when(coreLibsConfig.getAiReviewPatchSet()).thenReturn(false);

    List<ReviewGroupMember> group = resolver.resolve(triggerConfig, gerritClient, superproject);

    assertFalse(group.get(1).reviewable());
  }

  @Test
  public void groupIsApplicableOnlyWhenEveryReviewableMemberMatchesItsOwnExpression() {
    when(coreLibsConfig.getAiReviewApplicableIf()).thenReturn("label:Code-Style=+1");
    when(checkers.get(triggerConfig).isApplicable(superproject, "label:Verified=+1"))
        .thenReturn(true);
    when(checkers.get(coreLibsConfig).isApplicable(coreLibs, "label:Code-Style=+1"))
        .thenReturn(false);
    List<ReviewGroupMember> group = resolver.resolve(triggerConfig, gerritClient, superproject);

    assertFalse(resolver.isApplicable(group));

    when(checkers.get(coreLibsConfig).isApplicable(coreLibs, "label:Code-Style=+1"))
        .thenReturn(true);

    assertTrue(resolver.isApplicable(group));
    verify(checkers.get(primeConfig), never()).isApplicable(prime, "label:Verified=+1");
  }

  private static GerritChange change(String project, int number) {
    GerritChange change =
        new GerritChange(
            Project.nameKey(project),
            BranchNameKey.create(Project.nameKey(project), "master"),
            Change.key("I" + number));
    change.setChangeNumber(number);
    change.setPatchSetNumber(1);
    change.setPatchSetRevision("revision-" + number);
    return change;
  }
}
