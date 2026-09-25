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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

/**
 * A change of a multi-project review group.
 *
 * @param change the member change at its current patch set
 * @param config the configuration of the member's project
 * @param reviewable whether ReviewAI reviews the member's project; members that are not reviewable
 *     are sent to the AI as read-only context and receive no comments or votes
 */
public record ReviewGroupMember(GerritChange change, Configuration config, boolean reviewable) {}
