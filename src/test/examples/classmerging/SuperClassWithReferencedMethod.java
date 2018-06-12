// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package classmerging;

public class SuperClassWithReferencedMethod {

  public String referencedMethod() {
    System.out.println("In referencedMethod on SuperClassWithReferencedMethod");
    return "SuperClassWithReferencedMethod.referencedMethod()";
  }
}
