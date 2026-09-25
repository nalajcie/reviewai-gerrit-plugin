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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.SubmittedTogetherInfo;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.List;
import org.junit.Test;

public class GerritClientDetailTest {
  @Test
  public void toGerritChangeCopiesNumericChangeNumber() {
    ChangeInfo changeInfo = new ChangeInfo();
    changeInfo.project = "myProject";
    changeInfo.branch = "myBranchName";
    changeInfo.changeId = "myChangeId";
    changeInfo._number = 15438;
    changeInfo.currentRevision = "revision-3";

    GerritChange change = GerritClientDetail.toGerritChange(changeInfo);

    assertEquals(Integer.valueOf(15438), change.getChangeNumber().orElseThrow());
    assertEquals("revision-3", change.getPatchSetRevision());
  }

  @Test
  public void getSubmittedTogetherChangesReturnsOpenChangesAcrossProjects() throws Exception {
    Configuration config = mock(Configuration.class);
    GerritApi gerritApi = mock(GerritApi.class);
    Changes changes = mock(Changes.class);
    ChangeApi changeApi = mock(ChangeApi.class);
    SubmittedTogetherInfo info = new SubmittedTogetherInfo();
    info.changes =
        List.of(
            changeInfo("superproject", 10, "Update submodules", ChangeStatus.NEW),
            changeInfo("core-libs", 11, "Add span helpers", ChangeStatus.NEW),
            changeInfo("dsp-libs", 12, "Already merged", ChangeStatus.MERGED));
    GerritChange change = GerritClientDetail.toGerritChange(info.changes.getFirst());
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("superproject", "master", "I10")).thenReturn(changeApi);
    when(changeApi.submittedTogether(any(), any())).thenReturn(info);

    List<GerritChange> members =
        new GerritClientDetail(config, new ChangeSetData(1)).getSubmittedTogetherChanges(change);

    assertEquals(
        List.of("superproject", "core-libs"),
        members.stream().map(member -> member.getProjectName()).toList());
    assertEquals(Integer.valueOf(11), members.get(1).getChangeNumber().orElseThrow());
    assertEquals("Add span helpers", members.get(1).getSubject().orElseThrow());
    assertEquals("revision-11", members.get(1).getPatchSetRevision());
  }

  private static ChangeInfo changeInfo(
      String project, int number, String subject, ChangeStatus status) {
    ChangeInfo changeInfo = new ChangeInfo();
    changeInfo.project = project;
    changeInfo.branch = "master";
    changeInfo.changeId = "I" + number;
    changeInfo._number = number;
    changeInfo.subject = subject;
    changeInfo.status = status;
    changeInfo.currentRevision = "revision-" + number;
    changeInfo.currentRevisionNumber = 1;
    return changeInfo;
  }
}
