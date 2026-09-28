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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FilenameSanitizer {
  private static final String COMMIT_MESSAGE_FILENAME = "/COMMIT_MSG";

  private final List<String> patchSetFiles;

  public FilenameSanitizer(GerritClient gerritClient, GerritChange change) {
    IGerritClientPatchSet gerritClientPatchSet =
        gerritClient.getClientData(change).getGerritClientPatchSet();
    patchSetFiles = gerritClientPatchSet.getPatchSetFiles();
    log.debug("Initialized Patch set files: {}", patchSetFiles);
  }

  public void sanitizeFilename(AiReplyItem replyItem) {
    String filename = replyItem.getFilename();
    log.debug("Sanitizing filename: {}", filename);
    if (filename == null
        || filename.isEmpty()
        || patchSetFiles.contains(filename)
        || COMMIT_MESSAGE_FILENAME.equals(filename)) {
      return;
    }
    // Models often drop the leading slash of Gerrit's commit-message path, which is not among the
    // patch set files, so the substring match below can't find it.
    if (COMMIT_MESSAGE_FILENAME.substring(1).equals(filename)) {
      log.debug("Filename sanitized: {}", COMMIT_MESSAGE_FILENAME);
      replyItem.setFilename(COMMIT_MESSAGE_FILENAME);
      return;
    }
    String sanitizedFilename =
        patchSetFiles.stream().filter(s -> s.contains(filename)).findFirst().orElse(null);
    if (sanitizedFilename == null) {
      log.warn("Filename '{}' not sanitized. PatchSet Files: {}", filename, patchSetFiles);
      return;
    }
    log.debug("Filename sanitized: {}", sanitizedFilename);

    replyItem.setFilename(sanitizedFilename);
  }
}
