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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.TopicReviewScope;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventHandlerTypePatchSetReview implements IEventHandlerType {
  private final Configuration config;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final PatchSetReviewer reviewer;
  private final GerritClient gerritClient;
  private final boolean administratorUser;
  private final TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator;
  private final AiReviewApplicabilityChecker aiReviewApplicabilityChecker;
  private final ReviewGroupResolver reviewGroupResolver;

  EventHandlerTypePatchSetReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      AiReviewApplicabilityChecker aiReviewApplicabilityChecker,
      boolean administratorUser) {
    this(
        config,
        changeSetData,
        change,
        reviewer,
        gerritClient,
        topicPatchSetReviewCoordinator,
        aiReviewApplicabilityChecker,
        null,
        administratorUser);
  }

  EventHandlerTypePatchSetReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      AiReviewApplicabilityChecker aiReviewApplicabilityChecker,
      ReviewGroupResolver reviewGroupResolver,
      boolean administratorUser) {
    this.reviewGroupResolver = reviewGroupResolver;
    this.config = config;
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    this.administratorUser = administratorUser;
    this.topicPatchSetReviewCoordinator = topicPatchSetReviewCoordinator;
    this.aiReviewApplicabilityChecker = aiReviewApplicabilityChecker;
    log.debug(
        "Initialized EventHandlerTypePatchSetReview for full change ID: {}",
        change.getFullChangeId());
  }

  @Override
  public PreprocessResult preprocessEvent() {
    log.debug(
        "Starting preprocessing for patch set review on change ID: {}", change.getFullChangeId());
    if (!isPatchSetReviewEnabled(change)) {
      log.debug(
          "Patch set review is disabled or not applicable for change ID: {}",
          change.getFullChangeId());
      return PreprocessResult.EXIT;
    }
    if (!isReviewApplicable(change)) {
      return PreprocessResult.EXIT;
    }
    gerritClient.retrievePatchSetInfo(change);
    log.debug("Patch set information retrieved for change ID: {}", change.getFullChangeId());
    return PreprocessResult.OK;
  }

  @Override
  public void processEvent() throws Exception {
    log.debug("Starting patch set review for change ID: {}", change.getFullChangeId());
    if (isReviewGroupScope()
        && (changeSetData.getForcedTopicReview()
            || !changeSetData.getForcedReview()
            || changeSetData.getDeferredReview())) {
      processReviewGroups();
      log.debug("Completed patch set review for change ID: {}", change.getFullChangeId());
      return;
    }
    if (changeSetData.getForcedTopicReview()) {
      processForcedTopicReview();
      log.debug("Completed patch set review for change ID: {}", change.getFullChangeId());
      return;
    }
    Optional<List<GerritChange>> topicBatch =
        topicPatchSetReviewCoordinator.awaitBatch(change, config.getTopicPatchSetWaitMs());
    if (topicBatch.isEmpty()) {
      reviewer.review(change, administratorUser);
      log.debug("Completed patch set review for change ID: {}", change.getFullChangeId());
      return;
    }

    List<GerritChange> topicChanges = topicBatch.get();
    if (topicChanges.isEmpty()) {
      log.debug(
          "Topic patch set review already claimed for change ID: {}", change.getFullChangeId());
      return;
    }
    List<GerritChange> reviewableTopicChanges =
        topicChanges.stream().filter(this::prepareTopicChangeForReview).toList();
    if (reviewableTopicChanges.isEmpty()) {
      return;
    }
    if (reviewableTopicChanges.size() == 1) {
      reviewer.review(reviewableTopicChanges.getFirst(), administratorUser);
    } else {
      reviewer.reviewTopic(reviewableTopicChanges, administratorUser);
    }
    log.debug("Completed patch set review for change ID: {}", change.getFullChangeId());
  }

  private void processForcedTopicReview() throws Exception {
    List<GerritChange> topicChanges = gerritClient.getTopicChanges(change);
    if (topicChanges.isEmpty()) {
      log.info(
          "No topic changes found for forced topic review on change ID: {}",
          change.getFullChangeId());
      reviewer.review(change, administratorUser);
      return;
    }
    List<GerritChange> reviewableTopicChanges =
        topicChanges.stream().filter(this::prepareTopicChangeForReview).toList();
    if (reviewableTopicChanges.size() < 2) {
      reviewer.review(
          reviewableTopicChanges.isEmpty() ? change : reviewableTopicChanges.getFirst(),
          administratorUser);
      return;
    }
    reviewer.reviewTopic(reviewableTopicChanges, administratorUser);
  }

  private boolean isReviewGroupScope() {
    return reviewGroupResolver != null
        && config.getTopicReviewScope() == TopicReviewScope.SUBMITTED_TOGETHER;
  }

  /**
   * Reviews the multi-project review groups of the batched patch set events. A manual topic review
   * reviews the group of this change immediately. Automatic and deferred reviews wait for the other
   * events of the group, review each distinct group once per set of patch sets, and only when every
   * reviewable member satisfies its {@code aiReviewApplicableIf} expression.
   */
  private void processReviewGroups() throws Exception {
    boolean manualReview =
        changeSetData.getForcedTopicReview()
            || (changeSetData.getForcedReview() && !changeSetData.getDeferredReview());
    List<GerritChange> triggeringChanges;
    if (manualReview) {
      triggeringChanges = List.of(change);
    } else {
      triggeringChanges =
          topicPatchSetReviewCoordinator
              .awaitReviewGroupBatch(change, config.getTopicPatchSetWaitMs())
              .stream()
              .filter(
                  triggeringChange ->
                      triggeringChange == change || isPatchSetReviewEnabled(triggeringChange))
              .toList();
      if (triggeringChanges.isEmpty()) {
        log.debug("Review group batch already claimed for change ID: {}", change.getFullChangeId());
        return;
      }
    }
    for (List<ReviewGroupMember> group : resolveReviewGroups(triggeringChanges)) {
      reviewGroup(group, manualReview);
    }
  }

  private List<List<ReviewGroupMember>> resolveReviewGroups(List<GerritChange> triggeringChanges) {
    // Resolve the group of this change first, so that it is preferred over identical groups of
    // the other batched changes.
    List<GerritChange> orderedTriggeringChanges = new ArrayList<>(triggeringChanges);
    if (orderedTriggeringChanges.remove(change)) {
      orderedTriggeringChanges.addFirst(change);
    }
    List<List<ReviewGroupMember>> groups = new ArrayList<>();
    for (GerritChange triggeringChange : orderedTriggeringChanges) {
      groups.add(
          reviewGroupResolver.resolve(
              triggeringChange == change ? config : null, gerritClient, triggeringChange));
    }
    // A group contained in another group of the batch, for example the group of an ancestor in a
    // relation chain, is reviewed as part of the larger group.
    List<List<ReviewGroupMember>> maximalGroups = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      Set<String> members = memberIds(groups.get(i));
      boolean contained = false;
      for (int j = 0; j < groups.size() && !contained; j++) {
        Set<String> otherMembers = memberIds(groups.get(j));
        contained =
            i != j
                && otherMembers.containsAll(members)
                && (otherMembers.size() > members.size() || j < i);
      }
      if (!contained) {
        maximalGroups.add(groups.get(i));
      }
    }
    return maximalGroups;
  }

  private static Set<String> memberIds(List<ReviewGroupMember> group) {
    return group.stream()
        .map(member -> member.change().getFullChangeId())
        .collect(Collectors.toSet());
  }

  private void reviewGroup(List<ReviewGroupMember> group, boolean manualReview) throws Exception {
    GerritChange triggeringChange = group.getFirst().change();
    if (group.size() == 1) {
      if (triggeringChange == change || prepareTopicChangeForReview(triggeringChange)) {
        reviewer.review(triggeringChange, administratorUser);
      }
      return;
    }
    if (!manualReview && !reviewGroupResolver.isApplicable(group)) {
      return;
    }
    if (!manualReview
        && !topicPatchSetReviewCoordinator.claimReviewGroup(
            group.stream().map(ReviewGroupMember::change).toList())) {
      log.debug(
          "Review group of change {} was already reviewed at its current patch sets",
          triggeringChange.getFullChangeId());
      return;
    }
    for (ReviewGroupMember member : group) {
      if (member.reviewable() && member.change() != change) {
        gerritClient.retrievePatchSetInfo(member.change());
      }
    }
    reviewer.reviewGroup(group, administratorUser);
  }

  private boolean prepareTopicChangeForReview(GerritChange topicChange) {
    if (!isPatchSetReviewEnabled(topicChange) || !isReviewApplicable(topicChange)) {
      return false;
    }
    gerritClient.retrievePatchSetInfo(topicChange);
    return true;
  }

  private boolean isReviewApplicable(GerritChange change) {
    if (changeSetData.getForcedReview()) {
      log.debug(
          "Bypassing AI review applicability expression for forced review of change {}",
          change.getFullChangeId());
      return true;
    }
    String applicableIf = config.getAiReviewApplicableIf();
    if (aiReviewApplicabilityChecker.isApplicable(change, applicableIf)) {
      return true;
    }
    log.debug(
        "AI review applicability expression '{}' is not satisfied for change {}",
        applicableIf,
        change.getFullChangeId());
    return false;
  }

  private boolean isPatchSetReviewEnabled(GerritChange change) {
    if (!config.getAiReviewPatchSet()) {
      log.debug("AI review of patch sets is disabled in configuration.");
      return false;
    }
    Optional<PatchSetAttribute> patchSetAttributeOptional = change.getPatchSetAttribute();
    if (patchSetAttributeOptional.isEmpty()) {
      log.info("No patch set attribute available for change ID: {}", change.getFullChangeId());
      return false;
    }
    PatchSetAttribute patchSetAttribute = patchSetAttributeOptional.get();
    ChangeKind patchSetEventKind = patchSetAttribute.kind;
    // Automatically review code and commit-message changes. If review is forced via command, this
    // condition is bypassed.
    if (patchSetEventKind != ChangeKind.REWORK
        && patchSetEventKind != ChangeKind.NO_CODE_CHANGE
        && patchSetEventKind != ChangeKind.TRIVIAL_REBASE_WITH_MESSAGE_UPDATE
        && !changeSetData.getForcedReview()) {
      log.debug(
          "Change kind '{}' is not reviewable and no forced review, for change ID: {}",
          patchSetEventKind,
          change.getFullChangeId());
      return false;
    }
    String authorUsername =
        patchSetAttribute.author == null ? null : patchSetAttribute.author.username;
    if (authorUsername != null && gerritClient.isDisabledUser(authorUsername)) {
      log.info(
          "Patch set review is disabled for user '{}', change ID: {}",
          authorUsername,
          change.getFullChangeId());
      return false;
    }
    if (gerritClient.isWorkInProgress(change)) {
      log.debug("Change is marked as Work In Progress for change ID: {}", change.getFullChangeId());
      return false;
    }
    return true;
  }
}
