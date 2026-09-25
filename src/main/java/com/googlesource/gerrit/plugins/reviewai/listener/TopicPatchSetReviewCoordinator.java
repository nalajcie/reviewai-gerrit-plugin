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

import com.google.gerrit.server.events.PatchSetEvent;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TopicPatchSetReviewCoordinator {
  private static final int MAX_PROCESSED_EVENT_KEYS = 1000;

  private final Map<BatchKey, Batch> pendingBatches = new LinkedHashMap<>();
  private final LinkedHashMap<String, Boolean> processedEventKeys = new LinkedHashMap<>();
  private final LinkedHashMap<String, Boolean> reviewedGroupSignatures = new LinkedHashMap<>();

  public synchronized void recordEvent(PatchSetEvent event) {
    GerritChange change = new GerritChange(event);
    Optional<TopicKey> topicKey = TopicKey.from(change);
    if (topicKey.isEmpty()) {
      return;
    }
    record(topicKey.get(), change);
  }

  public synchronized Optional<List<GerritChange>> awaitBatch(GerritChange change, int waitMs)
      throws InterruptedException {
    Optional<TopicKey> topicKey = TopicKey.from(change);
    if (topicKey.isEmpty()) {
      return Optional.empty();
    }
    return awaitBatch(topicKey.get(), change, waitMs);
  }

  /**
   * Waits for the patch set events that may belong to the same multi-project review group. Events
   * are batched by topic across projects, or by project and branch for changes without a topic.
   * Returns the batched changes to the caller that claims the batch, and an empty list to the other
   * callers.
   */
  public synchronized List<GerritChange> awaitReviewGroupBatch(GerritChange change, int waitMs)
      throws InterruptedException {
    return awaitBatch(ReviewGroupKey.from(change), change, waitMs).orElse(List.of());
  }

  /**
   * Claims the review of a review group identified by its members' change and patch set numbers.
   * Returns {@code false} when the same group, at the same patch sets, was already claimed.
   */
  public synchronized boolean claimReviewGroup(List<GerritChange> members) {
    String signature =
        members.stream()
            .map(
                member ->
                    member.getProjectName()
                        + "~"
                        + member.getChangeNumber().map(String::valueOf).orElse("?")
                        + "/"
                        + member
                            .getPatchSetAttribute()
                            .map(patchSet -> String.valueOf(patchSet.number))
                            .orElse("?"))
            .sorted()
            .collect(Collectors.joining(","));
    if (reviewedGroupSignatures.containsKey(signature)) {
      log.debug("Skipping already claimed review group {}", signature);
      return false;
    }
    remember(reviewedGroupSignatures, signature);
    return true;
  }

  private Optional<List<GerritChange>> awaitBatch(
      BatchKey batchKey, GerritChange change, int waitMs) throws InterruptedException {
    String eventKey = change.getPatchSetEventKey();
    if (processedEventKeys.containsKey(eventKey)) {
      log.debug("Skipping already processed topic patch set event {}", eventKey);
      return Optional.of(List.of());
    }

    Batch batch = record(batchKey, change);
    long deadline = batch.createdAtMillis + waitMs;
    while (!batch.claimed) {
      long remainingMillis = deadline - System.currentTimeMillis();
      if (remainingMillis <= 0) {
        batch.claimed = true;
        pendingBatches.remove(batchKey);
        List<GerritChange> changes = batch.changes();
        changes.forEach(queuedChange -> rememberProcessed(queuedChange.getPatchSetEventKey()));
        notifyAll();
        return Optional.of(changes);
      }
      wait(remainingMillis);
    }
    return Optional.of(List.of());
  }

  private Batch record(BatchKey batchKey, GerritChange change) {
    Batch batch = pendingBatches.computeIfAbsent(batchKey, ignored -> new Batch());
    batch.record(change);
    notifyAll();
    return batch;
  }

  private void rememberProcessed(String eventKey) {
    remember(processedEventKeys, eventKey);
  }

  private static void remember(LinkedHashMap<String, Boolean> keys, String key) {
    keys.put(key, Boolean.TRUE);
    while (keys.size() > MAX_PROCESSED_EVENT_KEYS) {
      String firstKey = keys.keySet().iterator().next();
      keys.remove(firstKey);
    }
  }

  private static class Batch {
    private final long createdAtMillis = System.currentTimeMillis();
    private final Map<String, GerritChange> changesByEventKey = new LinkedHashMap<>();
    private boolean claimed;

    void record(GerritChange change) {
      changesByEventKey.putIfAbsent(change.getPatchSetEventKey(), change);
    }

    List<GerritChange> changes() {
      return new ArrayList<>(changesByEventKey.values());
    }
  }

  private interface BatchKey {}

  private record ReviewGroupKey(String project, String branch, String topic) implements BatchKey {
    static ReviewGroupKey from(GerritChange change) {
      return change
          .getTopic()
          .map(topic -> new ReviewGroupKey(null, null, topic))
          .orElseGet(
              () ->
                  new ReviewGroupKey(
                      change.getProjectName(), change.getBranchNameKey().branch(), null));
    }
  }

  private record TopicKey(String project, String branch, String topic) implements BatchKey {
    static Optional<TopicKey> from(GerritChange change) {
      return change
          .getTopic()
          .map(
              topic ->
                  new TopicKey(change.getProjectName(), change.getBranchNameKey().branch(), topic));
    }
  }
}
