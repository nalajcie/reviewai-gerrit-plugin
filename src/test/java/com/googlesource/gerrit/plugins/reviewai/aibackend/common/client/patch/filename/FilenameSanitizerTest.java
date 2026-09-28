package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class FilenameSanitizerTest {

  private FilenameSanitizer sanitizer;

  @Before
  public void setUp() {
    GerritChange change = mock(GerritChange.class);
    GerritClient gerritClient = mock(GerritClient.class, RETURNS_DEEP_STUBS);
    when(gerritClient.getClientData(change).getGerritClientPatchSet().getPatchSetFiles())
        .thenReturn(List.of("docs/01-plugin-configuration.md", "src/a.c"));
    sanitizer = new FilenameSanitizer(gerritClient, change);
  }

  @Test
  public void commitMessageWithoutLeadingSlashIsRestored() {
    assertEquals("/COMMIT_MSG", sanitize("COMMIT_MSG"));
  }

  @Test
  public void partialFilenameIsCompleted() {
    assertEquals("docs/01-plugin-configuration.md", sanitize("01-plugin-configuration.md"));
  }

  @Test
  public void unknownFilenameIsKept() {
    assertEquals("README.md", sanitize("README.md"));
  }

  private String sanitize(String filename) {
    AiReplyItem reply = AiReplyItem.builder().reply("r").build();
    reply.setFilename(filename);
    sanitizer.sanitizeFilename(reply);
    return reply.getFilename();
  }
}
