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

package com.googlesource.gerrit.plugins.reviewai.review;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicPatchSetReviewMerger;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicReviewPatchSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class TopicPatchSetReviewer {
  private final Configuration config;
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final Localizer localizer;
  private final PatchSetReviewer patchSetReviewer;
  private final TopicPatchSetReviewMerger topicPatchSetReviewMerger;

  TopicPatchSetReviewer(
      Configuration config,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer,
      PatchSetReviewer patchSetReviewer) {
    this(
        config,
        gerritClient,
        changeSetData,
        localizer,
        patchSetReviewer,
        new TopicPatchSetReviewMerger());
  }

  TopicPatchSetReviewer(
      Configuration config,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer,
      PatchSetReviewer patchSetReviewer,
      TopicPatchSetReviewMerger topicPatchSetReviewMerger) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.localizer = localizer;
    this.patchSetReviewer = patchSetReviewer;
    this.topicPatchSetReviewMerger = topicPatchSetReviewMerger;
  }

  void review(List<GerritChange> changes) throws Exception {
    review(changes, false);
  }

  void review(List<GerritChange> changes, boolean includeAiFailureDetails) throws Exception {
    log.debug("Starting topic review process for {} changes", changes.size());
    List<TopicReviewPatchSet> patchSets = new ArrayList<>();
    changeSetData.setReviewRepeatedCommentsMessage(null);
    for (GerritChange topicChange : changes) {
      gerritClient.requireCurrentRevision(topicChange);
      String patchSet = gerritClient.getPatchSet(topicChange);
      if (!patchSetReviewer.shouldSkipAiReviewForEmptyPatchSet(topicChange)) {
        patchSets.add(topicPatchSetReviewMerger.patchSet(topicChange, patchSets.size(), patchSet));
      }
    }
    if (patchSets.size() < 2) {
      if (patchSets.isEmpty()) {
        log.debug("No topic patch sets remain after patch filtering.");
        return;
      }
      patchSetReviewer.review(patchSets.getFirst().change(), includeAiFailureDetails);
      return;
    }

    GerritChange primaryChange = patchSets.getFirst().change();
    reviewAndPublish(
        primaryChange,
        topicPatchSetReviewMerger.buildMergedPatchSet(patchSets),
        patchSets,
        includeAiFailureDetails);
  }

  /**
   * Reviews a multi-project review group as one merged patch. The first member is the change that
   * triggered the review. Members that are not reviewable are included as read-only context and
   * receive no comments or votes. When the merged patch exceeds {@code maxReviewLines}, only the
   * triggering change is reviewed, with the member list prepended to its patch.
   */
  void reviewGroup(List<ReviewGroupMember> members, boolean includeAiFailureDetails)
      throws Exception {
    log.debug("Starting review group process for {} changes", members.size());
    List<TopicReviewPatchSet> patchSets = new ArrayList<>();
    Set<GerritChange> reviewableChanges = new HashSet<>();
    changeSetData.setReviewRepeatedCommentsMessage(null);
    for (ReviewGroupMember member : members) {
      GerritChange memberChange = member.change();
      gerritClient.requireCurrentRevision(memberChange);
      String patchSet = gerritClient.getPatchSet(memberChange);
      if (!patchSetReviewer.shouldSkipAiReviewForEmptyPatchSet(memberChange)) {
        patchSets.add(
            topicPatchSetReviewMerger.reviewGroupPatchSet(
                memberChange, patchSets.size(), patchSet));
      }
      if (member.reviewable()) {
        reviewableChanges.add(memberChange);
      }
    }
    List<TopicReviewPatchSet> publishedPatchSets =
        patchSets.stream()
            .filter(patchSet -> reviewableChanges.contains(patchSet.change()))
            .toList();
    if (publishedPatchSets.isEmpty()) {
      log.debug("No reviewable review group patch sets remain after patch filtering.");
      return;
    }
    GerritChange triggeringChange = members.getFirst().change();
    GerritChange singleReviewedChange =
        publishedPatchSets.stream().anyMatch(patchSet -> patchSet.change() == triggeringChange)
            ? triggeringChange
            : publishedPatchSets.getFirst().change();
    if (patchSets.size() < 2) {
      reviewSingleGroupMember(members, patchSets, singleReviewedChange, includeAiFailureDetails);
      return;
    }
    String mergedPatchSet =
        topicPatchSetReviewMerger.buildMergedReviewGroupPatchSet(members, patchSets);
    int mergedPatchSetLines = mergedPatchSet.split("\n").length;
    if (mergedPatchSetLines > config.getMaxReviewLines()) {
      log.info(
          "Review group of change {} has {} patch lines, more than maxReviewLines ({}); reviewing"
              + " only change {}",
          triggeringChange.getFullChangeId(),
          mergedPatchSetLines,
          config.getMaxReviewLines(),
          singleReviewedChange.getFullChangeId());
      reviewSingleGroupMember(members, patchSets, singleReviewedChange, includeAiFailureDetails);
      return;
    }

    Map<String, GerritChange> changesByPrefix = new LinkedHashMap<>();
    patchSets.forEach(patchSet -> changesByPrefix.put(patchSet.prefix(), patchSet.change()));
    changeSetData.setReviewGroupChangesByPrefix(changesByPrefix);
    try {
      reviewAndPublish(
          publishedPatchSets.getFirst().change(),
          mergedPatchSet,
          publishedPatchSets,
          includeAiFailureDetails);
    } finally {
      changeSetData.setReviewGroupChangesByPrefix(Map.of());
    }
  }

  private void reviewSingleGroupMember(
      List<ReviewGroupMember> members,
      List<TopicReviewPatchSet> patchSets,
      GerritChange reviewedChange,
      boolean includeAiFailureDetails)
      throws Exception {
    Map<String, GerritChange> otherChangesByPrefix = new LinkedHashMap<>();
    patchSets.stream()
        .filter(patchSet -> patchSet.change() != reviewedChange)
        .forEach(patchSet -> otherChangesByPrefix.put(patchSet.prefix(), patchSet.change()));
    changeSetData.setReviewGroupChangesByPrefix(otherChangesByPrefix);
    changeSetData.setReviewGroupHeader(
        topicPatchSetReviewMerger.buildReviewGroupHeader(members, patchSets)
            + "\n\nOnly the patch of change "
            + reviewedChange.getChangeNumber().map(String::valueOf).orElse("?")
            + " is included below; the other members are listed for context. Use unprefixed"
            + " filenames in inline replies.");
    // Reading the other members' patches left their comments and revision base in the shared
    // client state; reload them for the change that is reviewed (pending feedback, history).
    gerritClient.retrievePatchSetInfo(reviewedChange);
    try {
      patchSetReviewer.review(reviewedChange, includeAiFailureDetails);
    } finally {
      changeSetData.setReviewGroupChangesByPrefix(Map.of());
      changeSetData.setReviewGroupHeader(null);
    }
  }

  private void reviewAndPublish(
      GerritChange primaryChange,
      String mergedPatchSet,
      List<TopicReviewPatchSet> publishedPatchSets,
      boolean includeAiFailureDetails)
      throws Exception {
    gerritClient.retrievePatchSetInfo(primaryChange);
    gerritClient.getPatchSet(primaryChange);
    ChangeSetDataHandler.update(config, primaryChange, gerritClient, changeSetData, localizer);
    AiResponseContent reviewReply = null;
    try {
      reviewReply = patchSetReviewer.getReviewReply(primaryChange, mergedPatchSet);
      log.debug("AI final response for topic review: {}", reviewReply);
    } catch (AiRequestSupersededException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "AI request failed for topic review rooted at `{}`. domain=`{}`, model=`{}`. Cause: {}",
          primaryChange.getFullChangeId(),
          config.getAiDomain(),
          config.getAiModel(),
          e.getMessage(),
          e);
      String publicErrorMessage =
          SystemMessageFormatter.getLocalizedErrorMessage(
              localizer, "message.openai.connection.error");
      changeSetData.setReviewSystemMessage(publicErrorMessage);
      changeSetData.setReviewStatusMessage(
          includeAiFailureDetails
              ? SystemMessageFormatter.getLocalizedErrorMessageWithReason(
                  localizer, "message.openai.connection.error", e)
              : publicErrorMessage);
    }

    if (reviewReply == null && changeSetData.getReviewSystemMessage() == null) {
      log.debug("Skipping Gerrit topic review publication because no AI review was performed.");
      return;
    }

    List<Double> topicReviewScores = patchSetReviewer.getReviewScores(reviewReply);
    for (TopicReviewPatchSet patchSet : publishedPatchSets) {
      gerritClient.requireCurrentRevision(patchSet.change());
    }
    for (TopicReviewPatchSet patchSet : publishedPatchSets) {
      patchSetReviewer.publishTopicReviewPart(
          reviewReply, patchSet.change(), patchSet.prefix(), topicReviewScores);
    }
  }
}
