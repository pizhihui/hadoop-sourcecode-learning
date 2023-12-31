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

package org.apache.hadoop.abacus;

import java.util.ArrayList;
import java.util.Map.Entry;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;

/** 
 * @deprecated
 * 
 * This class implements the common functionalities of 
 * the subclasses of ValueAggregatorDescriptor class.
 *
 */
public class ValueAggregatorBaseDescriptor implements ValueAggregatorDescriptor {

  static public final String UNIQ_VALUE_COUNT = "UniqValueCount";

  static public final String LONG_VALUE_SUM = "LongValueSum";

  static public final String DOUBLE_VALUE_SUM = "DoubleValueSum";

  static public final String VALUE_HISTOGRAM = "ValueHistogram";

  public String inputFile = null;

  private static class MyEntry implements Entry {
    Object key;

    Object val;

    public Object getKey() {
      return key;
    }

    public Object getValue() {
      return val;
    }

    public Object setValue(Object val) {
      this.val = val;
      return val;
    }

    public MyEntry(Object key, Object val) {
      this.key = key;
      this.val = val;
    }
  }

  /**
   * 
   * @param type the aggregation type
   * @param id the aggregation id
   * @param val the val associated with the id to be aggregated
   * @return an Entry whose key is the aggregation id prefixed with 
   * the aggregation type.
   */
  public static Entry generateEntry(String type, String id, Object val) {
    Text key = new Text(type + TYPE_SEPARATOR + id);
    return new MyEntry(key, val);
  }

  /**
   * 
   * @param type the aggregation type
   * @return a value aggregator of the given type.
   */
  static public ValueAggregator generateValueAggregator(String type) {
    ValueAggregator retv = null;
    if (type.compareToIgnoreCase(UNIQ_VALUE_COUNT) == 0) {
      retv = new UniqValueCount();
    } else if (type.compareToIgnoreCase(LONG_VALUE_SUM) == 0) {
      retv = new LongValueSum();
    } else if (type.compareToIgnoreCase(DOUBLE_VALUE_SUM) == 0) {
      retv = new DoubleValueSum();
    } else if (type.compareToIgnoreCase(VALUE_HISTOGRAM) == 0) {
      retv = new ValueHistogram();
    }
    return retv;
  }

  /**
   * Generate 1 or 2 aggregation-id/value pairs for the given key/value pair.
   * The first id will be of type LONG_VALUE_SUM, with "record_count" as
   * its aggregation id. If the input is a file split,
   * the second id of the same type will be generated too, with the file name 
   * as its aggregation id. This achieves the behavior of counting the total number
   * of records in the input data, and the number of records in each input file.
   * 
   * @param key
   *          input key
   * @param val
   *          input value
   * @return a list of aggregation id/value pairs. An aggregation id encodes an
   *         aggregation type which is used to guide the way to aggregate the
   *         value in the reduce/combiner phrase of an Abacus based job.
   */
  public ArrayList<Entry> generateKeyValPairs(Object key, Object val) {
    ArrayList<Entry> retv = new ArrayList<Entry>();
    String countType = LONG_VALUE_SUM;
    String id = "record_count";
    Entry e = generateEntry(countType, id, ONE);
    if (e != null) {
      retv.add(e);
    }
    if (this.inputFile != null) {
      e = generateEntry(countType, this.inputFile, ONE);
      if (e != null) {
        retv.add(e);
      }
    }
    return retv;
  }

  /**
   * get the input file name.
   * 
   * @param job a job configuration object
   */
  public void configure(JobConf job) {
    this.inputFile = job.get("map.input.file");
  }
}
