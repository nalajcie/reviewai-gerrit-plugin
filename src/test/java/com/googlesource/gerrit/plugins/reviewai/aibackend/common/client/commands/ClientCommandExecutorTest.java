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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ClientCommandExecutorTest {
  @Mock private Configuration config;
  @Mock private GerritChange change;
  @Mock private PluginDataHandlerProvider pluginDataHandlerProvider;
  @Mock private PluginDataHandler changeDataHandler;
  @Mock private Localizer localizer;
  @Mock private PluginChatMemoryStore chatMemoryStore;
  @Mock private ReviewConcernPublisher reviewConcernPublisher;
  @Mock private ReviewFeedbackPublisher reviewFeedbackPublisher;

  @Test
  public void forgetThreadClearsLangChainMemoryForCurrentChangeAndPatchSet() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setPreviousReviewConcernLedger(new ReviewConcernLedger());
    changeSetData.setIncrementalPatchSet("stale incremental patch");
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    when(changeDataHandler.getValue("conversationId")).thenReturn("conv-1");
    when(change.getFullChangeId()).thenReturn("change~1");
    PatchSetAttribute patchSetAttribute = new PatchSetAttribute();
    patchSetAttribute.number = 1;
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSetAttribute));
    when(localizer.getText("message.command.thread.forget")).thenReturn("forgot");

    ClientCommandExecutor executor =
        new ClientCommandExecutor(
            config,
            changeSetData,
            change,
            null,
            pluginDataHandlerProvider,
            localizer,
            null,
            chatMemoryStore,
            reviewConcernPublisher,
            reviewFeedbackPublisher,
            new DisabledClientCommandExtension());

    executor.executeCommand(ClientCommandBase.CommandSet.FORGET_THREAD, Map.of(), Map.of(), "");

    verify(chatMemoryStore).deleteMessagesForChangeSet("change~1", 1);
    verify(changeDataHandler).removeValue(OpenAiConversation.getMessagesConversationKey());
    verify(reviewConcernPublisher).clear(change);
    verify(reviewFeedbackPublisher).forget(change);
    assertEquals("forgot", changeSetData.getReviewSystemMessage());
    assertNull(changeSetData.getPreviousReviewConcernLedger());
    assertNull(changeSetData.getIncrementalPatchSet());
  }

  @Test
  public void helpForAUserInTheProductionBuildListsOnlyUsableCommands() {
    String help = help(AiRole.USER, false, "");

    assertTrue(help.contains("message.command.help.review.nodebug"));
    assertTrue(help.contains("message.command.help.suggest"));
    for (String hidden :
        List.of("configure", "show", "directives", "forget_thread", "notes.debug", "help.review\n")) {
      assertFalse(hidden, help.contains("message.command.help." + hidden));
    }
  }

  @Test
  public void helpForAnAdministratorInTheDevBuildListsEverything() {
    String help = help(AiRole.ADMINISTRATOR, true, "");

    for (String shown :
        List.of("configure", "show", "directives", "forget_thread", "notes.debug", "review\n")) {
      assertTrue(shown, help.contains("message.command.help." + shown));
    }
  }

  @Test
  public void helpForAModeratorShowsForgetThreadButNoDevCommands() {
    String help = help(AiRole.MODERATOR, false, "");

    assertTrue(help.contains("message.command.help.forget_thread"));
    assertFalse(help.contains("message.command.help.show"));
  }

  @Test
  public void helpForAnUnavailableCommandSaysWhy() {
    assertTrue(help(AiRole.ADMINISTRATOR, false, "show").contains("message.command.dev.build.required"));
    assertTrue(
        help(AiRole.USER, true, "configure")
            .contains(AiRole.ADMINISTRATOR.requiredMessageKey()));
    assertTrue(
        help(AiRole.USER, false, "review")
            .contains("message.command.help.command.review.options.nodebug"));
  }

  private String help(AiRole role, boolean devBuild, String target) {
    ChangeSetData changeSetData = new ChangeSetData(1);
    when(localizer.getText(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ClientCommandExecutor executor =
        new ClientCommandExecutor(
            config,
            changeSetData,
            change,
            null,
            pluginDataHandlerProvider,
            localizer,
            null,
            chatMemoryStore,
            reviewConcernPublisher,
            reviewFeedbackPublisher,
            new DisabledClientCommandExtension());
    executor.setCommandAvailability(new CommandAvailability(role, devBuild));

    executor.executeCommand(ClientCommandBase.CommandSet.HELP, Map.of(), Map.of(), target);

    return changeSetData.getReviewSystemMessage() + "\n";
  }
}
