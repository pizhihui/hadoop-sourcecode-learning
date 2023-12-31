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

import org.apache.hadoop.io.*;

import java.io.*;
import java.net.*;

/**************************************************
 * A JobProfile is a MapReduce primitive.  Tracks a job,
 * whether living or dead.
 *
 * @author Mike Cafarella
 **************************************************/
public class JobProfile implements Writable {

  static {                                      // register a ctor
    WritableFactories.setFactory
      (JobProfile.class,
       new WritableFactory() {
         public Writable newInstance() { return new JobProfile(); }
       });
  }

  String user;
  String jobid;
  String jobFile;
  String url;
  String name;

  /**
   * Construct an empty {@link JobProfile}.
   */
  public JobProfile() {
  }

  /**
   * Construct a {@link JobProfile} the userid, jobid, 
   * job config-file, job-details url and job name. 
   * 
   * @param user userid of the person who submitted the job.
   * @param jobid id of the job.
   * @param jobFile job configuration file. 
   * @param url link to the web-ui for details of the job.
   * @param name user-specified job name.
   */
  public JobProfile(String user, String jobid, String jobFile, String url,
                    String name) {
    this.user = user;
    this.jobid = jobid;
    this.jobFile = jobFile;
    this.url = url;
    this.name = name;
  }

  /**
   * Get the user id.
   */
  public String getUser() {
    return user;
  }
    
  /**
   * Get the job id.
   */
  public String getJobId() {
    return jobid;
  }

  /**
   * Get the configuration file for the job.
   */
  public String getJobFile() {
    return jobFile;
  }

  /**
   * Get the link to the web-ui for details of the job.
   */
  public URL getURL() {
    try {
      return new URL(url.toString());
    } catch (IOException ie) {
      return null;
    }
  }

  /**
   * Get the user-specified job name.
   */
  public String getJobName() {
    return name;
  }
    
  ///////////////////////////////////////
  // Writable
  ///////////////////////////////////////
  public void write(DataOutput out) throws IOException {
    UTF8.writeString(out, jobid);
    UTF8.writeString(out, jobFile);
    UTF8.writeString(out, url);
    UTF8.writeString(out, user);
    UTF8.writeString(out, name);
  }
  public void readFields(DataInput in) throws IOException {
    this.jobid = UTF8.readString(in);
    this.jobFile = UTF8.readString(in);
    this.url = UTF8.readString(in);
    this.user = UTF8.readString(in);
    this.name = UTF8.readString(in);
  }
}


