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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.CodeContextSearchScope;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.Test;

public class LangChainToolScopeTest {

  @Test
  public void changedFilesScopeIsStatedInGrepAndTree() {
    Configuration config = config(CodeContextSearchScope.CHANGED_FILES);

    assertTrue(describe("config/grepTool.json", config).startsWith("Scope: ONLY the files changed"));
    assertTrue(describe("config/treeTool.json", config).startsWith("Scope: ONLY the files changed"));
  }

  @Test
  public void repositoryScopeIsStatedInGrepAndTree() {
    Configuration config = config(CodeContextSearchScope.REPOSITORY);

    assertTrue(describe("config/grepTool.json", config).startsWith("Scope: all text files of the repository"));
    assertTrue(describe("config/treeTool.json", config).startsWith("Scope: the whole repository"));
  }

  @Test
  public void getContentIsUnchangedButMentionsItsLimits() {
    ToolSpecification tool = load("config/getContentTool.json");
    ToolSpecification scoped =
        LangChainClient.withSearchScope(tool, config(CodeContextSearchScope.REPOSITORY));

    assertEquals(tool, scoped);
    assertTrue(tool.description().contains("Binary files are reported without their content"));
  }

  @Test
  public void getContentSaysChangedFilesAreInThePatchWhenSentInFull() {
    Configuration config = config(CodeContextSearchScope.CHANGED_FILES);
    when(config.getPatchFullFileMaxBytes()).thenReturn(64 * 1024);

    assertTrue(
        describe("config/getContentTool.json", config)
            .startsWith("Changed files up to 64 KB are already shown in full in the patch"));
  }

  private static String describe(String resource, Configuration config) {
    ToolSpecification scoped = LangChainClient.withSearchScope(load(resource), config);
    assertEquals(load(resource).parameters(), scoped.parameters());
    return scoped.description();
  }

  private static ToolSpecification load(String resource) {
    return new LangChainToolSpecificationFactory(resource).loadToolSpecification();
  }

  private static Configuration config(CodeContextSearchScope scope) {
    Configuration config = mock(Configuration.class);
    when(config.getCodeContextSearchScope()).thenReturn(scope);
    return config;
  }
}
