// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.DescriptorUtils.javaTypeToDescriptorIfValidJavaType;

import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemBasedString;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.ConstString;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.Streams;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class IdentifierNameStringMarker {
  private final AppInfoWithLiveness appInfo;
  private final DexItemFactory dexItemFactory;
  private final Set<DexItem> identifierNameStrings;

  public IdentifierNameStringMarker(AppInfoWithLiveness appInfo) {
    this.appInfo = appInfo;
    this.dexItemFactory = appInfo.dexItemFactory;
    this.identifierNameStrings = appInfo.identifierNameStrings;
  }

  public void decoupleIdentifierNameStringsInFields() {
    for (DexProgramClass clazz : appInfo.classes()) {
      clazz.forEachField(this::decoupleIdentifierNameStringInField);
    }
  }

  private void decoupleIdentifierNameStringInField(DexEncodedField encodedField) {
    if (!identifierNameStrings.contains(encodedField.field)) {
      return;
    }
    if (!(encodedField.staticValue instanceof DexValueString)) {
      return;
    }
    DexString original = ((DexValueString) encodedField.staticValue).getValue();
    String maybeDescriptor = javaTypeToDescriptorIfValidJavaType(original.toString());
    if (maybeDescriptor == null) {
      return;
    }
    DexType type = dexItemFactory.createType(maybeDescriptor);
    DexItemBasedString typeString = dexItemFactory.createItemBasedString(type);
    encodedField.staticValue = new DexValueString(typeString);
  }

  public void decoupleIdentifierNameStringsInMethod(DexEncodedMethod encodedMethod, IRCode code) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instruction.isStaticPut()) {
          StaticPut staticPut = instruction.asStaticPut();
          DexField field = staticPut.getField();
          if (identifierNameStrings.contains(field)) {
            Value in = staticPut.inValue();
            Value newIn = decoupleTypeIdentifierIfNecessary(code, iterator, staticPut, in);
            if (newIn != in) {
              iterator.replaceCurrentInstruction(
                  new StaticPut(staticPut.getType(), newIn, field));
              encodedMethod.markUseIdentifierNameString();
            }
          }
        } else if (instruction.isInstancePut()) {
          InstancePut instancePut = instruction.asInstancePut();
          DexField field = instancePut.getField();
          if (identifierNameStrings.contains(field)) {
            Value in = instancePut.value();
            Value newIn = decoupleTypeIdentifierIfNecessary(code, iterator, instancePut, in);
            if (newIn != in) {
              List<Value> values = new ArrayList<>(2);
              values.add(newIn);
              values.add(instancePut.object());
              iterator.replaceCurrentInstruction(
                  new InstancePut(instancePut.getType(), values, field));
              encodedMethod.markUseIdentifierNameString();
            }
          }
        } else if (instruction.isInvokeMethod()) {
          InvokeMethod invoke = instruction.asInvokeMethod();
          DexMethod invokedMethod = invoke.getInvokedMethod();
          if (identifierNameStrings.contains(invokedMethod)) {
            List<Value> ins = invoke.arguments();
            Value[] changes = new Value [ins.size()];
            if (isReflectiveCase(invokedMethod.proto)) {
              Value in = ins.get(1);
              Value newIn = decoupleReflectiveMemberIdentifier(code, iterator, invoke, in);
              if (newIn != in) {
                changes[1] = newIn;
              }
            } else {
              for (int i = 0; i < ins.size(); i++) {
                Value in = ins.get(i);
                Value newIn = decoupleTypeIdentifierIfNecessary(code, iterator, invoke, in);
                if (newIn != in) {
                  changes[i] = newIn;
                }
              }
            }
            if (!Arrays.stream(changes).allMatch(Objects::isNull)) {
              List<Value> newIns =
                  Streams.mapWithIndex(
                      ins.stream(),
                      (in, index) -> changes[(int) index] != null ? changes[(int) index] : in)
                  .collect(Collectors.toList());
              iterator.replaceCurrentInstruction(
                  Invoke.create(
                      invoke.getType(),
                      invokedMethod,
                      invokedMethod.proto,
                      invoke.outValue(),
                      newIns));
              encodedMethod.markUseIdentifierNameString();
            }
          }
        }
      }
    }
  }

  private Value decoupleTypeIdentifierIfNecessary(
      IRCode code, InstructionListIterator iterator, Instruction base, Value in) {
    if (!in.isConstString()) {
      return in;
    }
    ConstString constString = in.getConstInstruction().asConstString();
    String maybeDescriptor = javaTypeToDescriptorIfValidJavaType(constString.getValue().toString());
    if (maybeDescriptor == null) {
      return in;
    }
    DexType type = dexItemFactory.createType(maybeDescriptor);
    DexItemBasedString typeString = dexItemFactory.createItemBasedString(type);
    // v_n <- "x.y.z" // in.definition
    // ...
    // ... <- ... v_n ..
    // ...
    // this.fld <- v_n // base
    //
    //   ~>
    //
    // ...
    // v_n' <- DexItemBasedString("Lx/y/z;") // decoupled
    // this.fld <- v_n' // base
    //
    // 1) Move the cursor back to $base
    iterator.previous();
    // 2) Add $decoupled just before $base
    Value newIn = code.createValue(in.outType(), in.getLocalInfo());
    ConstString decoupled = new ConstString(newIn, typeString);
    decoupled.setPosition(base.getPosition());
    iterator.add(decoupled);
    // 3) Restore the cursor
    iterator.next();
    return newIn;
  }

  private Value decoupleReflectiveMemberIdentifier(
    IRCode code, InstructionListIterator iterator, InvokeMethod invoke, Value in) {
    if (!in.isConstString()) {
      return in;
    }
    Value classValue = invoke.arguments().get(0);
    if (!classValue.isConstClass()) {
      return in;
    }
    DexType holderType = classValue.getConstInstruction().asConstClass().getValue();
    DexClass holder = appInfo.definitionFor(holderType);
    if (holder == null) {
      return in;
    }
    DexString dexString = in.getConstInstruction().asConstString().getValue();
    DexItemBasedString itemBasedString = null;
    int numOfParams = invoke.arguments().size();
    if (numOfParams == 2) {
      for (DexEncodedField encodedField : holder.staticFields()) {
        if (encodedField.field.name == dexString) {
          itemBasedString = dexItemFactory.createItemBasedString(encodedField.field);
          break;
        }
      }
      if (itemBasedString == null) {
        for (DexEncodedField encodedField : holder.instanceFields()) {
          if (encodedField.field.name == dexString) {
            itemBasedString = dexItemFactory.createItemBasedString(encodedField.field);
            break;
          }
        }
      }
    } else {
      assert numOfParams == 3;
      DexTypeList arguments = retrieveDexTypeListFromClassList(invoke, invoke.arguments().get(2));
      if (arguments != null) {
        for (DexEncodedMethod encodedMethod : holder.directMethods()) {
          if (encodedMethod.method.name == dexString
              && encodedMethod.method.proto.parameters.equals(arguments)) {
            itemBasedString = dexItemFactory.createItemBasedString(encodedMethod.method);
            break;
          }
        }
        if (itemBasedString == null) {
          for (DexEncodedMethod encodedMethod : holder.virtualMethods()) {
            if (encodedMethod.method.name == dexString
                && encodedMethod.method.proto.parameters.equals(arguments)) {
              itemBasedString = dexItemFactory.createItemBasedString(encodedMethod.method);
              break;
            }
          }
        }
      }
    }
    if (itemBasedString == null) {
      return in;
    }
    // 1) Move the cursor back to $base
    iterator.previous();
    // 2) Add $decoupled just before $base
    Value newIn = code.createValue(in.outType(), in.getLocalInfo());
    ConstString decoupled = new ConstString(newIn, itemBasedString);
    decoupled.setPosition(invoke.getPosition());
    iterator.add(decoupled);
    // 3) Restore the cursor
    iterator.next();
    return newIn;
  }

  private boolean isReflectiveCase(DexProto proto) {
    // (Class, String) -> java.lang.reflect.Field
    // (Class, String, Class[]) -> java.lang.reflect.Method
    int numOfParams = proto.parameters.size();
    if (numOfParams != 2 && numOfParams != 3) {
      return false;
    }
    if (numOfParams == 2) {
      if (proto.returnType.descriptor != dexItemFactory.fieldDescriptor) {
        return false;
      }
    } else {
      if (proto.returnType.descriptor != dexItemFactory.methodDescriptor) {
        return false;
      }
    }
    if (proto.parameters.values[0].descriptor != dexItemFactory.classDescriptor) {
      return false;
    }
    if (proto.parameters.values[1].descriptor != dexItemFactory.stringDescriptor) {
      return false;
    }
    if (numOfParams == 3) {
      if (proto.parameters.values[2].descriptor != dexItemFactory.classArrayDescriptor) {
        return false;
      }
    }
    return true;
  }

  /**
   * Visits all {@link ArrayPut}'s with the given {@param classListValue} as array and {@link Class}
   * as value. Then collects all corresponding {@link DexType}s so as to determine reflective cases.
   *
   * @param invoke the instruction that invokes a reflective method with -identifiernamestring rule
   * @param classListValue the register that holds an array of {@link Class}'s
   * @return a list of {@link DexType} that corresponds to const class in {@param classListValue}
   */
  private DexTypeList retrieveDexTypeListFromClassList(InvokeMethod invoke, Value classListValue) {
    // Make sure this Value refers to an array.
    if (!classListValue.definition.isInvokeNewArray()
        && !classListValue.definition.isNewArrayEmpty()) {
      return null;
    }
    // The only pattern we consider is: new Class[] { A.class, B.class, ... }, which looks like
    //   new-array va ...
    //   const-class vc ...
    //   const/4 vi ...
    //   aput-object vc va vi
    //   ... repeat putting const class into one location at a time ...
    //   invoke-static ... va // Use that array at {@param invoke}.
    BasicBlock block = classListValue.definition.getBlock();
    InstructionIterator iterator = block.iterator();
    iterator.nextUntil(instr -> instr == classListValue.definition);
    Set<Instruction> users = classListValue.definition.outValue().uniqueUsers();
    int maxIndex = -1;
    Map<Integer, DexType> typeMap = new Int2ObjectArrayMap<>();
    while (iterator.hasNext()) {
      Instruction instr = iterator.next();
      // Iterate the instructions up to the current {@param invoke}.
      if (instr == invoke) {
        break;
      }
      if (!users.contains(instr)) {
        continue;
      }
      // Any other kinds of users mean that elements could be escaped and altered.
      if (!instr.isArrayPut()) {
        return null;
      }
      ArrayPut arrayPut = instr.asArrayPut();
      assert arrayPut.array() == classListValue;
      // Ignore statically unknown index.
      if (!(arrayPut.source().isConstClass() && arrayPut.index().isConstNumber())) {
        return null;
      }
      int index = arrayPut.index().getConstInstruction().asConstNumber().getIntValue();
      // Filter out out-of-bound index or non-deterministic index.
      if (index < 0 || typeMap.containsKey(index)) {
        return null;
      }
      maxIndex = maxIndex < index ? index : maxIndex;
      DexType type = arrayPut.source().getConstInstruction().asConstClass().getValue();
      typeMap.put(index, type);
    }
    if (maxIndex < 0) {
      return DexTypeList.empty();
    }
    // Make sure we were able to collect *all* {@link ConstClass}'s.
    for (int i = 0; i <= maxIndex; i++) {
      if (!typeMap.containsKey(i)) {
        return null;
      }
    }
    DexType[] types = new DexType [maxIndex + 1];
    for (int i = 0; i <= maxIndex; i++) {
      types[i] = typeMap.get(i);
    }
    return new DexTypeList(types);
  }

}