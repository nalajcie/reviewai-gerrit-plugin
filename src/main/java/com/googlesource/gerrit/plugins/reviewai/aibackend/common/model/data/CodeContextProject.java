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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

/**
 * A read-only repository exposed to the on-demand code context tools, resolved to a commit.
 *
 * @param project the Gerrit project name
 * @param ref the configured ref
 * @param commitId the commit the ref pointed to when the review started
 */
public record CodeContextProject(String project, String ref, String commitId) {
  public static final String PATH_PREFIX = "reviewai-context/";

  /** Returns the tool path prefix of the project, for example {@code reviewai-context/libs/}. */
  public String prefix() {
    return PATH_PREFIX + project + "/";
  }
}
