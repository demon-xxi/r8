// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming.b123068484;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.naming.b123068484.data.Concrete1;
import com.android.tools.r8.naming.b123068484.runner.Runner;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FieldRenamingTest extends TestBase {
  private static final Class<?> MAIN = Runner.class;
  private static final Class<?> CONCRETE1 = Concrete1.class;
  private static List<Path> CLASSES;
  private static final String EXPECTED_OUTPUT = StringUtils.lines("Runner");

  private final Backend backend;

  @Parameterized.Parameters(name = "Backend: {0}")
  public static Object[] data() {
    return Backend.values();
  }

  public FieldRenamingTest(Backend backend) {
    this.backend = backend;
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    CLASSES = new LinkedList<>();
    CLASSES.addAll(ToolHelper.getClassFilesForTestPackage(MAIN.getPackage()));
    CLASSES.addAll(ToolHelper.getClassFilesForTestPackage(CONCRETE1.getPackage()));
  }

  @Test
  public void testProguard() throws Exception {
    Path inJar = temp.newFile("input.jar").toPath().toAbsolutePath();
    writeToJar(inJar, CLASSES);
    testForProguard()
        .addProgramFiles(inJar)
        .addKeepMainRule(MAIN)
        .compile()
        .inspect(this::inspect)
        .run(MAIN)
        .assertSuccessWithOutput(EXPECTED_OUTPUT);
  }

  @Ignore("b/123068484")
  @Test
  public void testR8() throws Exception {
    testForR8(backend)
        .addProgramFiles(CLASSES)
        .addKeepMainRule(MAIN)
        .compile()
        .inspect(this::inspect)
        .run(MAIN)
        .assertSuccessWithOutput(EXPECTED_OUTPUT);
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject main = inspector.clazz(MAIN);
    assertThat(main, isPresent());
    MethodSubject methodSubject = main.mainMethod();
    assertThat(methodSubject, isPresent());

    methodSubject
        .iterateInstructions(InstructionSubject::isInstanceGet)
        .forEachRemaining(instructionSubject -> {
          String fieldName = instructionSubject.getField().name.toString();
          assertNotEquals("strField", fieldName);
        });
  }

}
