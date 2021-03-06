// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.debug.DebugTestConfig;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.AndroidAppConsumers;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class TestCompilerBuilder<
        C extends BaseCompilerCommand,
        B extends BaseCompilerCommand.Builder<C, B>,
        CR extends TestCompileResult<CR, RR>,
        RR extends TestRunResult,
        T extends TestCompilerBuilder<C, B, CR, RR, T>>
    extends TestBaseBuilder<C, B, CR, RR, T> {

  public static final Consumer<InternalOptions> DEFAULT_OPTIONS =
      new Consumer<InternalOptions>() {
        @Override
        public void accept(InternalOptions options) {}
      };

  final Backend backend;

  // Default initialized setup. Can be overwritten if needed.
  private Path defaultLibrary;
  private ProgramConsumer programConsumer;
  private StringConsumer mainDexListConsumer;
  private AndroidApiLevel defaultMinApiLevel = ToolHelper.getMinApiLevelForDexVm();
  private Consumer<InternalOptions> optionsConsumer = DEFAULT_OPTIONS;
  private PrintStream stdout = null;

  TestCompilerBuilder(TestState state, B builder, Backend backend) {
    super(state, builder);
    this.backend = backend;
    defaultLibrary = TestBase.runtimeJar(backend);
    programConsumer = TestBase.emptyConsumer(backend);
  }

  abstract T self();

  abstract CR internalCompile(
      B builder, Consumer<InternalOptions> optionsConsumer, Supplier<AndroidApp> app)
      throws CompilationFailedException;

  public T addOptionsModification(Consumer<InternalOptions> optionsConsumer) {
    if (optionsConsumer != null) {
      this.optionsConsumer = this.optionsConsumer.andThen(optionsConsumer);
    }
    return self();
  }

  public CR compile() throws CompilationFailedException {
    AndroidAppConsumers sink = new AndroidAppConsumers();
    builder.setProgramConsumer(sink.wrapProgramConsumer(programConsumer));
    builder.setMainDexListConsumer(mainDexListConsumer);
    if (defaultLibrary != null) {
      builder.addLibraryFiles(defaultLibrary);
    }
    if (backend == Backend.DEX && defaultMinApiLevel != null) {
      builder.setMinApiLevel(defaultMinApiLevel.getLevel());
    }
    PrintStream oldOut = System.out;
    try {
      if (stdout != null) {
        System.setOut(stdout);
      }
      return internalCompile(builder, optionsConsumer, Suppliers.memoize(sink::build));
    } finally {
      if (stdout != null) {
        System.setOut(oldOut);
      }
    }
  }

  @Override
  public RR run(String mainClass) throws IOException, CompilationFailedException {
    return compile().run(mainClass);
  }

  @Override
  public DebugTestConfig debugConfig() {
    // Rethrow exceptions since debug config is usually used in a delayed wrapper which
    // does not declare exceptions.
    try {
      return compile().debugConfig();
    } catch (CompilationFailedException e) {
      throw new RuntimeException(e);
    }
  }

  public T setMode(CompilationMode mode) {
    builder.setMode(mode);
    return self();
  }

  public T debug() {
    return setMode(CompilationMode.DEBUG);
  }

  public T release() {
    return setMode(CompilationMode.RELEASE);
  }

  public T setMinApi(AndroidApiLevel minApiLevel) {
    // Should we ignore min-api calls when backend == CF?
    this.defaultMinApiLevel = null;
    builder.setMinApiLevel(minApiLevel.getLevel());
    return self();
  }

  public T setProgramConsumer(ProgramConsumer programConsumer) {
    assert programConsumer != null;
    this.programConsumer = programConsumer;
    return self();
  }

  public T setMainDexListConsumer(StringConsumer consumer) {
    assert consumer != null;
    this.mainDexListConsumer = consumer;
    return self();
  }

  @Override
  public T addLibraryFiles(Collection<Path> files) {
    defaultLibrary = null;
    return super.addLibraryFiles(files);
  }

  public T noDesugaring() {
    builder.setDisableDesugaring(true);
    return self();
  }

  public T redirectStdOut(PrintStream printStream) {
    assert stdout == null;
    stdout = printStream;
    return self();
  }
}
