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

import static org.mockito.Mockito.doAnswer;

import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.TopicReviewScope;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReviewGroupPatchSetReviewTest {
  private final Configuration config = mock(Configuration.class);
  private final PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
  private final GerritClient gerritClient = mock(GerritClient.class);
  private final ReviewGroupResolver resolver = mock(ReviewGroupResolver.class);
  private final AiReviewApplicabilityChecker applicabilityChecker =
      mock(AiReviewApplicabilityChecker.class);
  private final TopicPatchSetReviewCoordinator coordinator = new TopicPatchSetReviewCoordinator();

  private GerritChange superproject;
  private GerritChange coreLibs;
  private List<ReviewGroupMember> group;

  @Before
  public void setUp() {
    superproject = change("superproject", 10, "topic-1");
    coreLibs = change("core-libs", 11, "topic-1");
    group =
        List.of(
            new ReviewGroupMember(superproject, config, true),
            new ReviewGroupMember(coreLibs, config, true));
    when(config.getTopicReviewScope()).thenReturn(TopicReviewScope.SUBMITTED_TOGETHER);
    when(config.getTopicPatchSetWaitMs()).thenReturn(0);
    when(resolver.resolve(config, gerritClient, superproject)).thenReturn(group);
  }

  @Test
  public void reviewsSubmittedTogetherGroupWhenEveryMemberIsApplicable() throws Exception {
    when(resolver.isApplicable(group)).thenReturn(true);

    handler(superproject, new ChangeSetData(1)).processEvent();

    verify(reviewer).reviewGroup(group, true);
    verify(gerritClient).retrievePatchSetInfo(coreLibs);
  }

  @Test
  public void defersGroupUntilEveryMemberIsApplicable() throws Exception {
    when(resolver.isApplicable(group)).thenReturn(false);

    handler(superproject, new ChangeSetData(1)).processEvent();

    verify(reviewer, never()).reviewGroup(any(), anyBoolean());
    verify(reviewer, never()).review(any(), anyBoolean());
  }

  @Test
  public void reviewsGroupOnlyOncePerSetOfPatchSets() throws Exception {
    when(resolver.isApplicable(group)).thenReturn(true);
    when(resolver.resolve(null, gerritClient, coreLibs)).thenReturn(group);
    when(resolver.resolve(config, gerritClient, coreLibs)).thenReturn(group);

    handler(superproject, deferredReview()).processEvent();
    handler(coreLibs, deferredReview()).processEvent();

    verify(reviewer, times(1)).reviewGroup(group, true);
  }

  @Test
  public void manualTopicReviewBypassesGatingAndDeduplication() throws Exception {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setForcedTopicReview(true);

    handler(superproject, changeSetData).processEvent();
    handler(superproject, changeSetData).processEvent();

    verify(resolver, never()).isApplicable(any());
    verify(reviewer, times(2)).reviewGroup(group, true);
  }

  @Test
  public void singleChangeGroupIsReviewedAsNormalChange() throws Exception {
    List<ReviewGroupMember> singleGroup =
        List.of(new ReviewGroupMember(superproject, config, true));
    when(resolver.resolve(config, gerritClient, superproject)).thenReturn(singleGroup);

    handler(superproject, new ChangeSetData(1)).processEvent();

    verify(reviewer).review(superproject, true);
    verify(reviewer, never()).reviewGroup(any(), anyBoolean());
  }

  @Test
  public void topicReviewOfAChangeWithoutRelatedChangesSaysSo() throws Exception {
    when(resolver.resolve(config, gerritClient, superproject))
        .thenReturn(List.of(new ReviewGroupMember(superproject, config, true)));
    when(config.getLocaleDefault()).thenReturn(java.util.Locale.ENGLISH);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setForcedTopicReview(true);
    List<String> progress = new ArrayList<>();
    changeSetData.setReviewProgressListener(progress::add);
    List<String> notesDuringReview = new ArrayList<>();
    doAnswer(
            invocation -> {
              notesDuringReview.add(changeSetData.getReviewScopeNote());
              return null;
            })
        .when(reviewer)
        .review(superproject, true);

    handler(superproject, changeSetData).processEvent();

    assertTrue(notesDuringReview.getFirst().startsWith("No related open changes"));
    assertEquals(notesDuringReview, progress);
    assertNull(changeSetData.getReviewScopeNote());
  }

  @Test
  public void coordinatorClaimsReviewGroupOncePerPatchSets() {
    TopicPatchSetReviewCoordinator reviewGroupCoordinator = new TopicPatchSetReviewCoordinator();
    GerritChange newPatchSet = change("core-libs", 11, "topic-1");
    newPatchSet.setPatchSetNumber(2);

    assertTrue(reviewGroupCoordinator.claimReviewGroup(List.of(superproject, coreLibs)));
    assertFalse(reviewGroupCoordinator.claimReviewGroup(List.of(coreLibs, superproject)));
    assertTrue(reviewGroupCoordinator.claimReviewGroup(List.of(superproject, newPatchSet)));
  }

  @Test
  public void coordinatorBatchesReviewGroupEventsByTopicAcrossProjects() throws Exception {
    TopicPatchSetReviewCoordinator reviewGroupCoordinator = new TopicPatchSetReviewCoordinator();

    List<GerritChange> batch = reviewGroupCoordinator.awaitReviewGroupBatch(superproject, 0);

    assertEquals(List.of(superproject), batch);
    assertEquals(List.of(), reviewGroupCoordinator.awaitReviewGroupBatch(superproject, 0));
  }

  private EventHandlerTypePatchSetReview handler(GerritChange change, ChangeSetData changeSetData) {
    return new EventHandlerTypePatchSetReview(
        config,
        changeSetData,
        change,
        reviewer,
        gerritClient,
        coordinator,
        applicabilityChecker,
        resolver,
        true);
  }

  private static ChangeSetData deferredReview() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setDeferredReview(true);
    return changeSetData;
  }

  private static GerritChange change(String project, int number, String topic) {
    GerritChange change =
        new GerritChange(
            Project.nameKey(project),
            BranchNameKey.create(Project.nameKey(project), "master"),
            Change.key("I" + number));
    change.setChangeNumber(number);
    change.setPatchSetNumber(1);
    change.setPatchSetRevision("revision-" + number);
    change.setTopic(topic);
    return change;
  }
}
