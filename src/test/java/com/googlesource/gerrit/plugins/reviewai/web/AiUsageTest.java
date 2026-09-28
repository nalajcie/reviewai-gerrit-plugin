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

package com.googlesource.gerrit.plugins.reviewai.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.data.AiUsageStore;
import com.googlesource.gerrit.plugins.reviewai.permissions.ConfiguredAiGroupMembership;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;

public class AiUsageTest extends TestBase {
  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";
  private static final String ADMIN_GROUP = "AI Admins";
  private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

  private PluginConfigFactory configFactory;
  private ConfiguredAiGroupMembership groupMembership;
  private PermissionBackend permissionBackend;
  private PermissionBackend.WithUser withUser;
  private CurrentUser user;
  private AiUsageStore store;

  @Before
  public void setUp() throws Exception {
    Config global = new Config();
    global.setString("plugin", PLUGIN_NAME, "aiAdministratorsGroup", ADMIN_GROUP);
    global.setString("plugin", PLUGIN_NAME, "aiBudgetDailyUsd", "2");
    global.setString("plugin", PLUGIN_NAME, "aiBudgetMonthlyUsd", "40");
    global.setString("plugin", PLUGIN_NAME, "aiBudgetProjectMonthlyUsd", "10");
    Config coreProject = new Config();
    coreProject.setString("plugin", PLUGIN_NAME, "aiBudgetProjectMonthlyUsd", "25");
    configFactory = mock(PluginConfigFactory.class);
    when(configFactory.getFromGerritConfig(PLUGIN_NAME))
        .thenReturn(PluginConfig.createFromGerritConfig(PLUGIN_NAME, global));
    when(configFactory.getFromProjectConfigWithInheritance(Project.nameKey("core"), PLUGIN_NAME))
        .thenReturn(PluginConfig.createFromGerritConfig(PLUGIN_NAME, coreProject));
    when(configFactory.getFromProjectConfigWithInheritance(Project.nameKey("docs"), PLUGIN_NAME))
        .thenReturn(PluginConfig.createFromGerritConfig(PLUGIN_NAME, new Config()));

    user = mock(CurrentUser.class);
    when(user.isIdentifiedUser()).thenReturn(true);
    groupMembership = mock(ConfiguredAiGroupMembership.class);
    permissionBackend = mock(PermissionBackend.class);
    withUser = mock(PermissionBackend.WithUser.class);
    when(permissionBackend.user(any())).thenReturn(withUser);

    store = new AiUsageStore(getTestReviewAiDb(), Clock.fixed(NOW, ZoneOffset.UTC));
    store.record(Instant.parse("2026-09-02T08:00:00Z"), "core", 3_000_000_000L);
    store.record(NOW, "core", 500_000_000L);
    store.record(NOW, "docs", 250_000_000L);
    store.record(Instant.parse("2026-08-31T23:00:00Z"), "core", 9_000_000_000L);
  }

  @Test
  public void refusesUsersOutsideTheAdministratorsGroup() {
    when(groupMembership.containsConfiguredGroup(ADMIN_GROUP, user)).thenReturn(Optional.of(false));

    assertThrows(AuthException.class, () -> view("month").apply(null));
  }

  @Test
  public void refusesAnonymousUsers() {
    when(user.isIdentifiedUser()).thenReturn(false);
    when(groupMembership.containsConfiguredGroup(any(), any())).thenReturn(Optional.of(true));

    assertThrows(AuthException.class, () -> view("month").apply(null));
  }

  @Test
  public void fallsBackToGerritAdministratorsWithoutUsableGroup() throws Exception {
    when(groupMembership.containsConfiguredGroup(ADMIN_GROUP, user)).thenReturn(Optional.empty());
    when(withUser.test(GlobalPermission.ADMINISTRATE_SERVER)).thenReturn(false);
    assertThrows(AuthException.class, () -> view("month").apply(null));

    when(withUser.test(GlobalPermission.ADMINISTRATE_SERVER)).thenReturn(true);
    assertEquals("month", view("month").apply(null).value().period);
  }

  @Test
  public void rejectsUnknownPeriod() {
    when(groupMembership.containsConfiguredGroup(ADMIN_GROUP, user)).thenReturn(Optional.of(true));

    assertThrows(BadRequestException.class, () -> view("year").apply(null));
  }

  @Test
  public void reportsMonthlyUsagePerProjectWithLimits() throws Exception {
    when(groupMembership.containsConfiguredGroup(ADMIN_GROUP, user)).thenReturn(Optional.of(true));

    JsonObject json = toJson(view("month").apply(null).value());

    assertEquals("month", json.get("period").getAsString());
    assertEquals("2026-09", json.get("period_start").getAsString());
    assertEquals("USD", json.get("currency").getAsString());
    assertEquals(3.75, json.get("total_usd").getAsDouble(), 1e-9);
    assertEquals(3_750_000_000L, json.get("total_nano_usd").getAsLong());
    JsonObject limits = json.getAsJsonObject("limits");
    assertEquals(2.0, limits.get("daily_usd").getAsDouble(), 0.0);
    assertEquals(40.0, limits.get("monthly_usd").getAsDouble(), 0.0);
    assertEquals(10.0, limits.get("project_monthly_usd").getAsDouble(), 0.0);
    assertEquals(1.2, limits.get("manual_overrun_factor").getAsDouble(), 0.0);
    JsonArray projects = json.getAsJsonArray("projects");
    assertEquals(2, projects.size());
    JsonObject core = projects.get(0).getAsJsonObject();
    assertEquals("core", core.get("project").getAsString());
    assertEquals(3.5, core.get("usd").getAsDouble(), 1e-9);
    assertEquals(3_500_000_000L, core.get("nano_usd").getAsLong());
    assertEquals(25.0, core.get("monthly_limit_usd").getAsDouble(), 0.0);
    JsonObject docs = projects.get(1).getAsJsonObject();
    assertEquals("docs", docs.get("project").getAsString());
    assertEquals(10.0, docs.get("monthly_limit_usd").getAsDouble(), 0.0);
  }

  @Test
  public void reportsDailyUsage() throws Exception {
    when(groupMembership.containsConfiguredGroup(ADMIN_GROUP, user)).thenReturn(Optional.of(true));

    AiUsage.Output output = view("day").apply(null).value();

    assertEquals("day", output.period);
    assertEquals("2026-09-28", output.periodStart);
    assertEquals(750_000_000L, output.totalNanoUsd);
    assertEquals(2, output.projects.size());
    assertFalse(output.projects.stream().anyMatch(p -> p.nanoUsd == 3_000_000_000L));
    assertTrue(output.projects.stream().anyMatch(p -> "core".equals(p.project)));
  }

  private AiUsage view(String period) {
    AiUsage view =
        new AiUsage(
            PLUGIN_NAME, configFactory, store, () -> user, groupMembership, permissionBackend);
    view.setPeriod(period);
    return view;
  }

  private static JsonObject toJson(AiUsage.Output output) {
    return OutputFormat.JSON.newGson().toJsonTree(output).getAsJsonObject();
  }
}
