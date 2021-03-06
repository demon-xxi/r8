// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.ToolHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ProguardMapReaderTest {

  private static final String ROOT = ToolHelper.EXAMPLES_BUILD_DIR;
  private static final String EXAMPLE_MAP = "throwing/throwing.map";
  private static final String EXAMPLE_MAP_WITH_PACKAGE_INFO =
      "dagger.android.package-info -> dagger.android.package-info\n";

  @Test
  public void parseThrowingMap() throws IOException {
    ClassNameMapper.mapperFromFile(Paths.get(ROOT, EXAMPLE_MAP));
  }

  @Test
  public void parseQuestionMarkMethod() throws IOException {
    // Regression test for b/120856784
    String mapping =
        "com.c.c.b -> com.c.c.b:\n" +
            "    1287:1287:int ?(int,int) -> ?";
    ClassNameMapper.mapperFromString(mapping);

    // From some other proguard generated map
    mapping = "com.moat.analytics.mobile.cha.b -> com.moat.analytics.mobile.cha.b:\n"
        + "    com.moat.analytics.mobile.cha.MoatAdEventType[] ? -> ?\n"
        + "    java.util.HashMap ? -> ?\n"
        + "    java.util.HashSet ?? -> ??\n";
    ClassNameMapper.mapperFromString(mapping);
  }


  @Test
  public void roundTripTest() throws IOException {
    ClassNameMapper firstMapper = ClassNameMapper.mapperFromFile(Paths.get(ROOT, EXAMPLE_MAP));
    ClassNameMapper secondMapper = ClassNameMapper.mapperFromString(firstMapper.toString());
    Assert.assertEquals(firstMapper, secondMapper);
  }

  @Test
  public void parseIdentifierArrowAmbiguity1() throws IOException {
    ClassNameMapper mapper = ClassNameMapper.mapperFromString("a->b:");
    ClassNameMapper.Builder builder = ClassNameMapper.builder();
    builder.classNamingBuilder("b", "a");
    Assert.assertEquals(builder.build(), mapper);
  }

  @Test
  public void parseIdentifierArrowAmbiguity2() throws IOException {
    ClassNameMapper mapper = ClassNameMapper.mapperFromString("-->b:");
    ClassNameMapper.Builder builder = ClassNameMapper.builder();
    builder.classNamingBuilder("b", "-");
    Assert.assertEquals(builder.build(), mapper);
  }

  @Test
  public void parseMapWithPackageInfo() throws IOException {
    ClassNameMapper mapper = ClassNameMapper.mapperFromString(EXAMPLE_MAP_WITH_PACKAGE_INFO);
    Assert.assertTrue(mapper.getObfuscatedToOriginalMapping().original.isEmpty());
  }

  @Test
  public void testSingleCases() throws IOException {
    List<String> ss =
        ImmutableList.of(
            /* */ "a.b.C -> d.e.F:\n" //
                + "    void a() -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a() -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:2:void a() -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a():2:2 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:2:void a():11:12 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a():11:11 -> b\n" //
                + "    1:1:void c():21 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:2:void a():11:12 -> b\n"
                + "    1:2:void c():21 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a(int):11:11 -> b\n" //
                + "    1:1:void c():21 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void g.a(int):11:11 -> b\n" //
                + "    1:1:void c():21 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a(int):11:11 -> b\n" //
                + "    2:2:void c():21:21 -> b\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    1:1:void a():11:11 -> b\n" //
                + "    1:1:void c():21:21 -> d\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    2:2:void f1():11:11 -> b\n" //
                + "    2:2:void f2():21 -> b\n" //
                + "    2:2:void f3():21 -> b\n" //
                + "    2:2:void f4():22 -> b\n" //
                + "    2:2:void f5():21 -> b\n" //
                + "    3:3:void f6():12:34 -> c\n",
            /* */ "a.b.C -> d.e.F:\n" //
                + "    2:2:void f1():11:11 -> b\n" //
                + "    2:2:void f2():21 -> b\n" //
                + "    2:2:void f3() -> b\n" //
                + "    2:2:void f5():21 -> b\n" //
                + "    3:3:void f6() -> c\n");

    for (String s : ss) {
      ClassNameMapper cnm = ClassNameMapper.mapperFromString(s);
      String result = cnm.toString();
      Assert.assertEquals(s, result);
    }
  }
}
