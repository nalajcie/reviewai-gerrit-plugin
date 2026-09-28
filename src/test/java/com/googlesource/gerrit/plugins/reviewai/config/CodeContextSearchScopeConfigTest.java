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

package com.googlesource.gerrit.plugins.reviewai.config;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.CodeContextSearchScope;
import org.junit.Test;

public class CodeContextSearchScopeConfigTest {

  @Test
  public void defaultsToChangedFiles() {
    assertEquals(CodeContextSearchScope.CHANGED_FILES, config(null, null).getCodeContextSearchScope());
  }

  @Test
  public void projectValueOverridesGlobalValue() {
    assertEquals(
        CodeContextSearchScope.REPOSITORY,
        config("CHANGED_FILES", "REPOSITORY").getCodeContextSearchScope());
  }

  @Test
  public void unknownValueFallsBackToChangedFiles() {
    assertEquals(
        CodeContextSearchScope.CHANGED_FILES, config("EVERYTHING", null).getCodeContextSearchScope());
  }

  private static Configuration config(String globalValue, String projectValue) {
    PluginConfig global = mock(PluginConfig.class);
    PluginConfig project = mock(PluginConfig.class);
    when(global.getString(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                "codeContextSearchScope".equals(invocation.getArgument(0)) && globalValue != null
                    ? globalValue
                    : invocation.getArgument(1));
    when(project.getString("codeContextSearchScope")).thenReturn(projectValue);
    return new Configuration(null, null, global, project, "ai@example.com", Account.id(1));
  }
}
