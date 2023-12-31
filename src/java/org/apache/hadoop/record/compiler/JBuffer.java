/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.record.compiler;

import org.apache.hadoop.record.compiler.JCompType.CCompType;

/**
 * Code generator for "buffer" type.
 *
 * @author Milind Bhandarkar
 */
public class JBuffer extends JCompType {
  
  class JavaBuffer extends JavaCompType {
    
    JavaBuffer() {
      super("org.apache.hadoop.record.Buffer", "Buffer", "org.apache.hadoop.record.Buffer");
    }
    
    void genCompareTo(CodeBuffer cb, String fname, String other) {
      cb.append("ret = "+fname+".compareTo("+other+");\n");
    }
    
    void genEquals(CodeBuffer cb, String fname, String peer) {
      cb.append("ret = "+fname+".equals("+peer+");\n");
    }
    
    void genHashCode(CodeBuffer cb, String fname) {
      cb.append("ret = "+fname+".hashCode();\n");
    }
    
    void genSlurpBytes(CodeBuffer cb, String b, String s, String l) {
      cb.append("{\n");
      cb.append("int i = org.apache.hadoop.record.Utils.readVInt("+
                b+", "+s+");\n");
      cb.append("int z = org.apache.hadoop.record.Utils.getVIntSize(i);\n");
      cb.append(s+" += z+i; "+l+" -= (z+i);\n");
      cb.append("}\n");
    }
    
    void genCompareBytes(CodeBuffer cb) {
      cb.append("{\n");
      cb.append("int i1 = org.apache.hadoop.record.Utils.readVInt(b1, s1);\n");
      cb.append("int i2 = org.apache.hadoop.record.Utils.readVInt(b2, s2);\n");
      cb.append("int z1 = org.apache.hadoop.record.Utils.getVIntSize(i1);\n");
      cb.append("int z2 = org.apache.hadoop.record.Utils.getVIntSize(i2);\n");
      cb.append("s1+=z1; s2+=z2; l1-=z1; l2-=z2;\n");
      cb.append("int r1 = org.apache.hadoop.record.Utils.compareBytes(b1,s1,i1,b2,s2,i2);\n");
      cb.append("if (r1 != 0) { return (r1<0)?-1:0; }\n");
      cb.append("s1+=i1; s2+=i2; l1-=i1; l1-=i2;\n");
      cb.append("}\n");
    }
  }
  
  class CppBuffer extends CppCompType {
    
    CppBuffer() {
      super(" ::std::string");
    }
    
    void genGetSet(CodeBuffer cb, String fname) {
      cb.append("virtual const "+getType()+"& get"+toCamelCase(fname)+"() const {\n");
      cb.append("return "+fname+";\n");
      cb.append("}\n");
      cb.append("virtual "+getType()+"& get"+toCamelCase(fname)+"() {\n");
      cb.append("return "+fname+";\n");
      cb.append("}\n");
    }
  }
  /** Creates a new instance of JBuffer */
  public JBuffer() {
    setJavaType(new JavaBuffer());
    setCppType(new CppBuffer());
    setCType(new CCompType());
  }
  
  String getSignature() {
    return "B";
  }
}
