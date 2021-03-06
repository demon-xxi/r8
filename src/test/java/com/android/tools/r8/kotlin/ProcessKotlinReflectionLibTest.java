// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin;

import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.KotlinTargetVersion;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ProcessKotlinReflectionLibTest extends KotlinTestBase {
  private final Backend backend;

  public ProcessKotlinReflectionLibTest(Backend backend, KotlinTargetVersion targetVersion) {
    super(targetVersion);
    this.backend = backend;
  }

  @Parameterized.Parameters(name = "Backend: {0} target: {1}")
  public static Collection<Object[]> data() {
    return buildParameters(Backend.values(), KotlinTargetVersion.values());
  }

  private void test(String... rules) throws Exception {
    testForR8(backend)
        .addLibraryFiles(ToolHelper.getDefaultAndroidJar(), ToolHelper.getKotlinStdlibJar())
        .addProgramFiles(ToolHelper.getKotlinReflectJar())
        .addKeepRules(rules)
        .compile();
  }

  @Test
  public void testAsIs() throws Exception {
    test("-dontshrink", "-dontoptimize", "-dontobfuscate");
  }

  @Test
  public void testDontShrinkAndDontOptimize() throws Exception {
    test("-dontshrink", "-dontoptimize");
  }

  @Test
  public void testDontShrinkAndDontObfuscate() throws Exception {
    test("-dontshrink", "-dontobfuscate");
  }

  @Test
  public void testDontShrink() throws Exception {
    test("-dontshrink");
  }

  @Test
  public void testDontOptimize() throws Exception {
    test("-dontoptimize");
  }

  @Test
  public void testDontObfuscate() throws Exception {
    test("-dontobfuscate");
  }

}
