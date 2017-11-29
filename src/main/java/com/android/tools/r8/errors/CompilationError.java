// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.Location;
import com.android.tools.r8.origin.Origin;

/**
 * Exception to signal an compilation error.
 * <p>
 * This is always an expected error and considered a user input issue. A user-understandable message
 * must be provided.
 */
public class CompilationError extends RuntimeException implements Diagnostic {

  private final Location location;
  public CompilationError(String message) {
    this(message, Location.UNKNOWN);
  }

  public CompilationError(String message, Throwable cause) {
    this(message, cause, Location.UNKNOWN);
  }

  public CompilationError(String message, Location location) {
    this(message, null, location);
  }

  public CompilationError(String message, Origin origin) {
    this(message, new Location(origin));
  }

  public CompilationError(String message, Throwable cause, Location location) {
    super(message, cause);
    this.location = location;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public String getDiagnosticMessage() {
    return getMessage();
  }
}