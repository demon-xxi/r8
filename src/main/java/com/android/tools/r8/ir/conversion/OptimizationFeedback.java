// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.android.tools.r8.graph.ParameterUsagesInfo;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import java.util.BitSet;

public interface OptimizationFeedback {
  void methodReturnsArgument(DexEncodedMethod method, int argument);
  void methodReturnsConstant(DexEncodedMethod method, long value);
  void methodNeverReturnsNull(DexEncodedMethod method);
  void methodNeverReturnsNormally(DexEncodedMethod method);
  void markProcessed(DexEncodedMethod method, ConstraintWithTarget state);
  void markUseIdentifierNameString(DexEncodedMethod method);
  void markCheckNullReceiverBeforeAnySideEffect(DexEncodedMethod method, boolean mark);
  void markTriggerClassInitBeforeAnySideEffect(DexEncodedMethod method, boolean mark);
  void setClassInlinerEligibility(DexEncodedMethod method, ClassInlinerEligibility eligibility);
  void setTrivialInitializer(DexEncodedMethod method, TrivialInitializer info);
  void setInitializerEnablingJavaAssertions(DexEncodedMethod method);
  void setParameterUsages(DexEncodedMethod method, ParameterUsagesInfo parameterUsagesInfo);
  void setNonNullParamOrThrow(DexEncodedMethod method, BitSet facts);
  void setNonNullParamOnNormalExits(DexEncodedMethod method, BitSet facts);
}
