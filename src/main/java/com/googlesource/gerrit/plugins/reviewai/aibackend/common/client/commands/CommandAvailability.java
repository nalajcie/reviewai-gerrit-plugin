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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.BaseOptionSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiCommandAccessPolicy;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiRole;
import com.googlesource.gerrit.plugins.reviewai.utils.PluginBuild;
import java.util.Map;
import java.util.Set;

/** Which commands a user can run in this build; /help lists only those. */
final class CommandAvailability {
  /** Commands of the development build only. */
  static final Set<CommandSet> DEV_BUILD_COMMANDS =
      Set.of(CommandSet.CONFIGURE, CommandSet.DIRECTIVES, CommandSet.SHOW);

  private final AiRole role;
  private final boolean devBuild;

  CommandAvailability(AiRole role, boolean devBuild) {
    this.role = role == null ? AiRole.USER : role;
    this.devBuild = devBuild;
  }

  static CommandAvailability of(AiRole role) {
    return new CommandAvailability(role, PluginBuild.isDevBuild());
  }

  boolean isDevBuildRequired(CommandSet command) {
    return !devBuild && DEV_BUILD_COMMANDS.contains(command);
  }

  boolean isAvailable(CommandSet command) {
    return !isDevBuildRequired(command) && deniedRole(command).isEmpty();
  }

  /** {@code /review --debug}: development build and the administrator role. */
  boolean isDebugAvailable() {
    return devBuild
        && AiCommandAccessPolicy.deniedRequiredRole(
                role, CommandSet.REVIEW, Map.of(BaseOptionSet.DEBUG, ""))
            .isEmpty();
  }

  java.util.Optional<AiRole> deniedRole(CommandSet command) {
    return AiCommandAccessPolicy.deniedRequiredRole(role, command, Map.of());
  }
}
