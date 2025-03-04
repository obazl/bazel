// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.cmdline;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.vfs.OsPathPolicy;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** The name of an external repository. */
public final class RepositoryName {

  static final String DEFAULT_REPOSITORY = "";

  @SerializationConstant
  public static final RepositoryName DEFAULT = new RepositoryName(DEFAULT_REPOSITORY);

  @SerializationConstant
  public static final RepositoryName BAZEL_TOOLS = new RepositoryName("@bazel_tools");

  @SerializationConstant
  public static final RepositoryName LOCAL_CONFIG_PLATFORM =
      new RepositoryName("@local_config_platform");

  @SerializationConstant public static final RepositoryName MAIN = new RepositoryName("@");

  private static final Pattern VALID_REPO_NAME = Pattern.compile("@[\\w\\-.]*");

  private static final LoadingCache<String, RepositoryName> repositoryNameCache =
      Caffeine.newBuilder()
          .weakValues()
          .build(
              name -> {
                String errorMessage = validate(name);
                if (errorMessage != null) {
                  errorMessage =
                      "invalid repository name '"
                          + StringUtilities.sanitizeControlChars(name)
                          + "': "
                          + errorMessage;
                  throw new LabelSyntaxException(errorMessage);
                }
                return new RepositoryName(StringCanonicalizer.intern(name));
              });

  /**
   * Makes sure that name is a valid repository name and creates a new RepositoryName using it.
   *
   * @throws LabelSyntaxException if the name is invalid
   */
  public static RepositoryName create(String name) throws LabelSyntaxException {
    if (name.isEmpty()) {
      return DEFAULT;
    }
    if (name.equals("@")) {
      return MAIN;
    }
    try {
      return repositoryNameCache.get(name);
    } catch (CompletionException e) {
      Throwables.propagateIfPossible(e.getCause(), LabelSyntaxException.class);
      throw e;
    }
  }

  /**
   * Creates a RepositoryName from a known-valid string (not @-prefixed). Generally this is a
   * directory that has been created via getSourceRoot() or getPathUnderExecRoot().
   */
  public static RepositoryName createFromValidStrippedName(String name) {
    return repositoryNameCache.get("@" + name);
  }

  /**
   * Extracts the repository name from a PathFragment that was created with {@code
   * PackageIdentifier.getSourceRoot}.
   *
   * @return a {@code Pair} of the extracted repository name and the path fragment with stripped of
   *     "external/"-prefix and repository name, or null if none was found or the repository name
   *     was invalid.
   */
  public static Pair<RepositoryName, PathFragment> fromPathFragment(
      PathFragment path, boolean siblingRepositoryLayout) {
    if (!path.isMultiSegment()) {
      return null;
    }

    PathFragment prefix =
        siblingRepositoryLayout
            ? LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
            : LabelConstants.EXTERNAL_PATH_PREFIX;
    if (!path.startsWith(prefix)) {
      return null;
    }

    try {
      RepositoryName repoName = RepositoryName.create("@" + path.getSegment(1));
      PathFragment subPath = path.subFragment(2);
      return Pair.of(repoName, subPath);
    } catch (LabelSyntaxException e) {
      return null;
    }
  }

  private final String name;

  /**
   * Store the name if the owner repository where this repository name is requested. If this field
   * is not null, it means this instance represents the requested repository name that is actually
   * not visible from the owner repository and should fail in {@link RepositoryDelegatorFunction}
   * when fetching the repository.
   */
  private final String ownerRepoIfNotVisible;

  private RepositoryName(String name, String ownerRepoIfNotVisible) {
    this.name = name;
    this.ownerRepoIfNotVisible = ownerRepoIfNotVisible;
  }

  private RepositoryName(String name) {
    this(name, null);
  }

  /** Performs validity checking. Returns null on success, an error message otherwise. */
  static String validate(String name) {
    if (name.isEmpty() || name.equals("@")) {
      return null;
    }

    // Some special cases for more user-friendly error messages.
    if (!name.startsWith("@")) {
      return "workspace names must start with '@'";
    }
    if (name.equals("@.")) {
      return "workspace names are not allowed to be '@.'";
    }
    if (name.equals("@..")) {
      return "workspace names are not allowed to be '@..'";
    }

    if (!VALID_REPO_NAME.matcher(name).matches()) {
      return "workspace names may contain only A-Z, a-z, 0-9, '-', '_' and '.'";
    }

    return null;
  }

  /**
   * Returns the repository name without the leading "{@literal @}".  For the default repository,
   * returns "".
   */
  public String strippedName() {
    if (name.isEmpty()) {
      return name;
    }
    return name.substring(1);
  }

  /**
   * Create a {@link RepositoryName} instance that indicates the requested repository name is
   * actually not visible from the owner repository and should fail in {@link
   * RepositoryDelegatorFunction} when fetching with this {@link RepositoryName} instance.
   */
  public RepositoryName toNonVisible(String ownerRepo) {
    Preconditions.checkNotNull(ownerRepo);
    return new RepositoryName(name, ownerRepo);
  }

  public boolean isVisible() {
    return ownerRepoIfNotVisible == null;
  }

  @Nullable
  public String getOwnerRepoIfNotVisible() {
    return ownerRepoIfNotVisible;
  }

  /**
   * Returns the repository name without the leading "{@literal @}". For the default repository,
   * returns "".
   */
  public static String stripName(String repoName) {
    return repoName.startsWith("@") ? repoName.substring(1) : repoName;
  }

  /**
   * Returns if this is the default repository, that is, {@link #name} is "".
   */
  public boolean isDefault() {
    return name.isEmpty();
  }

  /**
   * Returns if this is the main repository, that is, {@link #name} is "@".
   */
  public boolean isMain() {
    return name.equals("@");
  }

  /**
   * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
   */
  // TODO(bazel-team): Use this over toString()- easier to track its usage.
  public String getName() {
    return name;
  }

  /**
   * Returns the repository name, except that the main repo is conflated with the default repo
   * ({@code "@"} becomes the empty string).
   */
  public String getCanonicalForm() {
    return isMain() ? "" : name;
  }

  /**
   * Returns the runfiles/execRoot path for this repository. If we don't know the name of this repo
   * (i.e., it is in the main repository), return an empty path fragment.
   *
   * <p>If --experimental_sibling_repository_layout is true, return "$execroot/../repo" (sibling of
   * __main__), instead of "$execroot/external/repo".
   */
  public PathFragment getExecPath(boolean siblingRepositoryLayout) {
    if (isDefault() || isMain()) {
      return PathFragment.EMPTY_FRAGMENT;
    }
    PathFragment prefix =
        siblingRepositoryLayout
            ? LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
            : LabelConstants.EXTERNAL_PATH_PREFIX;
    return prefix.getRelative(strippedName());
  }

  /**
   * Returns the runfiles path relative to the x.runfiles/main-repo directory.
   */
  // TODO(kchodorow): remove once execroot is reorg-ed.
  public PathFragment getRunfilesPath() {
    return isDefault() || isMain()
        ? PathFragment.EMPTY_FRAGMENT : PathFragment.create("..").getRelative(strippedName());
  }

  /**
   * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
   */
  @Override
  public String toString() {
    return name;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof RepositoryName)) {
      return false;
    }
    RepositoryName other = (RepositoryName) object;
    return OsPathPolicy.getFilePathOs().equals(name, other.name)
        && OsPathPolicy.getFilePathOs().equals(ownerRepoIfNotVisible, other.ownerRepoIfNotVisible);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        OsPathPolicy.getFilePathOs().hash(name),
        OsPathPolicy.getFilePathOs().hash(ownerRepoIfNotVisible));
  }
}
