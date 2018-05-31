// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.annotations.version2;

import static com.android.tools.r8.ir.desugar.annotations.CovariantReturnTypeAnnotationTransformerTest.CRT_NAME;
import static com.android.tools.r8.ir.desugar.annotations.CovariantReturnTypeAnnotationTransformerTest.PACKAGE_NAME;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

// Generated by running tools/asmifier.py on build/classes/test/com/android/tools/r8/ir/desugar/
// annotations/version2/B.class, removing the subpackage "version2" from all class names, and
// manually changing the name of the CovariantReturnType annotation to
// "Ldalvik/annotation/codegen/CovariantReturnType;".
public class BDump implements Opcodes {
  public static byte[] dump() throws Exception {

    ClassWriter cw = new ClassWriter(0);
    FieldVisitor fv;
    MethodVisitor mv;
    AnnotationVisitor av0;

    cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, PACKAGE_NAME + "/B", null, PACKAGE_NAME + "/A", null);

    {
      mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, PACKAGE_NAME + "/A", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      mv = cw.visitMethod(ACC_PUBLIC, "method", "()L" + PACKAGE_NAME + "/A;", null, null);
      {
        av0 = mv.visitAnnotation("L" + CRT_NAME + ";", false);
        av0.visit("returnType", Type.getType("L" + PACKAGE_NAME + "/B;"));
        av0.visit("presentAfter", new Integer(25));
        av0.visitEnd();
      }
      mv.visitCode();
      mv.visitTypeInsn(NEW, PACKAGE_NAME + "/B");
      mv.visitInsn(DUP);
      mv.visitMethodInsn(INVOKESPECIAL, PACKAGE_NAME + "/B", "<init>", "()V", false);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(2, 1);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }
}