// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.util.EventCollectionApparatus;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.QueryResult;
import com.google.devtools.build.lib.query2.query.output.QueryOptions;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.GotOptionsEvent;
import com.google.devtools.build.lib.runtime.commands.QueryCommand;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.ExtensionRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Integration tests for 'blaze query'.
 */
@RunWith(JUnit4.class)
public class QueryIntegrationTest extends BuildIntegrationTestCase {
  private final CustomFileSystem fs = new CustomFileSystem();
  private final List<String> options = new ArrayList<>();

  @Override
  protected EventCollectionApparatus createEvents() {
    ImmutableSet.Builder<EventKind> eventsSet = ImmutableSet.builder();
    eventsSet.addAll(EventKind.ERRORS_AND_WARNINGS_AND_OUTPUT);
    eventsSet.add(EventKind.PROGRESS);
    return new EventCollectionApparatus(eventsSet.build());
  }

  private static class CustomFileSystem extends UnixFileSystem {
    final Map<PathFragment, FileStatus> stubbedStats = new HashMap<>();

    CustomFileSystem() {
      super(DigestHashFunction.SHA256, "");
    }

    void stubStat(Path path, @Nullable FileStatus stubbedResult) {
      stubbedStats.put(path.asFragment(), stubbedResult);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (stubbedStats.containsKey(path)) {
        return stubbedStats.get(path);
      }
      return super.statIfFound(path, followSymlinks);
    }
  }

  private static class QueryOutput {
    private final BlazeCommandResult blazeCommandResult;
    private final byte[] stdout;

    public QueryOutput(BlazeCommandResult blazeCommandResult, byte[] stdout) {
      this.blazeCommandResult = blazeCommandResult;
      this.stdout = stdout;
    }

    public BlazeCommandResult getBlazeCommandResult() {
      return blazeCommandResult;
    }

    public byte[] getStdout() {
      return stdout;
    }
  }

  private static class ProtoQueryOutput {
    private final QueryResult queryResult;
    private final QueryOutput queryOutput;

    public ProtoQueryOutput(QueryOutput queryOutput, QueryResult queryResult) {
      this.queryResult = queryResult;
      this.queryOutput = queryOutput;
    }

    public QueryResult getQueryResult() {
      return queryResult;
    }

    public QueryOutput getQueryOutput() {
      return queryOutput;
    }
  }

  @Override
  protected FileSystem createFileSystem() {
    return fs;
  }

  @Before
  public final void setQueryOptions() {
    runtimeWrapper.addOptionsClass(QueryOptions.class);
  }

  // Number large enough that an unordered collection with this many elements will never happen to
  // iterate over them in their "natural" order.
  private static final int NUM_DEPS = 1000;

  private static void assertSameElementsDifferentOrder(List<String> actual, List<String> expected) {
    assertThat(actual).containsExactlyElementsIn(expected);
    int i = 0;
    for (; i < expected.size(); i++) {
      if (!actual.get(i).equals(expected.get(i))) {
        break;
      }
    }
    assertWithMessage("Lists should not have been in same order")
        .that(i < expected.size())
        .isTrue();
  }

  private static List<String> getTargetNames(QueryResult result) {
    List<String> results = new ArrayList<>();
    for (Build.Target target : result.getTargetList()) {
      results.add(target.getRule().getName());
    }
    return results;
  }

  @Test
  public void testProtoUnorderedAndOrdered() throws Exception {
    List<String> expected = new ArrayList<>(NUM_DEPS + 1);
    String targets = "";
    String depString = "";
    for (int i = 0; i < NUM_DEPS; i++) {
      String dep = Integer.toString(i);
      depString += "'" + dep + "', ";
      expected.add("//foo:" + dep);
      targets += "sh_library(name = '" + dep + "')\n";
    }
    expected.add("//foo:a");
    Collections.sort(expected, Collections.reverseOrder());
    write("foo/BUILD", "sh_library(name = 'a', deps = [" + depString + "])", targets);
    ProtoQueryOutput result = getProtoQueryResult("deps(//foo:a)");
    assertSameElementsDifferentOrder(getTargetNames(result.getQueryResult()), expected);
    options.add("--order_output=full");
    result = getProtoQueryResult("deps(//foo:a)");
    assertThat(getTargetNames(result.getQueryResult()))
        .containsExactlyElementsIn(expected)
        .inOrder();
  }

  /**
   * Test that {min,max}rank work as expected with ordering. Since minrank and maxrank have special
   * handling for cycles in the graph, we put a cycle in to exercise that code.
   */
  private void assertRankUnorderedAndOrdered(boolean minRank) throws Exception {
    List<String> expected = new ArrayList<>(2 * NUM_DEPS + 1);
    // The build file looks like:
    // sh_library(name = 'a', deps = ['cycle1', '1', '2', ..., ]
    // sh_library(name = '1')
    // ...
    // sh_library(name = 'n')
    // sh_library(name = 'cycle0', deps = ['cyclen'])
    // sh_library(name = 'cycle1', deps = ['cycle0'])
    // ...
    // sh_library(name = 'cyclen', deps = ['cycle{n-1}'])
    String targets = "";
    String depString = "";
    for (int i = 0; i < NUM_DEPS; i++) {
      String dep = Integer.toString(i);
      depString += "'" + dep + "', ";
      expected.add("1 //foo:" + dep);
      expected.add("1 //foo:cycle" + dep);
      targets += "sh_library(name = '" + dep + "')\n";
      targets += "sh_library(name = 'cycle" + dep + "', deps = ['cycle";
      if (i > 0) {
        targets += i - 1;
      } else {
        targets += NUM_DEPS - 1;
      }
      targets += "'])\n";
    }
    Collections.sort(expected);
    expected.add(0, "0 //foo:a");
    options.add("--output=" + (minRank ? "minrank" : "maxrank"));
    options.add("--keep_going");
    write("foo/BUILD", "sh_library(name = 'a', deps = ['cycle0', " + depString + "])", targets);
    List<String> result = getStringQueryResult("deps(//foo:a)");
    assertWithMessage(result.toString()).that(result.get(0)).isEqualTo("0 //foo:a");
    assertSameElementsDifferentOrder(result, expected);
    options.add("--order_output=full");
    result = getStringQueryResult("deps(//foo:a)");
    assertWithMessage(result.toString()).that(result.get(0)).isEqualTo("0 //foo:a");
    assertThat(result).containsExactlyElementsIn(expected).inOrder();
  }

  @Test
  public void testMinrankUnorderedAndOrdered() throws Exception {
    assertRankUnorderedAndOrdered(true);
  }

  @Test
  public void testMaxrankUnorderedAndOrdered() throws Exception {
    assertRankUnorderedAndOrdered(false);
  }

  @Test
  public void testLabelOrderedFullAndDeps() throws Exception {
    List<String> expected = new ArrayList<>(NUM_DEPS + 1);
    String targets = "";
    String depString = "";
    for (int i = 0; i < NUM_DEPS; i++) {
      String dep = Integer.toString(i);
      depString += "'" + dep + "', ";
      expected.add("//foo:" + dep);
      targets += "sh_library(name = '" + dep + "')\n";
    }
    expected.add("//foo:a");
    Collections.sort(expected);
    write("foo/BUILD", "sh_library(name = 'a', deps = [" + depString + "])", targets);
    List<String> result = getStringQueryResult("deps(//foo:a)");
    assertThat(result).containsExactlyElementsIn(expected).inOrder();
    options.add("--order_output=deps");
    result = getStringQueryResult("deps(//foo:a)");
    assertSameElementsDifferentOrder(result, expected);
  }

  @Test
  public void testInputFileElementContainsPackageGroups() throws Exception {
    write("fruit/BUILD",
        "package_group(name='coconut', packages=['//fruit/walnut'])",
        "exports_files(['chestnut'], visibility=[':coconut'])");

    Document result = getXmlQueryResult("//fruit:chestnut");
    Element resultNode = getResultNode(result, "//fruit:chestnut");

    assertThat(
            Iterables.getOnlyElement(
                xpathSelect(resultNode, "package-group[@name='//fruit:coconut']")))
        .isNotNull();
  }

  @Test
  public void testNonStrictTests() throws Exception {
    write("donut/BUILD",
        "sh_binary(name = 'thief', srcs = ['thief.sh'])",
        "cc_test(name = 'shop', srcs = ['shop.cc'])",
        "test_suite(name = 'cop', tests = [':thief', ':shop'])");

    // This should not throw an exception, and return 0 targets.
    ProtoQueryOutput result = getProtoQueryResult("tests(//donut:cop)");
    QueryResult queryResult = result.getQueryResult();
    assertThat(queryResult.getTargetCount()).isEqualTo(1);
    assertThat(queryResult.getTarget(0).getRule().getName()).isEqualTo("//donut:shop");
  }

  @Test
  public void testStrictTests() throws Exception {
    options.add("--strict_test_suite=true");
    write("donut/BUILD",
        "sh_binary(name = 'thief', srcs = ['thief.sh'])",
        "test_suite(name = 'cop', tests = [':thief'])");

    ProtoQueryOutput result = getProtoQueryResult("tests(//donut:cop)");
    BlazeCommandResult blazeCommandResult = result.getQueryOutput().getBlazeCommandResult();
    assertExitCode(result.getQueryOutput(), ExitCode.ANALYSIS_FAILURE);
    assertThat(blazeCommandResult.getFailureDetail().getMessage())
        .contains(
            "The label '//donut:thief' in the test_suite "
                + "'//donut:cop' does not refer to a test");
  }

  private void createBadBarBuild() throws IOException {
    Path barBuildFile = write("bar/BUILD", "sh_library(name = 'bar/baz')");
    FileStatus inconsistentFileStatus =
        new FileStatus() {
          @Override
          public boolean isFile() {
            return false;
          }

          @Override
          public boolean isSpecialFile() {
            return false;
          }

          @Override
          public boolean isDirectory() {
            return false;
          }

          @Override
          public boolean isSymbolicLink() {
            return false;
          }

          @Override
          public long getSize() {
            return 0;
          }

          @Override
          public long getLastModifiedTime() {
            return 0;
          }

          @Override
          public long getLastChangeTime() {
            return 0;
          }

          @Override
          public long getNodeId() {
            return 0;
          }
        };
    fs.stubStat(barBuildFile, inconsistentFileStatus);
  }

  // Regression test for b/14248208.
  private void runInconsistentFileSystem(boolean keepGoing) throws Exception {
    createBadBarBuild();
    if (keepGoing) {
      options.add("--keep_going");
    }
    QueryOutput result = getQueryResult("deps(//bar:baz)");
    ExitCode expectedExitcode =
        keepGoing ? ExitCode.PARTIAL_ANALYSIS_FAILURE : ExitCode.ANALYSIS_FAILURE;
    assertExitCode(result, expectedExitcode);
    events.assertContainsError("Inconsistent filesystem operations");
    assertThat(events.errors()).hasSize(1);
  }

  @Test
  public void inconsistentFileSystemKeepGoing() throws Exception {
    runInconsistentFileSystem(/*keepGoing=*/ true);
  }

  @Test
  public void inconsistentFileSystemNoKeepGoing() throws Exception {
    runInconsistentFileSystem(/*keepGoing=*/ false);
  }

  private void runDepInconsistentFileSystem(boolean keepGoing) throws Exception {
    write("foo/BUILD", "sh_library(name = 'foo', deps = ['//bar:baz'])");
    createBadBarBuild();
    if (keepGoing) {
      options.add("--keep_going");
    }
    QueryOutput result = getQueryResult("deps(//foo:foo)");
    ExitCode expectedExitcode =
        keepGoing ? ExitCode.PARTIAL_ANALYSIS_FAILURE : ExitCode.ANALYSIS_FAILURE;
    assertExitCode(result, expectedExitcode);
    events.assertContainsError("Inconsistent filesystem operations");
    events.assertContainsError("and referenced by '//foo:foo'");
    events.assertContainsError("Evaluation of query \"deps(//foo:foo)\" failed: errors were ");
    // TODO(janakr): We emit duplicate events: in the ErrorPrintingTargetEdgeErrorObserver and in
    //  TransitiveTargetFunction. Should be able to remove one of them, most likely
    //  TransitiveTargetFunction.
    assertThat(events.errors()).hasSize(3);
  }

  @Test
  public void depInconsistentFileSystemKeepGoing() throws Exception {
    runDepInconsistentFileSystem(/*keepGoing=*/ true);
  }

  @Test
  public void depInconsistentFileSystemNoKeepGoing() throws Exception {
    runDepInconsistentFileSystem(/*keepGoing=*/ false);
  }

  @Test
  public void invalidQueryFailsParsing() throws Exception {
    QueryOutput result = getQueryResult("deps(\"--bad_target_name_from_bad_script\")");

    assertCommandLineErrorExitCode(result);
    assertThat(result.getStdout()).isEmpty();
    events.assertContainsError("target literal must not begin with (-)");
  }

  @Test
  public void siblingsFunction() throws Exception {
    write(
        "foo/BUILD",
        "sh_library(name='t1')",
        "sh_library(name='t2')",
        "sh_library(name='t3')",
        "sh_library(name='t4')",
        "sh_library(name='t5')");

    QueryOutput result = getQueryResult("siblings(//foo:t1)");
    assertSuccessfulExitCode(result);
    assertThat(result.getStdout()).isNotEmpty();
  }

  @Test
  public void samePackageDirectRDepsFunction() throws Exception {
    write(
        "foo/BUILD",
        "sh_library(name='t1', srcs=['t1.sh'])",
        "sh_library(name='t2', srcs=['t2.sh'])",
        "sh_library(name='t3', srcs=['t2.sh'])");

    QueryOutput result = getQueryResult("same_pkg_direct_rdeps(//foo:t1.sh)");
    assertSuccessfulExitCode(result);

    assertQueryOutputContains(result, "//foo:t1");
    assertQueryOutputDoesNotContain(result, "//foo:t2", "/foo:t3");
  }

  @Test
  public void graphlessQuery() throws Exception {
    write("foo/BUILD", "sh_library(name='foo', srcs=['foo.sh'])");

    QueryOutput result =
        getQueryResult("//foo", "--experimental_graphless_query", "--order_output=no");
    assertSuccessfulExitCode(result);
    assertQueryOutputContains(result, "//foo:foo");
  }

  @Test
  public void graphlessQueryRequiresUnorderedOutput() throws Exception {
    write("foo/BUILD", "sh_library(name='foo', srcs=['foo.sh'])");

    QueryOutput result =
        getQueryResult("//foo", "--experimental_graphless_query", "--order_output=deps");
    events.assertContainsError(
        "--experimental_graphless_query requires --order_output=no or --order_output=auto");
    assertCommandLineErrorExitCode(result);
    assertThat(result.getStdout()).isEmpty();
  }

  @Test
  public void graphlessQueryWithLexicographicalOutput() throws Exception {
    write("foo/BUILD", "sh_library(name='foo', srcs=['foo.sh'])");

    QueryOutput result =
        getQueryResult(
            "//foo",
            "--experimental_graphless_query",
            "--order_output=auto",
            "--incompatible_lexicographical_output");
    assertSuccessfulExitCode(result);
    assertThat(result.getStdout()).isNotEmpty();
  }

  @Test
  public void graphlessQueryRequiresStreamedFormatter() throws Exception {
    write("foo/BUILD", "sh_library(name='foo', srcs=['foo.sh'])");

    QueryOutput result =
        getQueryResult(
            "//foo", "--experimental_graphless_query", "--order_output=no", "--output=maxrank");

    assertCommandLineErrorExitCode(result);
    assertThat(result.getStdout()).isEmpty();
    events.assertContainsError(
        "--experimental_graphless_query requires --order_output=no or --order_output=auto and an"
            + " --output option that supports streaming");
  }

  @Test
  public void ruleStackInBuildOutput() throws Exception {
    /*
     * See b/151165647 - This needs a non-trivial package name to avoid
     * including extraneous directories in the generator_location.
     */

    write(
        "package/inc.bzl",
        "def _impl(ctx): pass",
        "myrule = rule(implementation = _impl)",
        "def f():",
        "  g()",
        "def g():",
        "  myrule(name='a')");

    write("package/BUILD", "load('inc.bzl', 'f')\n" + "f()");

    QueryOutput result = getQueryResult("//package:a", "--output=build");
    assertSuccessfulExitCode(result);
    // TODO(b/151165647): fix the heuristic that incorrectly creates generator_location by//
    //  relativizing package name "p" relative to /foo/tmp/ regardless of segment boundaries.
    // TODO(b/151151653): the output should contain only workspace-relative paths.
    String workspaceDir = getWorkspace().toString();
    String expectedOut =
        "# "
            + workspaceDir
            + "/package/BUILD:2:2\n"
            + "myrule(\n"
            + "  name = \"a\",\n"
            + "  generator_name = \"a\",\n"
            + "  generator_function = \"f\",\n"
            + "  generator_location = "
            + "\"package/BUILD:2:2\",\n"
            + ")\n"
            + "# Rule a instantiated at (most recent call last):\n"
            + "#   "
            + workspaceDir
            + "/package/BUILD:2:2   in <toplevel>\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:4:4 in f\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:6:9 in g\n"
            + "# Rule myrule defined at (most recent call last):\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:2:14 in <toplevel>\n\n";

    String out = new String(result.getStdout(), UTF_8);

    assertThat(out).isEqualTo(expectedOut);
  }

  /*
   * Test of instantiation_stack (b/36593041) through query --output=build
   */
  @Test
  public void ruleStackInProtoOutput() throws Exception {
    write(
        "p/inc.bzl",
        "def _impl(ctx): pass",
        "myrule = rule(implementation = _impl)",
        "def f():",
        "  g()",
        "def g():",
        "  myrule(name='a')");

    write("p/BUILD", "load('inc.bzl', 'f')", "f()");
    ProtoQueryOutput result =
        getProtoQueryResult("//p:a", "--output=proto", "--proto:instantiation_stack=true");
    assertSuccessfulExitCode(result.getQueryOutput());

    String expectedProtoOut =
        "    instantiation_stack: \"p/BUILD:2:2: <toplevel>\"\n"
            + "    instantiation_stack: \"p/inc.bzl:4:4: f\"\n"
            + "    instantiation_stack: \"p/inc.bzl:6:9: g\"";
    String actualProtoOut = result.getQueryResult().toString();

    assertThat(actualProtoOut).contains(expectedProtoOut);
  }

  /*
   * Regression test for b/162110273.
   */
  @Test
  public void ruleStackRegressionTest() throws Exception {
    /*
     * See b/151165647 - This needs a non-trivial package name to avoid
     * including extraneous directories in the generator_location.
     */

    write(
        "package/inc.bzl",
        "def g(name):",
        "    native.cc_library(name = name)",
        "",
        "def f(name):",
        "    g(name)");

    write("package/BUILD", "load(\"inc.bzl\", \"f\")", "f(name = \"a\")", "f(name = \"b\")");
    QueryOutput result = getQueryResult("//package:all", "--output=build");
    assertSuccessfulExitCode(result);

    String workspaceDir = getWorkspace().toString();
    String expectedOut =
        "# "
            + workspaceDir
            + "/package/BUILD:2:2\n"
            + "cc_library(\n"
            + "  name = \"a\",\n"
            + "  generator_name = \"a\",\n"
            + "  generator_function = \"f\",\n"
            + "  generator_location = "
            + "\"package/BUILD:2:2\",\n"
            + ")\n"
            + "# Rule a instantiated at (most recent call last):\n"
            + "#   "
            + workspaceDir
            + "/package/BUILD:2:2    in <toplevel>\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:5:6  in f\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:2:22 in g\n"
            + "\n"
            + "# "
            + workspaceDir
            + "/package/BUILD:3:2\n"
            + "cc_library(\n"
            + "  name = \"b\",\n"
            + "  generator_name = \"b\",\n"
            + "  generator_function = \"f\",\n"
            + "  generator_location = "
            + "\"package/BUILD:3:2\",\n"
            + ")\n"
            + "# Rule b instantiated at (most recent call last):\n"
            + "#   "
            + workspaceDir
            + "/package/BUILD:3:2    in <toplevel>\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:5:6  in f\n"
            + "#   "
            + workspaceDir
            + "/package/inc.bzl:2:22 in g\n\n";

    String out = new String(result.getStdout(), UTF_8);
    assertThat(out).isEqualTo(expectedOut);
  }

  private void assertDepthBoundedQuery(boolean orderResults) throws Exception {
    if (orderResults) {
      options.add("--order_output=auto");
    } else {
      options.add("--order_output=no");
      options.add("--universe_scope=//depth:*");
    }

    write(
        "depth/BUILD",
        "sh_binary(name = 'one', srcs = ['one.sh'], deps = [':two'])",
        "sh_library(name = 'two', srcs = ['two.sh'],",
        "           deps = [':div2', ':three', '//depth2:three'])",
        "sh_library(name = 'three', srcs = ['three.sh'], deps = [':four'])",
        "sh_library(name = 'four', srcs = ['four.sh'], deps = [':div2', ':five'])",
        "sh_library(name = 'five', srcs = ['five.sh'])",
        "sh_library(name = 'div2', srcs = ['two.sh'])");

    write("depth2/BUILD", "sh_library(name = 'three', srcs = ['three.sh'])");
    write("depth/one.sh", "");
    write("depth/two.sh", "");
    write("depth/three.sh", "");
    write("depth/four.sh", "");
    write("depth/five.sh", "");

    write("depth2/three.sh", "");

    QueryOutput oneDep = getQueryResult("deps(//depth:one, 1)");
    assertQueryOutputContains(oneDep, "//depth:one.sh", "//depth:two", TestConstants.LAUNCHER_PATH);
    assertQueryOutputDoesNotContain(oneDep, "//depth2");

    // Ensure that the whole transitive closure wasn't pulled in earlier if not pre-loading.
    QueryOutput threeDep =
        getQueryResult("deps(//depth:one, 3)", "--experimental_ui_debug_all_events");

    if (orderResults) {
      events.assertContainsEvent(EventKind.PROGRESS, "Loading package: depth2");
    }

    assertQueryOutputContains(
        threeDep,
        "//depth:one",
        "//depth:one.sh",
        "//depth:two",
        "//depth:two.sh",
        "//depth:div2",
        "//depth:three",
        "//depth:three.sh",
        "//depth:four",
        "//depth2:three",
        "//depth2:three.sh",
        TestConstants.LAUNCHER_PATH);

    QueryOutput oneDepNonExperimental = getQueryResult("deps(//depth:one, 3)");

    /*
     * --experimental_ui_debug_all_events and expect_query_targets are not
     * mutually compatible at this time, so we run this again to check that the
     * output is exact rather than a superset.
     */
    assertQueryOutputContains(
        oneDepNonExperimental,
        "//depth:one",
        "//depth:one.sh",
        "//depth:two",
        "//depth:two.sh",
        "//depth:div2",
        "//depth:three",
        "//depth:three.sh",
        "//depth:four",
        "//depth2:three",
        "//depth2:three.sh",
        TestConstants.LAUNCHER_PATH);

    QueryOutput twoDep =
        getQueryResult("deps(//depth:one, 2)", "--experimental_ui_debug_all_events");

    events.clear();
    // Restricting the query, however, should not cause reloading.
    events.assertDoesNotContainEvent("Loading package:");

    assertQueryOutputContains(
        twoDep,
        "//depth:one",
        "//depth:one.sh",
        "//depth:two",
        "//depth:two.sh",
        "//depth:three",
        "//depth:div2",
        "//depth2:three",
        TestConstants.LAUNCHER_PATH);

    // Same as above
    QueryOutput twoDepNonExperimental = getQueryResult("deps(//depth:one, 2)");

    assertQueryOutputContains(
        twoDepNonExperimental,
        "//depth:one",
        "//depth:one.sh",
        "//depth:two",
        "//depth:two.sh",
        "//depth:three",
        "//depth:div2",
        "//depth2:three",
        TestConstants.LAUNCHER_PATH);
  }

  @Test
  public void depthBoundedQueryUnordered() throws Exception {
    assertDepthBoundedQuery(false);
  }

  @Test
  public void depthBoundedQueryOrdered() throws Exception {
    assertDepthBoundedQuery(true);
  }

  private void assertExitCode(QueryOutput result, ExitCode expected) {
    assertThat(result.getBlazeCommandResult().getExitCode()).isEqualTo(expected);
  }

  private void assertSuccessfulExitCode(QueryOutput result) {
    assertExitCode(result, ExitCode.SUCCESS);
  }

  private void assertCommandLineErrorExitCode(QueryOutput result) {
    assertExitCode(result, ExitCode.COMMAND_LINE_ERROR);
  }

  private void assertQueryOutputContains(QueryOutput result, String... expectedStrings) {
    String out = new String(result.getStdout(), UTF_8);
    for (String expectedString : expectedStrings) {
      assertThat(out).contains(expectedString);
    }
  }

  private void assertQueryOutputDoesNotContain(QueryOutput result, String... unexpected) {
    String out = new String(result.getStdout(), UTF_8);
    for (String log : unexpected) {
      assertThat(out).doesNotContain(log);
    }
  }

  private QueryOutput getQueryResult(String queryString, String... flags) throws Exception {
    Collections.addAll(options, flags);
    runtimeWrapper.resetOptions();
    runtimeWrapper.addOptions(options);
    runtimeWrapper.addOptions(queryString);
    CommandEnvironment env = runtimeWrapper.newCommand(QueryCommand.class);
    OptionsParsingResult options = env.getOptions();
    for (BlazeModule module : getRuntime().getBlazeModules()) {
      module.beforeCommand(env);
    }

    env.getEventBus()
        .post(
            new GotOptionsEvent(
                getRuntime().getStartupOptionsProvider(),
                options,
                InvocationPolicy.getDefaultInstance()));

    for (BlazeModule module : getRuntime().getBlazeModules()) {
      env.getSkyframeExecutor().injectExtraPrecomputedValues(module.getPrecomputedValues());
    }

    // In this test we are allowed to omit the beforeCommand; so force setting of a command
    // id in the CommandEnvironment, as we will need it in a moment even though we deviate from
    // normal calling order.
    try {
      env.getCommandId();
    } catch (IllegalArgumentException e) {
      // Ignored, as we know the test deviates from normal calling order.
    }

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    env.getReporter()
        .addHandler(
            event -> {
              if (event.getKind().equals(EventKind.STDOUT)) {
                try {
                  stdout.write(event.getMessageBytes());
                } catch (IOException e) {
                  throw new IllegalStateException(e);
                }
              }
            });
    BlazeCommandResult lastBlazeCommandResult = new QueryCommand().exec(env, options);
    return new QueryOutput(lastBlazeCommandResult, stdout.toByteArray());
  }

  private Document getXmlQueryResult(String queryString) throws Exception {
    options.add("--output=xml");
    byte[] queryResult = getQueryResult(queryString).getStdout();
    return DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(queryResult));
  }

  private static List<Node> xpathSelect(Node doc, String expression) throws Exception {
    XPathExpression expr = XPathFactory.newInstance().newXPath().compile(expression);
    NodeList result = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
    List<Node> list = new ArrayList<>();
    for (int i = 0; i < result.getLength(); i++) {
      list.add(result.item(i));
    }
    return list;
  }

  private List<String> getStringQueryResult(String queryString) throws Exception {
    QueryOutput result = getQueryResult(queryString);
    return Arrays.asList(new String(result.getStdout(), Charset.defaultCharset()).split("\n"));
  }

  private ProtoQueryOutput getProtoQueryResult(String queryString, String... flags)
      throws Exception {
    options.add("--output=proto");
    Collections.addAll(options, flags);
    QueryOutput result = getQueryResult(queryString);
    byte[] stdout = result.getStdout();
    QueryResult queryResult = QueryResult.parseFrom(stdout, ExtensionRegistry.getEmptyRegistry());

    return new ProtoQueryOutput(result, queryResult);
  }

  Element getResultNode(Document xml, String ruleName) throws Exception {
    return (Element) Iterables.getOnlyElement(xpathSelect(xml,
        String.format("/query/*[@name='%s']", ruleName)));
  }
}
