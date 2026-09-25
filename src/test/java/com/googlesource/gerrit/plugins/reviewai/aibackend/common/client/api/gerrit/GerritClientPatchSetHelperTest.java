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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class GerritClientPatchSetHelperTest {
  private static final String OLD_SHA = "1111111111111111111111111111111111111111";
  private static final String NEW_SHA = "2222222222222222222222222222222222222222";
  private static final String HEADER = "Subject: [PATCH] Update submodules\n\n";
  private static final String GITLINK_DIFF =
      String.join(
          "\n",
          "diff --git a/core-libs b/core-libs",
          "index 1111111..2222222 160000",
          "--- a/core-libs",
          "+++ b/core-libs",
          "@@ -1 +1 @@",
          "-Subproject commit " + OLD_SHA,
          "+Subproject commit " + NEW_SHA,
          "");
  private static final String NEW_GITLINK_DIFF =
      String.join(
          "\n",
          "diff --git a/dsp-libs b/dsp-libs",
          "new file mode 160000",
          "index 0000000..2222222",
          "--- /dev/null",
          "+++ b/dsp-libs",
          "@@ -0,0 +1 @@",
          "+Subproject commit " + NEW_SHA,
          "");
  private static final String FILE_DIFF =
      String.join(
          "\n",
          "diff --git a/README b/README",
          "--- a/README",
          "+++ b/README",
          "@@ -1 +1 @@",
          "-old",
          "+new",
          "");

  @Test
  public void rendersGitlinkUpdatesAsReadableLines() {
    String rendered =
        GerritClientPatchSetHelper.renderGitlinkDiffs(
            HEADER + GITLINK_DIFF + NEW_GITLINK_DIFF + FILE_DIFF);

    assertEquals(
        HEADER
            + "diff --git a/core-libs b/core-libs\n"
            + "submodule core-libs: "
            + OLD_SHA
            + " -> "
            + NEW_SHA
            + "\n"
            + "diff --git a/dsp-libs b/dsp-libs\n"
            + "submodule dsp-libs: (none) -> "
            + NEW_SHA
            + "\n"
            + FILE_DIFF,
        rendered);
  }

  @Test
  public void keepsGitlinksOnlyWhenRequested() {
    String patch = HEADER + GerritClientPatchSetHelper.renderGitlinkDiffs(GITLINK_DIFF) + FILE_DIFF;

    String withoutGitlinks =
        GerritClientPatchSetHelper.filterPatchByEnabledFileExtensions(
            patch, List.of("c"), List.of());
    String withGitlinks =
        GerritClientPatchSetHelper.filterPatchByEnabledFileExtensions(
            patch, List.of("c"), List.of(), true);

    assertEquals(HEADER, withoutGitlinks);
    assertTrue(withGitlinks.contains("submodule core-libs: "));
    assertFalse(withGitlinks.contains("README"));
    assertEquals(
        List.of("core-libs"), GerritClientPatchSetHelper.extractFilesFromPatch(withGitlinks));
  }
}
