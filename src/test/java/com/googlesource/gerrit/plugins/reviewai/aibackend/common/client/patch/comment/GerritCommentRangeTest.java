package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPatchSetFileDiff;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class GerritCommentRangeTest {

  private static final List<String> COMMIT_MESSAGE_FILE =
      List.of(
          "Parent:     1234567 (base)",
          "Author:     Dev <dev@example.com>",
          "AuthorDate: 2026-09-28 16:00:00 +0200",
          "Commit:     Dev <dev@example.com>",
          "CommitDate: 2026-09-28 16:00:00 +0200",
          "",
          "docs: findings and gotchas of the rollout",
          "",
          "What we learned enabling ReviewAI with Gemini 3,",
          "meant to be read before debugging.",
          "",
          "TASK: CI-697");
  private static final GerritCodeRange WHOLE_MESSAGE =
      GerritCodeRange.builder().startLine(7).startCharacter(0).endLine(12).endCharacter(12).build();

  private GerritCommentRange commentRange;

  @Before
  public void setUp() {
    GerritPatchSetFileDiff diff = new GerritPatchSetFileDiff();
    GerritPatchSetFileDiff.Content content = new GerritPatchSetFileDiff.Content();
    content.b = new ArrayList<>(COMMIT_MESSAGE_FILE);
    diff.setContent(List.of(content));
    FileDiffProcessed commitMessage =
        new FileDiffProcessed(mock(Configuration.class), true, diff);
    HashMap<String, FileDiffProcessed> fileDiffs = new HashMap<>();
    fileDiffs.put("/COMMIT_MSG", commitMessage);
    fileDiffs.put("src/a.c", mock(FileDiffProcessed.class));

    GerritChange change = mock(GerritChange.class);
    GerritClient gerritClient = mock(GerritClient.class, RETURNS_DEEP_STUBS);
    when(gerritClient.getClientData(change).getGerritClientPatchSet().getFileDiffsProcessed())
        .thenReturn(fileDiffs);
    commentRange = new GerritCommentRange(gerritClient, change);
  }

  @Test
  public void commitMessageReplyWithoutSnippetIsAnchoredToTheMessage() {
    AiReplyItem reply = AiReplyItem.builder().reply("Subject is vague").build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(Optional.of(WHOLE_MESSAGE), commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void commitMessageReplyIsAnchoredToTheQuotedSubject() {
    AiReplyItem reply =
        AiReplyItem.builder()
            .reply("Use an imperative subject")
            .codeSnippet("docs: findings and gotchas of the rollout")
            .build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(
        Optional.of(
            GerritCodeRange.builder()
                .startLine(7)
                .startCharacter(0)
                .endLine(7)
                .endCharacter(41)
                .build()),
        commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void commitMessageReplyIsAnchoredToQuotedLinesAcrossLineBreaks() {
    AiReplyItem reply =
        AiReplyItem.builder()
            .reply("Say what was learned")
            .codeSnippet("  enabling ReviewAI with Gemini 3,\nmeant to be read")
            .build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(
        Optional.of(
            GerritCodeRange.builder()
                .startLine(9)
                .startCharacter(0)
                .endLine(10)
                .endCharacter(34)
                .build()),
        commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void commitMessageReplyWithUnknownSnippetIsAnchoredToTheMessage() {
    AiReplyItem reply =
        AiReplyItem.builder()
            .reply("Subject is vague")
            .codeSnippet("docs: document rollout gotchas")
            .build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(Optional.of(WHOLE_MESSAGE), commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void commitMessageSuggestionCoversTheWholeMessage() {
    AiReplyItem reply =
        AiReplyItem.builder()
            .reply("```suggestion\ndocs: document rollout gotchas\n```")
            .codeSnippet("docs: findings and gotchas of the rollout")
            .build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(Optional.of(WHOLE_MESSAGE), commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void commitMessageHeaderIsNotMatched() {
    AiReplyItem reply =
        AiReplyItem.builder().reply("Wrong author").codeSnippet("Dev <dev@example.com>").build();
    reply.setFilename("/COMMIT_MSG");

    assertEquals(Optional.of(WHOLE_MESSAGE), commentRange.getGerritCommentRange(reply));
  }

  @Test
  public void fileReplyWithoutSnippetHasNoRange() {
    AiReplyItem reply = AiReplyItem.builder().reply("Consider a helper").build();
    reply.setFilename("src/a.c");

    assertTrue(commentRange.getGerritCommentRange(reply).isEmpty());
  }
}
