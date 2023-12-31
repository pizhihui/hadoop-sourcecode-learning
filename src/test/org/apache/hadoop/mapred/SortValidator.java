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

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapred.lib.HashPartitioner;
import org.apache.hadoop.fs.*;

/**
 * A set of utilities to validate the <b>sort</b> of the map-reduce framework.
 * This utility program has 2 main parts:
 * 1. Checking the records' statistics
 *   a) Validates the no. of bytes and records in sort's input & output. 
 *   b) Validates the xor of the md5's of each key/value pair.
 *   c) Ensures same key/value is present in both input and output.
 * 2. Check individual records  to ensure each record is present in both
 *    the input and the output of the sort (expensive on large data-sets). 
 *    
 * To run: bin/hadoop jar build/hadoop-examples.jar sortvalidate
 *            [-m <i>maps</i>] [-r <i>reduces</i>] [-deep] 
 *            -sortInput <i>sort-in-dir</i> -sortOutput <i>sort-out-dir</i> 
 *
 * @author Arun C Murthy
 */
public class SortValidator {

  static private final IntWritable sortInput = new IntWritable(1); 
  static private final IntWritable sortOutput = new IntWritable(2); 

  static void printUsage() {
    System.err.println("sortvalidate [-m <maps>] [-r <reduces>] [-deep] " +
                       "-sortInput <sort-input-dir> -sortOutput <sort-output-dir>");
    System.exit(1);
  }

  static private IntWritable deduceInputFile(JobConf job) {
    Path[] inputPaths = job.getInputPaths();
    String inputFile = null; 
    try {
      inputFile = new URI(job.get("map.input.file")).getPath();
    } catch (URISyntaxException urise) {
      System.err.println("Caught: " + urise);
      System.exit(-1);
    }
    
    // value == one for sort-input; value == two for sort-output
    return (inputFile.startsWith(inputPaths[0].toString()+"/")) ? 
      sortInput : sortOutput;
  }
  
  static private byte[] pair(BytesWritable a, BytesWritable b) {
    byte[] pairData = new byte[a.getSize()+ b.getSize()];
    System.arraycopy(a.get(), 0, pairData, 0, a.getSize());
    System.arraycopy(b.get(), 0, pairData, a.getSize(), b.getSize());
    return pairData;
  }

  private static final PathFilter sortPathsFilter = new PathFilter() {
    public boolean accept(Path path) {
      return (path.getName().startsWith("part-"));
    }
  };
  
  /**
   * A simple map-reduce job which checks consistency of the
   * MapReduce framework's sort by checking:
   * a) Records are sorted correctly
   * b) Keys are partitioned correctly
   * c) The input and output have same no. of bytes and records.
   * d) The input and output have the correct 'checksum' by xor'ing 
   *    the md5 of each record.
   *    
   * @author Arun C Murthy
   */
  public static class RecordStatsChecker {
    
    public static class RecordStatsWritable implements Writable {
      private long bytes = 0;
      private long records = 0;
      private int checksum = 0;
      
      public RecordStatsWritable() {}
      
      public RecordStatsWritable(long bytes, long records, int checksum) {
        this.bytes = bytes;
        this.records = records;
        this.checksum = checksum;
      }
      
      public void write(DataOutput out) throws IOException {
        WritableUtils.writeVLong(out, bytes);
        WritableUtils.writeVLong(out, records);
        WritableUtils.writeVInt(out, checksum);
      }

      public void readFields(DataInput in) throws IOException {
        bytes = WritableUtils.readVLong(in);
        records = WritableUtils.readVLong(in);
        checksum = WritableUtils.readVInt(in);
      }
      
      public long getBytes() { return bytes; }
      public long getRecords() { return records; }
      public int getChecksum() { return checksum; }
    }
    
    public static class Map extends MapReduceBase implements Mapper {
      private IntWritable key = null;
      private BytesWritable prevKey = null;
      private Partitioner partitioner = null;
      private int partition = -1;
      private int noSortReducers = -1;
      private long recordId = -1;

      public void configure(JobConf job) {
        // 'key' == sortInput for sort-input; key == sortOutput for sort-output
        key = deduceInputFile(job);
        
        if (key == sortOutput) {
          partitioner = new HashPartitioner();
          
          // Figure the 'current' partition and no. of reduces of the 'sort'
          try {
            URI inputURI = new URI(job.get("map.input.file"));
            String inputFile = inputURI.getPath();
            partition = Integer.valueOf(
                                        inputFile.substring(inputFile.lastIndexOf("part")+5)
                                        ).intValue();
            noSortReducers = job.getInt("sortvalidate.sort.reduce.tasks", -1);
          } catch (Exception e) {
            System.err.println("Caught: " + e);
            System.exit(-1);
          }
        }
      }
      
      public void map(WritableComparable key, 
                      Writable value,
                      OutputCollector output, 
                      Reporter reporter) throws IOException {
        BytesWritable bwKey = (BytesWritable)key;
        BytesWritable bwValue = (BytesWritable)value;
        ++recordId;
        
        if (this.key == sortOutput) {
          // Check if keys are 'sorted' if this  
          // record is from sort's output
          if (prevKey == null) {
            prevKey = bwKey;
          } else {
            if (prevKey.compareTo(bwKey) > 0) {
              throw new IOException("The 'map-reduce' framework wrongly classifed"
                                    + "(" + prevKey + ") > (" + bwKey + ") for record# " 
                                    + recordId); 
            }
            prevKey = bwKey;
          }

          // Check if the sorted output is 'partitioned' right
          int keyPartition = 
            partitioner.getPartition(bwKey, bwValue, noSortReducers);
          if (partition != keyPartition) {
            throw new IOException("Partitions do not match for record# " + 
                                  recordId + " ! - '" + partition + "' v/s '" + 
                                  keyPartition + "'");
          }
        }

        int keyValueChecksum = 
          (WritableComparator.hashBytes(bwKey.get(), bwKey.getSize()) ^
           WritableComparator.hashBytes(bwValue.get(), bwValue.getSize()));

        // output (this.key, record-stats)
        output.collect(this.key, new RecordStatsWritable(
                                                         (bwKey.getSize()+bwValue.getSize()), 1, keyValueChecksum));
      }
    }
    
    public static class Reduce extends MapReduceBase implements Reducer {
      public void reduce(WritableComparable key, Iterator values,
                         OutputCollector output, 
                         Reporter reporter) throws IOException {
        long bytes = 0;
        long records = 0;
        int xor = 0;
        while (values.hasNext()) {
          RecordStatsWritable stats = ((RecordStatsWritable)values.next());
          bytes += stats.getBytes();
          records += stats.getRecords();
          xor ^= stats.getChecksum(); 
        }
        
        output.collect(key, new RecordStatsWritable(bytes, records, xor));
      }
    }
    
    public static class NonSplitableSequenceFileInputFormat 
      extends SequenceFileInputFormat {
      protected boolean isSplitable(FileSystem fs, Path filename) {
        return false;
      }
    }
    
    static void checkRecords(Configuration defaults, 
                             Path sortInput, Path sortOutput) throws IOException {
      FileSystem fs = FileSystem.get(defaults);
      JobConf jobConf = new JobConf(defaults, RecordStatsChecker.class);
      jobConf.setJobName("sortvalidate-recordstats-checker");

      int noSortReduceTasks = 
        fs.listPaths(sortOutput, sortPathsFilter).length;
      jobConf.setInt("sortvalidate.sort.reduce.tasks", noSortReduceTasks);
      int noSortInputpaths = fs.listPaths(sortInput).length;

      jobConf.setInputFormat(NonSplitableSequenceFileInputFormat.class);
      jobConf.setOutputFormat(SequenceFileOutputFormat.class);
      
      jobConf.setOutputKeyClass(IntWritable.class);
      jobConf.setOutputValueClass(RecordStatsChecker.RecordStatsWritable.class);
      
      jobConf.setMapperClass(Map.class);
      jobConf.setCombinerClass(Reduce.class);
      jobConf.setReducerClass(Reduce.class);
      
      jobConf.setNumMapTasks(noSortReduceTasks);
      jobConf.setNumReduceTasks(1);

      jobConf.setInputPath(sortInput);
      jobConf.addInputPath(sortOutput);
      Path outputPath = new Path("/tmp/sortvalidate/recordstatschecker");
      if (fs.exists(outputPath)) {
        fs.delete(outputPath);
      }
      jobConf.setOutputPath(outputPath);
      
      // Uncomment to run locally in a single process
      //job_conf.set("mapred.job.tracker", "local");
      
      System.out.println("\nSortValidator.RecordStatsChecker: Validate sort " +
                         "from " + jobConf.getInputPaths()[0] + " (" + 
                         noSortInputpaths + " files), " + 
                         jobConf.getInputPaths()[1] + " (" + noSortReduceTasks + 
                         " files) into " + jobConf.getOutputPath() + 
                         " with 1 reducer.");
      Date startTime = new Date();
      System.out.println("Job started: " + startTime);
      JobClient.runJob(jobConf);
      Date end_time = new Date();
      System.out.println("Job ended: " + end_time);
      System.out.println("The job took " + 
                         (end_time.getTime() - startTime.getTime()) /1000 + " seconds.");
      
      // Check to ensure that the statistics of the 
      // framework's sort-input and sort-output match
      SequenceFile.Reader stats = new SequenceFile.Reader(fs, 
                                                          new Path(outputPath, "part-00000"), defaults);
      IntWritable k1 = new IntWritable();
      IntWritable k2 = new IntWritable();
      RecordStatsWritable v1 = new RecordStatsWritable();
      RecordStatsWritable v2 = new RecordStatsWritable();
      if (!stats.next(k1, v1)) {
        throw new IOException("Failed to read record #1 from reduce's output");
      }
      if (!stats.next(k2, v2)) {
        throw new IOException("Failed to read record #2 from reduce's output");
      }

      if ((v1.getBytes() != v2.getBytes()) || (v1.getRecords() != v2.getRecords()) || 
          v1.getChecksum() != v2.getChecksum()) {
        throw new IOException("(" + 
                              v1.getBytes() + ", " + v1.getRecords() + ", " + v1.getChecksum() + ") v/s (" +
                              v2.getBytes() + ", " + v2.getRecords() + ", " + v2.getChecksum() + ")");
      }
    }

  }
  
  /**
   * A simple map-reduce task to check if the input and the output
   * of the framework's sort is consistent by ensuring each record 
   * is present in both the input and the output.
   * 
   * @author Arun C   Murthy
   */
  public static class RecordChecker {
    
    public static class Map extends MapReduceBase implements Mapper {
      private IntWritable value = null;
      
      public void configure(JobConf job) {
        // value == one for sort-input; value == two for sort-output
        value = deduceInputFile(job);
      }
      
      public void map(WritableComparable key, 
                      Writable value,
                      OutputCollector output, 
                      Reporter reporter) throws IOException {
        // newKey = (key, value)
        BytesWritable keyValue = 
          new BytesWritable(pair((BytesWritable)key, (BytesWritable)value));
    
        // output (newKey, value)
        output.collect(keyValue, this.value);
      }
    }
    
    public static class Reduce extends MapReduceBase implements Reducer {
      public void reduce(WritableComparable key, Iterator values,
                         OutputCollector output, 
                         Reporter reporter) throws IOException {
        int ones = 0;
        int twos = 0;
        while (values.hasNext()) {
          IntWritable count = ((IntWritable) values.next()); 
          if (count.equals(sortInput)) {
            ++ones;
          } else if (count.equals(sortOutput)) {
            ++twos;
          } else {
            throw new IOException("Invalid 'value' of " + count.get() + 
                                  " for (key,value): " + key.toString());
          }
        }
        
        // Check to ensure there are equal no. of ones and twos
        if (ones != twos) {
          throw new IOException("Illegal ('one', 'two'): (" + ones + ", " + twos +
                                ") for (key, value): " + key.toString());
        }
      }
    }
    
    static void checkRecords(Configuration defaults, int noMaps, int noReduces,
                             Path sortInput, Path sortOutput) throws IOException {
      JobConf jobConf = new JobConf(defaults, RecordChecker.class);
      jobConf.setJobName("sortvalidate-record-checker");
      
      jobConf.setInputFormat(SequenceFileInputFormat.class);
      jobConf.setOutputFormat(SequenceFileOutputFormat.class);
      
      jobConf.setOutputKeyClass(BytesWritable.class);
      jobConf.setOutputValueClass(IntWritable.class);
      
      jobConf.setMapperClass(Map.class);        
      jobConf.setReducerClass(Reduce.class);
      
      JobClient client = new JobClient(jobConf);
      ClusterStatus cluster = client.getClusterStatus();
      if (noMaps == -1) {
        noMaps = cluster.getTaskTrackers() * 
          jobConf.getInt("test.sortvalidate.maps_per_host", 10);
      }
      if (noReduces == -1) {
        noReduces = cluster.getTaskTrackers() * 
          jobConf.getInt("test.sortvalidate.reduces_per_host", 
                         cluster.getMaxTasks());
      }
      jobConf.setNumMapTasks(noMaps);
      jobConf.setNumReduceTasks(noReduces);
      
      jobConf.setInputPath(sortInput);
      jobConf.addInputPath(sortOutput);
      Path outputPath = new Path("/tmp/sortvalidate/recordchecker");
      FileSystem fs = FileSystem.get(defaults);
      if (fs.exists(outputPath)) {
        fs.delete(outputPath);
      }
      jobConf.setOutputPath(outputPath);
      
      // Uncomment to run locally in a single process
      //job_conf.set("mapred.job.tracker", "local");
      
      System.out.println("\nSortValidator.RecordChecker: Running on " +
                         cluster.getTaskTrackers() +
                         " nodes to validate sort from " + jobConf.getInputPaths()[0] + ", " + 
                         jobConf.getInputPaths()[1] + " into " + jobConf.getOutputPath() + 
                         " with " + noReduces + " reduces.");
      Date startTime = new Date();
      System.out.println("Job started: " + startTime);
      JobClient.runJob(jobConf);
      Date end_time = new Date();
      System.out.println("Job ended: " + end_time);
      System.out.println("The job took " + 
                         (end_time.getTime() - startTime.getTime()) /1000 + " seconds.");
    }
  }

  
  /**
   * The main driver for sort program.
   * Invoke this method to submit the map/reduce job.
   * @throws IOException When there is communication problems with the 
   *                     job tracker.
   */
  public static void main(String[] args) throws IOException {
    Configuration defaults = new Configuration();
    
    int noMaps = -1, noReduces = -1;
    Path sortInput = null, sortOutput = null;
    boolean deepTest = false;
    for(int i=0; i < args.length; ++i) {
      try {
        if ("-m".equals(args[i])) {
          noMaps = Integer.parseInt(args[++i]);
        } else if ("-r".equals(args[i])) {
          noReduces = Integer.parseInt(args[++i]);
        } else if ("-sortInput".equals(args[i])){
          sortInput = new Path(args[++i]);
        } else if ("-sortOutput".equals(args[i])){
          sortOutput = new Path(args[++i]);
        } else if ("-deep".equals(args[i])) {
          deepTest = true;
        } else {
          printUsage();
        }
      } catch (NumberFormatException except) {
        System.err.println("ERROR: Integer expected instead of " + args[i]);
        printUsage();
      } catch (ArrayIndexOutOfBoundsException except) {
        System.err.println("ERROR: Required parameter missing from " +
                           args[i-1]);
        printUsage(); // exits
      }
    }
    
    // Sanity check
    if (sortInput == null || sortOutput == null) {
      printUsage();
    }

    // Check if the records are consistent and sorted correctly
    RecordStatsChecker.checkRecords(defaults, sortInput, sortOutput);

    // Check if the same records are present in sort's inputs & outputs
    if (deepTest) {
      RecordChecker.checkRecords(defaults, noMaps, noReduces, sortInput, 
                                 sortOutput);
    }
    
    System.out.println("\nSUCCESS! Validated the MapReduce framework's 'sort'" +
                       " successfully.");
  }
  
}
