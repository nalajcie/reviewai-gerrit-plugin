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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;
import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.cutString;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CodeContextProject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnDemandCodeContextTools extends ClientBase {
  public static final String TREE = "tree";
  public static final String GET_CONTENT = "get_content";
  public static final String GREP = "grep";
  public static final Set<String> FUNCTION_NAMES = Set.of(TREE, GET_CONTENT, GREP);

  private static final String CONTEXT_NOT_PROVIDED = "CONTEXT NOT PROVIDED";
  private static final String PREEXISTING_CONTEXT_MARKER =
      "NOTE: This file is pre-existing repository context and is NOT part of the current change.\n\n";
  private static final String CODE_CONTEXT_PROJECT_MARKER =
      "NOTE: This file is from the read-only code context project %s at %s and is NOT part of the"
          + " current change.\n\n";
  private static final Pattern COMMIT_MESSAGE_PATH_PATTERN =
      Pattern.compile("^(?:reviewai-topic-change-.*)?/?COMMIT_MSG$");
  private static final int LOG_MAX_CONTENT_SIZE = 256;

  private final GerritChange change;
  private final GitRepoFiles gitRepoFiles;
  private final TreeOutputCompressor treeOutputCompressor;
  private final Map<String, GerritChange> reviewGroupChangesByPrefix;
  private final List<CodeContextProject> codeContextProjects;
  private final Map<GerritChange, Optional<Set<String>>> changedFilesByChange = new HashMap<>();

  public OnDemandCodeContextTools(
      Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    this(config, change, gitRepoFiles, Map.of());
  }

  /**
   * Creates the tools for a review whose patch merges several review group members. Paths that
   * start with a member prefix, such as {@code reviewai-topic-change-2/core-libs/}, resolve to that
   * member's repository at its current patch set; other paths resolve to {@code change}.
   */
  public OnDemandCodeContextTools(
      Configuration config,
      GerritChange change,
      GitRepoFiles gitRepoFiles,
      Map<String, GerritChange> reviewGroupChangesByPrefix) {
    this(config, change, gitRepoFiles, reviewGroupChangesByPrefix, List.of());
  }

  /**
   * Creates the tools with read-only code context projects, addressed with paths that start with
   * {@code reviewai-context/<project>/}.
   */
  public OnDemandCodeContextTools(
      Configuration config,
      GerritChange change,
      GitRepoFiles gitRepoFiles,
      Map<String, GerritChange> reviewGroupChangesByPrefix,
      List<CodeContextProject> codeContextProjects) {
    super(config);
    this.codeContextProjects = codeContextProjects == null ? List.of() : codeContextProjects;
    this.change = change;
    this.gitRepoFiles = gitRepoFiles;
    this.treeOutputCompressor = new TreeOutputCompressor();
    this.reviewGroupChangesByPrefix =
        reviewGroupChangesByPrefix == null ? Map.of() : reviewGroupChangesByPrefix;
  }

  private Set<String> changedFiles(GerritChange targetChange) {
    return changedFilesByChange
        .computeIfAbsent(
            targetChange,
            ignored -> {
              try {
                return Optional.ofNullable(gitRepoFiles.getPatchSetChangedFiles(targetChange));
              } catch (Exception e) {
                log.warn(
                    "Could not resolve changed files for change {}; on-demand tools will not be"
                        + " scoped to the change",
                    targetChange.getFullChangeId(),
                    e);
                return Optional.empty();
              }
            })
        .orElse(null);
  }

  public String execute(String toolName, String arguments) {
    if (!FUNCTION_NAMES.contains(toolName)) {
      log.debug("Ignoring unsupported on-demand code context tool: {}", toolName);
      return "";
    }

    log.debug(
        "On-demand code context request for {}: tool={}, arguments={}",
        getChangeId(),
        toolName,
        arguments);
    String response;
    try {
      JsonObject argumentObject = parseArguments(arguments);
      response =
          switch (toolName) {
            case TREE -> tree(getString(argumentObject, "subdir"));
            case GET_CONTENT -> getContent(getString(argumentObject, "file_path"));
            case GREP ->
                grep(getString(argumentObject, "string"), getString(argumentObject, "path"));
            default -> "";
          };
    } catch (FileNotFoundException e) {
      log.debug("File not found while executing on-demand code context tool {}", toolName, e);
      response = CONTEXT_NOT_PROVIDED;
    } catch (Exception e) {
      log.warn("Error executing on-demand code context tool {}", toolName, e);
      response = CONTEXT_NOT_PROVIDED;
    }
    log.debug(
        "On-demand code context response for {}: tool={}, response={}",
        getChangeId(),
        toolName,
        cutString(response, LOG_MAX_CONTENT_SIZE));
    return response;
  }

  private String tree(String subdir) {
    Optional<ContextPath> contextPath = resolveContextPath(subdir);
    if (contextPath.isPresent()) {
      return contextTree(contextPath.get());
    }
    boolean root = subdir == null || subdir.isBlank();
    String output;
    if (root && !reviewGroupChangesByPrefix.isEmpty()) {
      List<String> paths = new ArrayList<>();
      paths.addAll(treePaths(resolve(subdir)));
      for (Map.Entry<String, GerritChange> member : reviewGroupChangesByPrefix.entrySet()) {
        if (member.getValue() != change) {
          paths.addAll(treePaths(new ResolvedPath(member.getValue(), member.getKey(), "")));
        }
      }
      output = paths.isEmpty() ? "" : treeOutputCompressor.format(paths, subdir);
    } else {
      ResolvedPath resolvedPath = resolve(subdir);
      List<String> paths = treePaths(resolvedPath);
      output =
          paths.isEmpty()
              ? ""
              : treeOutputCompressor.format(
                  paths,
                  resolvedPath.prefix().isEmpty()
                      ? subdir
                      : resolvedPath.prefix() + resolvedPath.path());
    }
    if (root && !codeContextProjects.isEmpty()) {
      List<String> lines = new ArrayList<>();
      if (!output.isEmpty()) {
        lines.add(output);
      }
      codeContextProjects.forEach(project -> lines.add(project.prefix() + "..."));
      output = String.join("\n", lines);
    }
    return output.isEmpty() ? CONTEXT_NOT_PROVIDED : output;
  }

  private String contextTree(ContextPath contextPath) {
    CodeContextProject project = contextPath.project();
    try {
      List<String> paths =
          gitRepoFiles
              .getRepositoryFileTree(project.project(), project.commitId(), contextPath.path())
              .stream()
              .map(path -> project.prefix() + path)
              .toList();
      if (paths.isEmpty()) {
        return CONTEXT_NOT_PROVIDED;
      }
      return treeOutputCompressor.format(paths, project.prefix() + contextPath.path());
    } catch (IOException e) {
      log.warn("Could not list code context project {}", project.project(), e);
      return CONTEXT_NOT_PROVIDED;
    }
  }

  private List<String> treePaths(ResolvedPath resolvedPath) {
    List<String> paths =
        gitRepoFiles.getPatchSetFileTree(config, resolvedPath.change(), resolvedPath.path());
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    Set<String> changed = changedFiles(resolvedPath.change());
    if (changed != null) {
      paths = paths.stream().filter(changed::contains).toList();
    }
    return paths.stream().map(path -> resolvedPath.prefix() + path).toList();
  }

  private String getContent(String filePath) throws FileNotFoundException {
    if (filePath == null || filePath.isBlank() || isCommitMessagePath(filePath)) {
      return CONTEXT_NOT_PROVIDED;
    }
    Optional<ContextPath> contextPath = resolveContextPath(filePath);
    if (contextPath.isPresent()) {
      CodeContextProject project = contextPath.get().project();
      if (contextPath.get().path().isEmpty()) {
        return CONTEXT_NOT_PROVIDED;
      }
      return String.format(CODE_CONTEXT_PROJECT_MARKER, project.project(), project.ref())
          + gitRepoFiles.getRepositoryFileContent(
              project.project(), project.commitId(), contextPath.get().path());
    }
    ResolvedPath resolvedPath = resolve(filePath);
    if (resolvedPath.path().isBlank()) {
      return CONTEXT_NOT_PROVIDED;
    }
    String content =
        gitRepoFiles.getPatchSetFileContent(resolvedPath.change(), resolvedPath.path());
    Set<String> changed = changedFiles(resolvedPath.change());
    if (changed != null && !changed.contains(resolvedPath.path())) {
      return PREEXISTING_CONTEXT_MARKER + content;
    }
    return content;
  }

  private static boolean isCommitMessagePath(String filePath) {
    return COMMIT_MESSAGE_PATH_PATTERN.matcher(filePath).matches();
  }

  private String grep(String string, String path) throws IOException {
    if (string == null || string.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    Optional<ContextPath> contextPath = resolveContextPath(path);
    List<String> matches = new ArrayList<>();
    if (contextPath.isPresent()) {
      CodeContextProject project = contextPath.get().project();
      gitRepoFiles
          .grepRepository(project.project(), project.commitId(), contextPath.get().path(), string)
          .forEach(match -> matches.add(project.prefix() + match));
    } else {
      matches.addAll(grep(resolve(null), string));
      for (Map.Entry<String, GerritChange> member : reviewGroupChangesByPrefix.entrySet()) {
        if (member.getValue() != change) {
          matches.addAll(grep(new ResolvedPath(member.getValue(), member.getKey(), ""), string));
        }
      }
      String pathFilter = path == null ? "" : path.replaceAll("^/+", "");
      if (!pathFilter.isEmpty()) {
        matches.removeIf(match -> !match.startsWith(pathFilter));
      }
    }
    if (matches.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    return String.join("\n", matches);
  }

  private List<String> grep(ResolvedPath resolvedPath, String string) {
    Set<String> changed = changedFiles(resolvedPath.change());
    List<String> matches =
        gitRepoFiles.grepPatchSet(config, resolvedPath.change(), string, changed);
    if (matches == null) {
      return List.of();
    }
    return matches.stream().map(match -> resolvedPath.prefix() + match).toList();
  }

  /**
   * Resolves a tool path to the review group member it refers to. Unprefixed paths refer to the
   * change under review; in a merged review group they are reported back with its prefix.
   */
  private ResolvedPath resolve(String path) {
    String normalizedPath = path == null ? "" : path.replaceAll("^/+", "");
    String ownPrefix = "";
    for (Map.Entry<String, GerritChange> member : reviewGroupChangesByPrefix.entrySet()) {
      String prefix = member.getKey();
      if (normalizedPath.startsWith(prefix)
          || normalizedPath.equals(prefix.substring(0, prefix.length() - 1))) {
        return new ResolvedPath(
            member.getValue(),
            prefix,
            normalizedPath.length() > prefix.length()
                ? normalizedPath.substring(prefix.length())
                : "");
      }
      if (member.getValue() == change) {
        ownPrefix = prefix;
      }
    }
    return new ResolvedPath(change, ownPrefix, path);
  }

  private record ResolvedPath(GerritChange change, String prefix, String path) {}

  /** Resolves a tool path that starts with {@code reviewai-context/<project>/}. */
  private Optional<ContextPath> resolveContextPath(String path) {
    if (path == null || codeContextProjects.isEmpty()) {
      return Optional.empty();
    }
    String normalizedPath = path.replaceAll("^/+", "");
    return codeContextProjects.stream()
        .filter(
            project ->
                normalizedPath.startsWith(project.prefix())
                    || normalizedPath.equals(
                        project.prefix().substring(0, project.prefix().length() - 1)))
        // Prefer the longest prefix when a project name is a path prefix of another one.
        .max(Comparator.comparingInt(project -> project.prefix().length()))
        .map(
            project ->
                new ContextPath(
                    project,
                    normalizedPath.length() > project.prefix().length()
                        ? normalizedPath.substring(project.prefix().length())
                        : ""));
  }

  private record ContextPath(CodeContextProject project, String path) {}

  private static JsonObject parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return new JsonObject();
    }
    return JsonParser.parseString(arguments).getAsJsonObject();
  }

  private String getChangeId() {
    return change == null ? "<unknown-change>" : change.getFullChangeId();
  }
}
