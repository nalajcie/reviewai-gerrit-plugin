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

import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the multi-project review group of a change when {@code topicReviewScope} is {@code
 * SUBMITTED_TOGETHER}: the open changes that Gerrit submits together with it, in any project.
 */
@Slf4j
public class ReviewGroupResolver {
  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  ReviewGroupResolver(ConfigCreator configCreator, AiReviewPermission aiReviewPermission) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
  }

  /**
   * Returns the review group of {@code change}, starting with the change itself. Each member
   * carries its own project configuration and whether ReviewAI reviews its project.
   *
   * @param changeConfig the configuration of {@code change} when its event already passed the
   *     review checks, in which case it is reviewable; {@code null} to load the configuration and
   *     check the project as for any other member
   */
  public List<ReviewGroupMember> resolve(
      Configuration changeConfig, GerritClient gerritClient, GerritChange change) {
    List<ReviewGroupMember> members = new ArrayList<>();
    members.add(
        changeConfig == null
            ? toMember(change, null)
            : new ReviewGroupMember(change, changeConfig, true));
    Configuration fallbackConfig = members.getFirst().config();
    for (GerritChange member : gerritClient.getSubmittedTogetherChanges(change)) {
      if (!isSameChange(member, change)) {
        members.add(toMember(member, fallbackConfig));
      }
    }
    log.debug(
        "Review group of change {}: {}",
        change.getFullChangeId(),
        members.stream().map(member -> member.change().getFullChangeId()).toList());
    return members;
  }

  /**
   * Returns {@code true} when every reviewable member satisfies the {@code aiReviewApplicableIf}
   * expression of its own project. Context-only members do not gate the review.
   */
  public boolean isApplicable(List<ReviewGroupMember> members) {
    for (ReviewGroupMember member : members) {
      if (!member.reviewable()) {
        continue;
      }
      String applicableIf = member.config().getAiReviewApplicableIf();
      if (!newApplicabilityChecker(member.config()).isApplicable(member.change(), applicableIf)) {
        log.info(
            "Review group deferred: AI review applicability expression '{}' is not satisfied for"
                + " member {}",
            applicableIf,
            member.change().getFullChangeId());
        return false;
      }
    }
    return true;
  }

  private ReviewGroupMember toMember(GerritChange member, Configuration fallbackConfig) {
    Optional<Configuration> memberConfig = createConfig(member);
    return new ReviewGroupMember(
        member,
        memberConfig.orElse(fallbackConfig),
        memberConfig.map(value -> isReviewable(value, member)).orElse(false));
  }

  protected AiReviewApplicabilityChecker newApplicabilityChecker(Configuration config) {
    return new AiReviewApplicabilityChecker(config);
  }

  private Optional<Configuration> createConfig(GerritChange member) {
    try {
      return Optional.of(
          configCreator.createConfig(member.getProjectNameKey(), member.getChangeKey()));
    } catch (Exception e) {
      log.warn(
          "Could not load the configuration of review group member {}; using it as context only",
          member.getFullChangeId(),
          e);
      return Optional.empty();
    }
  }

  private boolean isReviewable(Configuration memberConfig, GerritChange member) {
    return memberConfig.getAiReviewPatchSet()
        && aiReviewPermission.isAiReviewConfigured(member.getProjectNameKey())
        && !aiReviewPermission.isAiReviewExplicitlyDisallowed(
            member.getProjectNameKey(), member.getBranchNameKey().branch());
  }

  private static boolean isSameChange(GerritChange candidate, GerritChange change) {
    if (candidate.getChangeNumber().isPresent() && change.getChangeNumber().isPresent()) {
      return candidate.getChangeNumber().get().equals(change.getChangeNumber().get());
    }
    return Objects.equals(candidate.getFullChangeId(), change.getFullChangeId());
  }
}
