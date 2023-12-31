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

package org.apache.hadoop.contrib.utils.join;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;

/**
 * This class implements the main function for creating a map/reduce
 * job to join data of different sources. To create sucn a job, the 
 * user must implement a mapper class that extends DataJoinMapperBase class,
 * and a reducer class that extends DataJoinReducerBase. 
 * 
 * @author runping
 *
 */
public class DataJoinJob {

  public static Class getClassByName(String className) {
    Class retv = null;
    try {
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      retv = Class.forName(className, true, classLoader);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return retv;
  }

  public static JobConf createDataJoinJob(String args[]) throws IOException {

    String inputDir = args[0];
    String outputDir = args[1];
    int numOfReducers = Integer.parseInt(args[2]);
    Class mapper = getClassByName(args[3]);
    Class reducer = getClassByName(args[4]);
    Class mapoutputValueClass = getClassByName(args[5]);
    Class outputFormat = TextOutputFormat.class;
    Class outputValueClass = Text.class;
    if (args[6].compareToIgnoreCase("text") != 0) {
      System.out.println("Using SequenceFileOutputFormat: " + args[6]);
      outputFormat = SequenceFileOutputFormat.class;
      outputValueClass = getClassByName(args[6]);
    } else {
      System.out.println("Using TextOutputFormat: " + args[6]);
    }
    long maxNumOfValuesPerGroup = 100;
    String jobName = "";
    if (args.length > 7) {
      maxNumOfValuesPerGroup = Long.parseLong(args[7]);
    }
    if (args.length > 8) {
      jobName = args[8];
    }
    Configuration defaults = new Configuration();
    JobConf job = new JobConf(defaults, DataJoinJob.class);
    job.setJobName("DataJoinJob: " + jobName);

    FileSystem fs = FileSystem.get(defaults);
    fs.delete(new Path(outputDir));
    String[] inputDirsSpecs = inputDir.split(",");
    for (int i = 0; i < inputDirsSpecs.length; i++) {
      String spec = inputDirsSpecs[i];
      job.addInputPath(new Path(spec));
    }

    job.setInputFormat(SequenceFileInputFormat.class);

    job.setMapperClass(mapper);
    job.setOutputPath(new Path(outputDir));
    job.setOutputFormat(outputFormat);
    SequenceFile.setCompressionType(job,
                                    SequenceFile.CompressionType.BLOCK);
    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(mapoutputValueClass);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(outputValueClass);
    job.setReducerClass(reducer);

    job.setNumMapTasks(1);
    job.setNumReduceTasks(numOfReducers);
    job.setLong("ultjoin.maxNumOfValuesPerGroup",
                maxNumOfValuesPerGroup);
    job.set("mapred.child.java.opts", "-Xmx1024m");
    job.setKeepFailedTaskFiles(true);
    return job;
  }

  /**
   * Submit/run a map/reduce job.
   * 
   * @param job
   * @return true for success
   * @throws IOException
   */
  public static boolean runJob(JobConf job) throws IOException {
    JobClient jc = new JobClient(job);
    boolean sucess = true;
    RunningJob running = null;
    try {
      running = jc.submitJob(job);
      String jobId = running.getJobID();
      System.out.println("Job " + jobId + " is submitted");
      while (!running.isComplete()) {
        System.out.println("Job " + jobId + " is still running.");
        try {
          Thread.sleep(60000);
        } catch (InterruptedException e) {
        }
        running = jc.getJob(jobId);
      }
      sucess = running.isSuccessful();
    } finally {
      if (!sucess && (running != null)) {
        running.killJob();
      }
      jc.close();
    }
    return sucess;
  }

  /**
   * @param args
   */
  public static void main(String[] args) {
    boolean success;
    if (args.length < 7 || args.length > 9) {
      System.out.println("usage: DataJoinJob " + "inputdirs outputdir "
                         + "numofParts " + "mapper_class " + "reducer_class "
                         + "map_output_value_class "
                         + "output_value_class [maxNumOfValuesPerGroup [descriptionOfJob]]]");
      System.exit(-1);
    }

    try {
      JobConf job = DataJoinJob.createDataJoinJob(args);
      success = DataJoinJob.runJob(job);
      if (!success) {
        System.out.println("Job failed");
      }
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }
}
