// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.cf.CfPrinter;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.If;
import com.android.tools.r8.ir.code.If.Type;
import com.android.tools.r8.ir.code.ValueType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfIfCmp extends CfInstruction {

  private final If.Type kind;
  private final ValueType type;
  private final CfLabel target;

  public CfIfCmp(If.Type kind, ValueType type, CfLabel target) {
    this.kind = kind;
    this.type = type;
    this.target = target;
  }

  public Type getKind() {
    return kind;
  }

  public ValueType getType() {
    return type;
  }

  public CfLabel getTarget() {
    return target;
  }

  public int getOpcode() {
    switch (kind) {
      case EQ:
        return type.isObject() ? Opcodes.IF_ACMPEQ : Opcodes.IF_ICMPEQ;
      case GE:
        return Opcodes.IF_ICMPGE;
      case GT:
        return Opcodes.IF_ICMPGT;
      case LE:
        return Opcodes.IF_ICMPLE;
      case LT:
        return Opcodes.IF_ICMPLT;
      case NE:
        return type.isObject() ? Opcodes.IF_ACMPNE : Opcodes.IF_ICMPNE;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void write(MethodVisitor visitor) {
    visitor.visitJumpInsn(getOpcode(), target.getLabel());
  }
}