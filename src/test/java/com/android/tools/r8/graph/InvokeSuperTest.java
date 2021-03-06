// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.AsmTestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.VmTestRunner;
import com.android.tools.r8.VmTestRunner.IgnoreForRangeOfVmVersions;
import com.android.tools.r8.graph.invokesuper.Consumer;
import com.android.tools.r8.graph.invokesuper.InvokerClassDump;
import com.android.tools.r8.graph.invokesuper.InvokerClassFailingDump;
import com.android.tools.r8.graph.invokesuper.MainClass;
import com.android.tools.r8.graph.invokesuper.MainClassFailing;
import com.android.tools.r8.graph.invokesuper.SubLevel1;
import com.android.tools.r8.graph.invokesuper.SubLevel2;
import com.android.tools.r8.graph.invokesuper.SubclassOfInvokerClass;
import com.android.tools.r8.graph.invokesuper.Super;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VmTestRunner.class)
public class InvokeSuperTest extends AsmTestBase {

  @Test
  @IgnoreForRangeOfVmVersions(from = Version.V5_1_1, to = Version.V6_0_1)
  public void testInvokeSuperTargets() throws Exception {
    ensureSameOutput(MainClass.class.getCanonicalName(),
        ToolHelper.getClassAsBytes(MainClass.class),
        ToolHelper.getClassAsBytes(Consumer.class),
        ToolHelper.getClassAsBytes(Super.class),
        ToolHelper.getClassAsBytes(SubLevel1.class),
        ToolHelper.getClassAsBytes(SubLevel2.class),
        InvokerClassDump.dump(),
        ToolHelper.getClassAsBytes(SubclassOfInvokerClass.class));
  }

  @Test
  public void testInvokeSuperTargetsNonVerifying() throws Exception {
    ensureR8FailsWithCompilationError(MainClassFailing.class.getCanonicalName(),
        ToolHelper.getClassAsBytes(MainClassFailing.class),
        ToolHelper.getClassAsBytes(Consumer.class),
        ToolHelper.getClassAsBytes(Super.class),
        ToolHelper.getClassAsBytes(SubLevel1.class),
        ToolHelper.getClassAsBytes(SubLevel2.class),
        InvokerClassFailingDump.dump(),
        ToolHelper.getClassAsBytes(SubclassOfInvokerClass.class));
  }
}
