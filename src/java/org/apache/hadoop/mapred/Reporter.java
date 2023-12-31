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

import org.apache.hadoop.util.Progressable;

/** Passed to application code to permit alteration of status. */
public interface Reporter extends Progressable {
  
  /**
   * A constant of Reporter type that does nothing.
   */
  public static final Reporter NULL = new Reporter() {
      public void setStatus(String s) {
      }
      public void progress() throws IOException {
      }
      public void incrCounter(Enum key, long amount) {
      }
      public InputSplit getInputSplit() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("NULL reporter has no input");
      }
    };

  /**
   * Alter the application's status description.
   * 
   * @param status
   *          a brief description of the current status
   */
  public abstract void setStatus(String status) throws IOException;
  
  /**
   * Increments the counter identified by the key, which can be of
   * any enum type, by the specified amount.
   * @param key A value of any enum type
   * @param amount A non-negative amount by which the counter is to 
   * be incremented
   */
  public abstract void incrCounter(Enum key, long amount);
  
  /**
   * Get the InputSplit object for a map.
   * @return the input split that the map is reading from
   * @throws UnsupportedOperationException if called outside a mapper
   */
  public abstract InputSplit getInputSplit() 
    throws UnsupportedOperationException;
}
