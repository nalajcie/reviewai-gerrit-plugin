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
import java.util.HashMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class GerritCommentRangeTest {

  private static final GerritCodeRange WHOLE_MESSAGE =
      GerritCodeRange.builder().startLine(7).startCharacter(0).endLine(12).endCharacter(24).build();

  private GerritCommentRange commentRange;

  @Before
  public void setUp() {
    FileDiffProcessed commitMessage = mock(FileDiffProcessed.class);
    when(commitMessage.getCommitMessageRange()).thenReturn(Optional.of(WHOLE_MESSAGE));
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
  public void fileReplyWithoutSnippetHasNoRange() {
    AiReplyItem reply = AiReplyItem.builder().reply("Consider a helper").build();
    reply.setFilename("src/a.c");

    assertTrue(commentRange.getGerritCommentRange(reply).isEmpty());
  }
}
