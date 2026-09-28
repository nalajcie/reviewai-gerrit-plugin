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

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.ConfigResource;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiUsageStore;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiBudgetGuard;
import com.googlesource.gerrit.plugins.reviewai.permissions.ConfiguredAiGroupMembership;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Config;
import org.kohsuke.args4j.Option;

/**
 * {@code GET /config/server/<plugin>~ai-usage?period=day|month}: the estimated AI cost of the
 * current UTC day or month, per project, with the configured budgets. For ReviewAI administrators
 * only.
 */
@Slf4j
public class AiUsage implements RestReadView<ConfigResource> {
  private final String pluginName;
  private final PluginConfigFactory configFactory;
  private final AiUsageStore usageStore;
  private final Provider<CurrentUser> currentUser;
  private final ConfiguredAiGroupMembership groupMembership;
  private final PermissionBackend permissionBackend;

  private String period = "month";

  @Option(name = "--period", metaVar = "PERIOD", usage = "day or month (default: month)")
  public void setPeriod(String period) {
    this.period = period;
  }

  @Inject
  AiUsage(
      @PluginName String pluginName,
      PluginConfigFactory configFactory,
      AiUsageStore usageStore,
      Provider<CurrentUser> currentUser,
      ConfiguredAiGroupMembership groupMembership,
      PermissionBackend permissionBackend) {
    this.pluginName = pluginName;
    this.configFactory = configFactory;
    this.usageStore = usageStore;
    this.currentUser = currentUser;
    this.groupMembership = groupMembership;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<Output> apply(ConfigResource resource) throws Exception {
    PluginConfig globalConfig = configFactory.getFromGerritConfig(pluginName);
    Configuration globalConfiguration = configuration(globalConfig, emptyPluginConfig());
    CurrentUser user = currentUser.get();
    if (!isAiAdministrator(globalConfiguration.getAiAdministratorsGroup(), user)) {
      throw new AuthException("ReviewAI administrator permission required");
    }
    AiUsageStore.Period selectedPeriod = parsePeriod(period);
    AiUsageStore.Usage usage = usageStore.usage(selectedPeriod);

    Output output = new Output();
    output.period = selectedPeriod.name().toLowerCase(Locale.ROOT);
    output.periodStart = usage.period();
    output.totalNanoUsd = usage.totalNanoUsd();
    output.totalUsd = AiBudgetGuard.toUsd(usage.totalNanoUsd());
    output.limits = new Limits();
    output.limits.dailyUsd = globalConfiguration.getAiBudgetDailyUsd();
    output.limits.monthlyUsd = globalConfiguration.getAiBudgetMonthlyUsd();
    output.limits.projectMonthlyUsd = globalConfiguration.getAiBudgetProjectMonthlyUsd();
    output.limits.manualOverrunFactor = AiBudgetGuard.MANUAL_OVERRUN_FACTOR;
    output.projects = new ArrayList<>();
    for (Map.Entry<String, Long> entry : usage.projectNanoUsd().entrySet()) {
      ProjectUsage projectUsage = new ProjectUsage();
      projectUsage.project = entry.getKey();
      projectUsage.nanoUsd = entry.getValue();
      projectUsage.usd = AiBudgetGuard.toUsd(entry.getValue());
      projectUsage.monthlyLimitUsd =
          projectConfig(entry.getKey())
              .map(projectConfig -> configuration(globalConfig, projectConfig))
              .orElse(globalConfiguration)
              .getAiBudgetProjectMonthlyUsd();
      output.projects.add(projectUsage);
    }
    return Response.ok(output);
  }

  private boolean isAiAdministrator(String groupName, CurrentUser user) {
    if (user == null || !user.isIdentifiedUser()) {
      return false;
    }
    Optional<Boolean> configuredMember = groupMembership.containsConfiguredGroup(groupName, user);
    if (configuredMember.isPresent()) {
      return configuredMember.get();
    }
    // As for administrator-only commands: without a usable aiAdministratorsGroup, Gerrit
    // administrators are ReviewAI administrators.
    try {
      return permissionBackend.user(user).test(GlobalPermission.ADMINISTRATE_SERVER);
    } catch (Exception e) {
      log.debug("Failed to inspect Gerrit administrative permission", e);
      return false;
    }
  }

  private static AiUsageStore.Period parsePeriod(String value) throws BadRequestException {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    try {
      return normalized.isEmpty()
          ? AiUsageStore.Period.MONTH
          : AiUsageStore.Period.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("period must be day or month", e);
    }
  }

  private Optional<PluginConfig> projectConfig(String project) {
    if (AiUsageStore.UNKNOWN_PROJECT.equals(project)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          configFactory.getFromProjectConfigWithInheritance(Project.nameKey(project), pluginName));
    } catch (Exception e) {
      log.debug("Cannot read ReviewAI configuration of project {}", project, e);
      return Optional.empty();
    }
  }

  private PluginConfig emptyPluginConfig() {
    return PluginConfig.createFromGerritConfig(pluginName, new Config());
  }

  private static Configuration configuration(PluginConfig global, PluginConfig project) {
    return new Configuration(null, null, global, project, "", null);
  }

  public static class Output {
    @SerializedName("period")
    public String period;

    @SerializedName("period_start")
    public String periodStart;

    @SerializedName("currency")
    public String currency = "USD";

    @SerializedName("total_usd")
    public double totalUsd;

    @SerializedName("total_nano_usd")
    public long totalNanoUsd;

    @SerializedName("limits")
    public Limits limits;

    @SerializedName("projects")
    public List<ProjectUsage> projects;
  }

  /** Configured budgets in USD; 0 means no limit. */
  public static class Limits {
    @SerializedName("daily_usd")
    public double dailyUsd;

    @SerializedName("monthly_usd")
    public double monthlyUsd;

    @SerializedName("project_monthly_usd")
    public double projectMonthlyUsd;

    @SerializedName("manual_overrun_factor")
    public double manualOverrunFactor;
  }

  public static class ProjectUsage {
    @SerializedName("project")
    public String project;

    @SerializedName("usd")
    public double usd;

    @SerializedName("nano_usd")
    public long nanoUsd;

    @SerializedName("monthly_limit_usd")
    public double monthlyLimitUsd;
  }
}
