// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package classmerging;

public class SubClassThatReferencesSuperMethod extends SuperClassWithReferencedMethod {

  @Override
  public String referencedMethod() {
    System.out.println("In referencedMethod on SubClassThatReferencesSuperMethod");
    System.out.println("Calling referencedMethod on SuperClassWithReferencedMethod with super");
    System.out.println("Got: " + super.referencedMethod());
    return "SubClassThatReferencesSuperMethod.referencedMethod()";
  }
}
