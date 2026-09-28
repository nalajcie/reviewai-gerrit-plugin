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

package com.googlesource.gerrit.plugins.reviewai.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.review.topic.ReviewGroupMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

public class ReviewGroupReviewerTest {
  private static final String SUPERPROJECT_PATCH =
      String.join(
          "\n",
          "diff --git a/core-libs b/core-libs",
          "submodule core-libs: revision-old -> revision-11",
          "");
  private static final String CORE_LIBS_PATCH =
      String.join(
          "\n",
          "diff --git a/src/span.h b/src/span.h",
          "--- a/src/span.h",
          "+++ b/src/span.h",
          "@@ -1 +1 @@",
          "-old",
          "+new",
          "");

  private final Configuration config = mock(Configuration.class);
  private final GerritClient gerritClient = mock(GerritClient.class);
  private final PatchSetReviewer patchSetReviewer = mock(PatchSetReviewer.class);
  private final ChangeSetData changeSetData = new ChangeSetData(1);
  private final Localizer localizer = mock(Localizer.class);
  private final AiResponseContent reply = new AiResponseContent("");

  private GerritChange superproject;
  private GerritChange coreLibs;
  private GerritChange prime;
  private TopicPatchSetReviewer reviewer;

  @Before
  public void setUp() throws Exception {
    superproject = change("superproject", 10, "Update submodules");
    coreLibs = change("core-libs", 11, "Add span helpers");
    prime = change("PRIME", 12, "Use span helpers");
    GerritClientData clientData = mock(GerritClientData.class);
    when(clientData.getCommentProperties()).thenReturn(List.of());
    when(clientData.getGerritClientPatchSet()).thenReturn(mock(IGerritClientPatchSet.class));
    when(clientData.getCommentData()).thenReturn(mock(CommentData.class));
    when(localizer.getText(anyString())).thenReturn("localized");
    when(gerritClient.getClientData(any())).thenReturn(clientData);
    when(gerritClient.getPatchSet(superproject)).thenReturn(SUPERPROJECT_PATCH);
    when(gerritClient.getPatchSet(coreLibs)).thenReturn(CORE_LIBS_PATCH);
    when(gerritClient.getPatchSet(prime)).thenReturn(CORE_LIBS_PATCH.replace("span.h", "main.c"));
    when(config.getMaxReviewLines()).thenReturn(1000);
    reply.setReplies(List.of());
    when(patchSetReviewer.getReviewReply(any(), anyString())).thenReturn(reply);
    when(patchSetReviewer.getReviewScores(reply)).thenReturn(List.of());
    reviewer =
        new TopicPatchSetReviewer(config, gerritClient, changeSetData, localizer, patchSetReviewer);
  }

  @Test
  public void mergesMembersWithProjectPrefixesAndPublishesOnlyToReviewableMembers()
      throws Exception {
    List<Map<String, GerritChange>> toolPrefixes = new ArrayList<>();
    doAnswer(
            invocation -> {
              toolPrefixes.add(changeSetData.getReviewGroupChangesByPrefix());
              return reply;
            })
        .when(patchSetReviewer)
        .getReviewReply(any(), anyString());

    reviewer.reviewGroup(members(), false);

    ArgumentCaptor<String> mergedPatch = ArgumentCaptor.forClass(String.class);
    InOrder order = inOrder(gerritClient, patchSetReviewer);
    order.verify(gerritClient).getPatchSet(coreLibs);
    order.verify(gerritClient).retrievePatchSetInfo(superproject);
    order.verify(patchSetReviewer).getReviewReply(eq(superproject), mergedPatch.capture());
    String patch = mergedPatch.getValue();
    assertTrue(patch.contains("ReviewAI review group members:"));
    assertTrue(
        patch.contains(
            "- reviewai-topic-change-1/core-libs/: change 11 in project core-libs, branch master:"
                + " \"Add span helpers\""));
    assertTrue(
        patch.contains(
            "change 12 in project PRIME, branch master: \"Use span helpers\" (context only"));
    assertTrue(patch.contains("diff --git a/reviewai-topic-change-1/core-libs/src/span.h"));
    assertTrue(patch.contains("diff --git a/reviewai-topic-change-2/PRIME/src/main.c"));
    assertTrue(
        patch.contains(
            "submodule core-libs: revision-old -> revision-11 (= change 11 in core-libs)"));
    assertEquals(
        List.of(
            "reviewai-topic-change-0/superproject/",
            "reviewai-topic-change-1/core-libs/",
            "reviewai-topic-change-2/PRIME/"),
        new ArrayList<>(toolPrefixes.getFirst().keySet()));
    verify(patchSetReviewer)
        .publishTopicReviewPart(
            reply, superproject, "reviewai-topic-change-0/superproject/", List.of());
    verify(patchSetReviewer)
        .publishTopicReviewPart(reply, coreLibs, "reviewai-topic-change-1/core-libs/", List.of());
    verify(patchSetReviewer, never()).publishTopicReviewPart(any(), eq(prime), any(), any());
    assertEquals(Map.of(), changeSetData.getReviewGroupChangesByPrefix());
  }

  @Test
  public void reviewsOnlyTriggeringChangeWithMemberListWhenGroupIsTooLarge() throws Exception {
    when(config.getMaxReviewLines()).thenReturn(5);
    when(localizer.getText("message.review.group.over.limit"))
        .thenReturn("Only this change: %d changes, %d lines, limit %d");
    List<String> headers = new ArrayList<>();
    List<String> scopeNotes = new ArrayList<>();
    List<String> progress = new ArrayList<>();
    changeSetData.setReviewProgressListener(progress::add);
    doAnswer(
            invocation -> {
              headers.add(changeSetData.getReviewGroupHeader());
              scopeNotes.add(changeSetData.getReviewScopeNote());
              return null;
            })
        .when(patchSetReviewer)
        .review(superproject, false);

    reviewer.reviewGroup(members(), false);

    InOrder order = inOrder(gerritClient, patchSetReviewer);
    order.verify(gerritClient).getPatchSet(coreLibs);
    order.verify(gerritClient).retrievePatchSetInfo(superproject);
    order.verify(patchSetReviewer).review(superproject, false);
    verify(patchSetReviewer, never()).getReviewReply(any(), anyString());
    verify(patchSetReviewer, never()).publishTopicReviewPart(any(), any(), any(), any());
    assertTrue(headers.getFirst().contains("ReviewAI review group members:"));
    assertTrue(headers.getFirst().contains("Only the patch of change 10 is included below"));
    assertNull(changeSetData.getReviewGroupHeader());
    assertTrue(scopeNotes.getFirst().matches("Only this change: \\d changes, \\d+ lines, limit 5"));
    assertNull(changeSetData.getReviewScopeNote());
    // reported before the review runs, for the waiting Review Agent panel
    assertEquals(List.of(scopeNotes.getFirst()), progress);
  }

  @Test
  public void skipsGroupWhenNoReviewableMemberHasPatch() throws Exception {
    when(patchSetReviewer.shouldSkipAiReviewForEmptyPatchSet(superproject)).thenReturn(true);
    when(patchSetReviewer.shouldSkipAiReviewForEmptyPatchSet(coreLibs)).thenReturn(true);

    reviewer.reviewGroup(members(), false);

    verify(patchSetReviewer, never()).review(any(), anyBoolean());
    verify(patchSetReviewer, never()).getReviewReply(any(), anyString());
  }

  private List<ReviewGroupMember> members() {
    return List.of(
        new ReviewGroupMember(superproject, config, true),
        new ReviewGroupMember(coreLibs, config, true),
        new ReviewGroupMember(prime, config, false));
  }

  private static GerritChange change(String project, int number, String subject) {
    GerritChange change =
        new GerritChange(
            Project.nameKey(project),
            BranchNameKey.create(Project.nameKey(project), "master"),
            Change.key("I" + number));
    change.setChangeNumber(number);
    change.setPatchSetNumber(1);
    change.setPatchSetRevision("revision-" + number);
    change.setSubject(subject);
    return change;
  }
}
