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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git;

import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.isFileExtensionEnabled;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.git.FileEntry;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

@Slf4j
public class GitRepoFiles {
  /** Maximum number of paths listed from a code context project. */
  public static final int CONTEXT_MAX_TREE_ENTRIES = 2000;

  /** Maximum size of a code context project file that is read or searched. */
  public static final int CONTEXT_MAX_FILE_BYTES = 512 * 1024;

  /** Maximum number of code context project files searched by one grep. */
  public static final int CONTEXT_MAX_GREP_FILES = 5000;

  /** Maximum number of matches returned by one grep in a code context project. */
  public static final int CONTEXT_MAX_GREP_MATCHES = 200;

  private static final String LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/";

  private final GitRepositoryManager repositoryManager;

  @Inject
  public GitRepoFiles(GitRepositoryManager repositoryManager) {
    this.repositoryManager = repositoryManager;
  }

  @VisibleForTesting
  GitRepoFiles() {
    this.repositoryManager = null;
  }

  public List<String> getGitRepoFilesAsJson(Configuration config, GerritChange change) {
    log.debug("Getting Repository files as JSON");
    GitFileChunkBuilder chunkBuilder = new GitFileChunkBuilder(config);
    FileSelection selection = FileSelection.from(config);
    try {
      List<Map<String, String>> chunkedFileContent =
          withRepositoryTree(
              change,
              (repository, tree) ->
                  listFilesWithContent(repository, tree, chunkBuilder, selection));
      return chunkedFileContent.stream()
          .map(chunk -> getGson().toJson(chunk))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve files from change branch: ", e);
    }
  }

  public String getFileContent(GerritChange change, String path) throws FileNotFoundException {
    return getFileContentAtRevision(change, path, this::getBranchRevTree);
  }

  public String getPatchSetFileContent(GerritChange change, String path)
      throws FileNotFoundException {
    return getFileContentAtRevision(change, path, this::getPatchSetRevTree);
  }

  private String getFileContentAtRevision(
      GerritChange change, String path, RevTreeResolver treeResolver) throws FileNotFoundException {
    try {
      String content =
          withRepositoryTreeReader(
              change,
              treeResolver,
              (repository, tree, reader) -> readFileContent(reader, tree, path));
      if (content != null) {
        return content;
      } else {
        throw new FileNotFoundException("Error retrieving file at " + path);
      }
    } catch (IOException e) {
      throw new FileNotFoundException("File not found: " + path);
    }
  }

  public List<String> getPatchSetFileTree(
      Configuration config, GerritChange change, String subdir) {
    log.debug("Getting repository file tree from subdir: {}", subdir);
    FileSelection selection = FileSelection.from(config);
    String normalizedSubdir = normalizePath(subdir);
    try {
      return withRepositoryTree(
          change,
          this::getPatchSetRevTree,
          (repository, tree) -> listMatchingPaths(repository, tree, normalizedSubdir, selection));
    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve file tree from " + normalizedSubdir, e);
    }
  }

  public List<String> grepPatchSet(
      Configuration config, GerritChange change, String searchString, Set<String> includedPaths) {
    log.debug("Searching repository for string: {}", searchString);
    FileSelection selection = FileSelection.from(config);
    if (searchString == null || searchString.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      return withRepositoryTreeReader(
          change,
          this::getPatchSetRevTree,
          (repository, tree, reader) ->
              grepTree(repository, tree, reader, searchString, includedPaths, selection));
    } catch (IOException e) {
      throw new RuntimeException("Failed to search repository", e);
    }
  }

  /**
   * Lists the files of a project at a commit, below {@code subdir} when given, bounded by {@link
   * #CONTEXT_MAX_TREE_ENTRIES}.
   */
  public List<String> getRepositoryFileTree(String project, String commitId, String subdir)
      throws IOException {
    String normalizedSubdir = normalizePath(subdir);
    return withCommitTree(
        project,
        commitId,
        (repository, tree, reader) -> {
          List<String> paths = new ArrayList<>();
          try (TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree, normalizedSubdir)) {
            while (treeWalk.next() && paths.size() < CONTEXT_MAX_TREE_ENTRIES) {
              paths.add(treeWalk.getPathString());
            }
          }
          return paths;
        });
  }

  /**
   * Returns a text file of a project at a commit. Binary files, Git LFS pointers, submodules and
   * files larger than {@link #CONTEXT_MAX_FILE_BYTES} are reported as not found.
   */
  public String getRepositoryFileContent(String project, String commitId, String path)
      throws FileNotFoundException {
    String normalizedPath = normalizePath(path);
    try {
      String content =
          withCommitTree(
              project,
              commitId,
              (repository, tree, reader) -> {
                try (TreeWalk treeWalk = TreeWalk.forPath(reader, normalizedPath, tree)) {
                  return treeWalk == null ? null : readContextFile(reader, treeWalk);
                }
              });
      if (content == null) {
        throw new FileNotFoundException("File not available: " + path);
      }
      return content;
    } catch (IOException e) {
      throw new FileNotFoundException("File not found: " + path);
    }
  }

  /** Maximum number of matches the repository-wide patch set grep returns. */
  public static final int REPOSITORY_GREP_MAX_MATCHES = 30;

  /** Maximum number of matches per file in the repository-wide patch set grep. */
  public static final int REPOSITORY_GREP_MAX_MATCHES_PER_FILE = 3;

  /** Matching lines longer than this are cut in the repository-wide patch set grep. */
  public static final int REPOSITORY_GREP_MAX_LINE_LENGTH = 160;

  /** Maximum part of a file that the get_content tool returns. */
  public static final int TOOL_MAX_FILE_BYTES = 64 * 1024;

  /** Matches of a bounded search, and how many more matches there were. */
  public record BoundedGrep(List<String> matches, int omittedMatches, boolean fileLimitReached) {}

  /**
   * Searches the text files of the repository at the change's patch set, below {@code subdir} when
   * given, for a literal string. Unlike {@link #grepPatchSet} it is not limited to the changed
   * files, so the output is bounded: {@link #REPOSITORY_GREP_MAX_MATCHES} matches, {@link
   * #REPOSITORY_GREP_MAX_MATCHES_PER_FILE} per file, lines cut at {@link
   * #REPOSITORY_GREP_MAX_LINE_LENGTH}, at most {@link #CONTEXT_MAX_GREP_FILES} files searched.
   * Binary, LFS and files above {@link #CONTEXT_MAX_FILE_BYTES} are skipped, as are file types
   * the configuration excludes.
   */
  public BoundedGrep grepPatchSetRepository(
      Configuration config, GerritChange change, String subdir, String searchString)
      throws IOException {
    if (searchString == null || searchString.isEmpty()) {
      return new BoundedGrep(List.of(), 0, false);
    }
    FileSelection selection = FileSelection.from(config);
    String normalizedSubdir = normalizePath(subdir);
    return withRepositoryTreeReader(
        change,
        this::getPatchSetRevTree,
        (repository, tree, reader) -> {
          List<String> matches = new ArrayList<>();
          int omitted = 0;
          int searchedFiles = 0;
          try (TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree, normalizedSubdir)) {
            while (treeWalk.next()) {
              String path = treeWalk.getPathString();
              if (!selection.accepts(path)) {
                continue;
              }
              if (searchedFiles >= CONTEXT_MAX_GREP_FILES) {
                return new BoundedGrep(matches, omitted, true);
              }
              String content = readContextFile(reader, treeWalk);
              if (content == null) {
                continue;
              }
              searchedFiles++;
              List<String> fileMatches = new ArrayList<>();
              addGrepMatches(fileMatches, path, content, searchString);
              for (int i = 0; i < fileMatches.size(); i++) {
                if (i < REPOSITORY_GREP_MAX_MATCHES_PER_FILE
                    && matches.size() < REPOSITORY_GREP_MAX_MATCHES) {
                  matches.add(cutLine(fileMatches.get(i)));
                } else {
                  omitted++;
                }
              }
            }
          }
          return new BoundedGrep(matches, omitted, false);
        });
  }

  private static String cutLine(String line) {
    return line.length() <= REPOSITORY_GREP_MAX_LINE_LENGTH
        ? line
        : line.substring(0, REPOSITORY_GREP_MAX_LINE_LENGTH) + " [...]";
  }

  /** A file of the patch set as the get_content tool returns it. */
  public record ToolFileContent(String text, long sizeBytes, boolean binary, boolean truncated) {}

  /**
   * Reads a file of the patch set for the get_content tool: binary and LFS files are reported
   * without their content, text beyond {@link #TOOL_MAX_FILE_BYTES} is cut. A binary loader file
   * (about 500 KB) once put 155k tokens into every later request of a review.
   */
  public ToolFileContent getPatchSetFileForTool(GerritChange change, String path)
      throws FileNotFoundException {
    try {
      ToolFileContent content =
          withRepositoryTreeReader(
              change,
              this::getPatchSetRevTree,
              (repository, tree, reader) -> {
                try (TreeWalk treeWalk = TreeWalk.forPath(reader, path, tree)) {
                  if (treeWalk == null) {
                    return null;
                  }
                  ObjectLoader loader = reader.open(treeWalk.getObjectId(0));
                  long size = loader.getSize();
                  byte[] bytes =
                      size <= TOOL_MAX_FILE_BYTES
                          ? loader.getBytes()
                          : readPrefix(loader, TOOL_MAX_FILE_BYTES);
                  if (RawText.isBinary(bytes)) {
                    return new ToolFileContent("", size, true, false);
                  }
                  String text = new String(bytes, StandardCharsets.UTF_8);
                  if (text.startsWith(LFS_POINTER_PREFIX)) {
                    return new ToolFileContent("", size, true, false);
                  }
                  return new ToolFileContent(text, size, false, size > TOOL_MAX_FILE_BYTES);
                }
              });
      if (content == null) {
        throw new FileNotFoundException("Error retrieving file at " + path);
      }
      return content;
    } catch (IOException e) {
      throw new FileNotFoundException("File not found: " + path);
    }
  }

  private static byte[] readPrefix(ObjectLoader loader, int maxBytes) throws IOException {
    try (java.io.InputStream in = loader.openStream()) {
      return in.readNBytes(maxBytes);
    }
  }

  /**
   * Searches the text files of a project at a commit, below {@code subdir} when given, for a
   * literal string. The search is bounded by {@link #CONTEXT_MAX_GREP_FILES} and {@link
   * #CONTEXT_MAX_GREP_MATCHES}, and skips the files that {@link #getRepositoryFileContent} rejects.
   */
  public List<String> grepRepository(
      String project, String commitId, String subdir, String searchString) throws IOException {
    if (searchString == null || searchString.isEmpty()) {
      return Collections.emptyList();
    }
    String normalizedSubdir = normalizePath(subdir);
    return withCommitTree(
        project,
        commitId,
        (repository, tree, reader) -> {
          List<String> matches = new ArrayList<>();
          int searchedFiles = 0;
          try (TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree, normalizedSubdir)) {
            while (treeWalk.next()
                && searchedFiles < CONTEXT_MAX_GREP_FILES
                && matches.size() < CONTEXT_MAX_GREP_MATCHES) {
              String content = readContextFile(reader, treeWalk);
              if (content == null) {
                continue;
              }
              searchedFiles++;
              addGrepMatches(matches, treeWalk.getPathString(), content, searchString);
            }
          }
          return matches.size() > CONTEXT_MAX_GREP_MATCHES
              ? new ArrayList<>(matches.subList(0, CONTEXT_MAX_GREP_MATCHES))
              : matches;
        });
  }

  private <T> T withCommitTree(
      String project, String commitId, RepositoryTreeReaderCallback<T> callback)
      throws IOException {
    if (repositoryManager == null) {
      throw new IOException("GitRepositoryManager is not available");
    }
    try (Repository repository = repositoryManager.openRepository(Project.nameKey(project));
        RevWalk revWalk = new RevWalk(repository);
        ObjectReader reader = repository.newObjectReader()) {
      RevTree tree = revWalk.parseCommit(ObjectId.fromString(commitId)).getTree();
      return callback.execute(repository, tree, reader);
    }
  }

  private TreeWalk newRecursiveTreeWalk(Repository repository, RevTree tree, String subdir)
      throws IOException {
    TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree);
    if (!subdir.isEmpty()) {
      treeWalk.setFilter(PathFilter.create(subdir));
    }
    return treeWalk;
  }

  private static String readContextFile(ObjectReader reader, TreeWalk treeWalk) throws IOException {
    FileMode mode = treeWalk.getFileMode(0);
    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
      return null;
    }
    ObjectLoader loader = reader.open(treeWalk.getObjectId(0));
    if (loader.getSize() > CONTEXT_MAX_FILE_BYTES) {
      return null;
    }
    byte[] bytes = loader.getBytes();
    if (RawText.isBinary(bytes)) {
      return null;
    }
    String content = new String(bytes, StandardCharsets.UTF_8);
    return content.startsWith(LFS_POINTER_PREFIX) ? null : content;
  }

  private List<Map<String, String>> listFilesWithContent(
      Repository repository,
      RevTree tree,
      GitFileChunkBuilder chunkBuilder,
      FileSelection selection)
      throws IOException {
    Map<String, List<FileEntry>> dirFilesMap =
        getDirFilesMap(repository, tree, TreeFilter.ANY_DIFF, selection);
    for (Map.Entry<String, List<FileEntry>> entry : dirFilesMap.entrySet()) {
      String dirPath = entry.getKey();
      log.debug("File from dirFilesMap processed: {}", dirPath);
      List<FileEntry> fileEntries = entry.getValue();
      chunkBuilder.addFiles(fileEntries);
    }

    return chunkBuilder.getChunks();
  }

  private List<String> listMatchingPaths(
      Repository repository, RevTree tree, String normalizedSubdir, FileSelection selection)
      throws IOException {
    return collectMatchingFiles(
        repository,
        tree,
        path -> isUnderSubdir(path, normalizedSubdir),
        (paths, path, treeWalk) -> paths.add(path),
        selection);
  }

  private List<String> grepTree(
      Repository repository,
      RevTree tree,
      ObjectReader reader,
      String searchString,
      Set<String> includedPaths,
      FileSelection selection)
      throws IOException {
    return collectMatchingFiles(
        repository,
        tree,
        path -> includedPaths == null || includedPaths.contains(path),
        (matches, path, treeWalk) -> {
          String content = readFile(reader, treeWalk).text();
          addGrepMatches(matches, path, content, searchString);
        },
        selection);
  }

  private List<String> collectMatchingFiles(
      Repository repository,
      RevTree tree,
      PathMatcher pathMatcher,
      MatchingFileCollector collector,
      FileSelection selection)
      throws IOException {
    List<String> results = new ArrayList<>();
    try (TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree)) {
      while (treeWalk.next()) {
        String path = treeWalk.getPathString();
        if (!pathMatcher.matches(path)) continue;
        if (!selection.accepts(path)) continue;
        collector.collect(results, path, treeWalk);
      }
    }
    return results;
  }

  private Map<String, List<FileEntry>> getDirFilesMap(
      Repository repository, RevTree tree, TreeFilter filter, FileSelection selection)
      throws IOException {
    Map<String, List<FileEntry>> dirFilesMap = new LinkedHashMap<>();

    try (ObjectReader reader = repository.newObjectReader()) {
      try (TreeWalk treeWalk = newRecursiveTreeWalk(repository, tree)) {
        treeWalk.setFilter(filter);

        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!selection.accepts(path)) continue;
          int lastSlashIndex = path.lastIndexOf('/');
          String dirPath = (lastSlashIndex != -1) ? path.substring(0, lastSlashIndex) : "";
          FileContent content = readFile(reader, treeWalk);

          dirFilesMap
              .computeIfAbsent(dirPath, k -> new ArrayList<>())
              .add(new FileEntry(path, content.text(), content.size()));
          log.debug("Repo File loaded: {}", path);
        }
      }
    }
    return dirFilesMap;
  }

  private <T> T withRepositoryTree(GerritChange change, RepositoryTreeCallback<T> callback)
      throws IOException {
    return withRepositoryTree(change, this::getBranchRevTree, callback);
  }

  private <T> T withRepositoryTree(
      GerritChange change, RevTreeResolver treeResolver, RepositoryTreeCallback<T> callback)
      throws IOException {
    try (Repository repository = openRepository(change)) {
      return callback.execute(repository, treeResolver.resolve(repository, change));
    }
  }

  private <T> T withRepositoryTreeReader(
      GerritChange change, RevTreeResolver treeResolver, RepositoryTreeReaderCallback<T> callback)
      throws IOException {
    return withRepositoryTree(
        change,
        treeResolver,
        (repository, tree) -> {
          try (ObjectReader reader = repository.newObjectReader()) {
            return callback.execute(repository, tree, reader);
          }
        });
  }

  private TreeWalk newRecursiveTreeWalk(Repository repository, RevTree tree) throws IOException {
    TreeWalk treeWalk = new TreeWalk(repository);
    treeWalk.addTree(tree);
    treeWalk.setRecursive(true);
    return treeWalk;
  }

  private Repository openRepository(GerritChange change) throws IOException {
    log.debug("Opening repository for change: {}", change.getFullChangeId());
    if (repositoryManager == null) {
      throw new IOException("GitRepositoryManager is not available");
    }
    return repositoryManager.openRepository(change.getProjectNameKey());
  }

  RevTree getBranchRevTree(Repository repository, GerritChange change) throws IOException {
    return getRevTree(repository, change.getBranchNameKey().branch(), "Branch");
  }

  RevTree getPatchSetRevTree(Repository repository, GerritChange change) throws IOException {
    int changeNumber =
        change
            .getChangeNumber()
            .orElseThrow(() -> new IOException("Change number is not available"));
    int patchSetNumber =
        change
            .getPatchSetAttribute()
            .map(attribute -> attribute.number)
            .orElseThrow(() -> new IOException("Patch set number is not available"));
    String patchSetRef = PatchSet.id(Change.id(changeNumber), patchSetNumber).toRefName();
    return getRevTree(repository, patchSetRef, "Patch set");
  }

  public Set<String> getPatchSetChangedFiles(GerritChange change) throws IOException {
    int changeNumber =
        change
            .getChangeNumber()
            .orElseThrow(() -> new IOException("Change number is not available"));
    int patchSetNumber =
        change
            .getPatchSetAttribute()
            .map(attribute -> attribute.number)
            .orElseThrow(() -> new IOException("Patch set number is not available"));
    String patchSetRef = PatchSet.id(Change.id(changeNumber), patchSetNumber).toRefName();

    try (Repository repository = openRepository(change);
        RevWalk revWalk = new RevWalk(repository)) {
      ObjectId commitId = repository.resolve(patchSetRef);
      if (commitId == null) {
        throw new IOException("Patch set not found: " + patchSetRef);
      }
      RevCommit commit = revWalk.parseCommit(commitId);
      ObjectId baseTreeId = commit.getParentCount() == 0 ? null : commit.getParent(0).getId();

      Set<String> changedFiles = new HashSet<>();
      try (DiffFormatter diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
        diffFormatter.setRepository(repository);
        for (DiffEntry entry : diffFormatter.scan(baseTreeId, commit.getTree())) {
          changedFiles.add(
              entry.getChangeType() == DiffEntry.ChangeType.DELETE
                  ? entry.getOldPath()
                  : entry.getNewPath());
        }
      }
      return changedFiles;
    }
  }

  private RevTree getRevTree(Repository repository, String ref, String refType) throws IOException {
    ObjectId lastCommitId = repository.resolve(ref);
    if (lastCommitId == null) {
      throw new IOException(refType + " not found: " + ref);
    }
    try (RevWalk revWalk = new RevWalk(repository)) {
      return revWalk.parseCommit(lastCommitId).getTree();
    }
  }

  private String readFileContent(ObjectReader reader, RevTree tree, String path)
      throws IOException {
    try (TreeWalk treeWalk = TreeWalk.forPath(reader, path, tree)) {
      if (treeWalk != null) {
        return readFile(reader, treeWalk).text();
      }
      return null;
    }
  }

  /**
   * Reads an entry, returning its text and byte length together.
   *
   * <p>The length used to be left on the instance for the caller to pick up. That made this a
   * hidden out-parameter, so a second call on the same instance would silently overwrite the first
   * call's value before the first had read it.
   */
  private FileContent readFile(ObjectReader reader, TreeWalk treeWalk) throws IOException {
    ObjectId objectId = treeWalk.getObjectId(0);
    byte[] bytes = reader.open(objectId).getBytes();

    return new FileContent(new String(bytes, StandardCharsets.UTF_8), bytes.length);
  }

  private static String normalizePath(String path) {
    if (path == null) {
      return "";
    }
    return path.replaceAll("^/+", "").replaceAll("/+$", "");
  }

  private static boolean isUnderSubdir(String path, String subdir) {
    return subdir == null
        || subdir.isEmpty()
        || path.equals(subdir)
        || path.startsWith(subdir + "/");
  }

  private static void addGrepMatches(
      List<String> matches, String path, String content, String searchString) {
    String[] lines = content.split("\\R", -1);
    for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      if (lines[lineIndex].contains(searchString)) {
        matches.add(String.format("%s:%d: %s", path, lineIndex + 1, lines[lineIndex]));
      }
    }
  }

  private interface RepositoryTreeCallback<T> {
    T execute(Repository repository, RevTree tree) throws IOException;
  }

  private interface RepositoryTreeReaderCallback<T> {
    T execute(Repository repository, RevTree tree, ObjectReader reader) throws IOException;
  }

  private interface RevTreeResolver {
    RevTree resolve(Repository repository, GerritChange change) throws IOException;
  }

  private interface PathMatcher {
    boolean matches(String path);
  }

  private interface MatchingFileCollector {
    void collect(List<String> results, String path, TreeWalk treeWalk) throws IOException;
  }

  /**
   * The file filters for one call.
   *
   * <p>Held per call rather than on the instance: one {@code GitRepoFiles} serves every agent stage
   * running against a change, so instance state here decides one call's results using another
   * call's configuration.
   */
  private record FileSelection(List<String> enabled, List<String> disabled) {

    static FileSelection from(Configuration config) {
      return new FileSelection(
          config.getEnabledFileExtensions(), config.getDisabledFileExtensions());
    }

    boolean accepts(String path) {
      return isFileExtensionEnabled(path, enabled, disabled);
    }
  }

  /** An entry's text and byte length, read together so neither can be overwritten in between. */
  private record FileContent(String text, long size) {}
}
