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

package org.apache.hadoop.mapred;

import java.io.IOException;
import java.io.DataInput;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

/** Reads key/value pairs from an input file {@link FileSplit}.
 * Implemented by {@link InputFormat} implementations. */
public interface RecordReader {
  /** Reads the next key/value pair.
   *
   * @param key the key to read data into
   * @param value the value to read data into
   * @return true iff a key/value was read, false if at EOF
   *
   * @see Writable#readFields(DataInput)
   */      
  boolean next(Writable key, Writable value) throws IOException;
  
  /**
   * Create an object of the appropriate type to be used as a key.
   * @return a new key object
   */
  WritableComparable createKey();
  
  /**
   * Create an object of the appropriate type to be used as the value.
   * @return a new value object
   */
  Writable createValue();

  /** Returns the current position in the input. */
  long getPos() throws IOException;

  /** Close this to future operations.*/ 
  public void close() throws IOException;

  /**
   * How far has the reader gone through the input.
   * @return progress from 0.0 to 1.0
   * @throws IOException
   */
  float getProgress() throws IOException;
}
