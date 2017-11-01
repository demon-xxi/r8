// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import java.io.IOException;
import java.util.Set;

/**
 * Interface used by D8 and R8 to output the generated results.
 * <p>
 * Implementations must be able to cope with concurrent calls to these methods and non-determinism
 * in the order of calls. For example, {@link #writeDexFile} may be called concurrently and in any
 * order. It is the responsibility of an implementation to ensure deterministic output, if such is
 * desired.
 * <p>
 * The two versions of {@link #writeDexFile} are not used simulatneously. Normally, D8 and R8 will
 * write output using the {@link #writeDexFile(byte[], Set, int)} method. Only if instructed to
 * generated a DEX file per class ({@link com.android.tools.r8.utils.OutputMode#FilePerInputClass})
 * will D8 invoke {@link #writeDexFile(byte[], Set, String)} to generate a corresponding DEX file.
 * <p>
 * See {@link com.android.tools.r8.utils.ForwardingOutputSink} for a helper class that can be used
 * to wrap an existing sink and override only certain behavior.
 */
public interface OutputSink {

  /**
   * Write a DEX file containing the definitions for all classes in classDescriptors into the DEX
   * file numbered as fileId.
   * <p>
   * This is the equivalent to writing out the files classes.dex, classes2.dex, etc., where fileId
   * gives the current file count.
   * <p>
   * Files are not necessarily generated in order and files might be written concurrently. However,
   * for each fileId only one file is ever written. If this method is called, the other writeDexFile
   * and writeClassFile methods will not be called.
   */
  void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId) throws IOException;

  /**
   * Write a DEX file that contains the class primaryClassName and its companion classes.
   * <p>
   * This is equivalent to writing out the file com/foo/bar/Test.dex given a primaryClassName of
   * com.foo.bar.Test.
   * <p>
   * There is no guaranteed order and files might be written concurrently. However, for each
   * primaryClassName only one file is ever written.
   * <p>
   * This method is only invoked by D8 and only if compiling each class into its own dex file, e.g.,
   * for incremental compilation. If this method is called, the other writeDexFile and
   * writeClassFile methods will not be called.
   */
  void writeDexFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException;

  /**
   * Write a Java classfile that contains the class primaryClassName and its companion classes.
   * <p>
   * This is equivalent to writing out the file com/foo/bar/Test.class given a primaryClassName of
   * com.foo.bar.Test.
   * <p>
   * There is no guaranteed order and files might be written concurrently. However, for each
   * primaryClassName only one file is ever written.
   * <p>
   * This method is only invoked by R8 and only if compiling to Java bytecode. If this method is
   * called, the other writeDexFile and writeClassFile methods will not be called.
   */
  void writeClassFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException;

  /**
   * Provides the raw bytes that would be generated for the <code>-printusage</code> flag.
   * <p>
   * This method is only invoked by R8 and only if R8 is instructed to generate printusage
   * information.
   */
  void writePrintUsedInformation(byte[] contents) throws IOException;

  /**
   * Provides the raw bytes that would be generated for the <code>-printmapping</code> flag.
   * <p>
   * This method is only invoked by R8 and only if R8 is instructed to generate a proguard map and
   * if such map is non-empty.
   */
  void writeProguardMapFile(byte[] contents) throws IOException;

  /**
   * Provides the raw bytes that would be generated for the <code>-printseeds</code> flag.
   * <p>
   * This method is only invoked by R8 and only if R8 is instructed to generate seeds information.
   */
  void writeProguardSeedsFile(byte[] contents) throws IOException;

  /**
   * Provides the raw bytes that would be generated by R8 or an R8-based helper tool when instructed
   * to generate a main-dex list.
   * <p>
   * This method is only invoked by R8 or R8-based tools and only if R8 is instructed to generate
   * a main-dex list.
   */
  void writeMainDexListFile(byte[] contents) throws IOException;

  /**
   * Closes the output sink.
   * <p>
   * This method is invokes once all output has been generated.
   */
  void close() throws IOException;
}