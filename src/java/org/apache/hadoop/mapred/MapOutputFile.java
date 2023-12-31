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

import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.conf.*;

/**
 * Manipulate the working area for the transient store for maps and reduces.
 */ 
class MapOutputFile {

  private JobConf conf;
  private LocalDirAllocator lDirAlloc = 
                            new LocalDirAllocator("mapred.local.dir");
  
  /** Return the path to local map output file created earlier
   * @param mapTaskId a map task id
   */
  public Path getOutputFile(String mapTaskId)
    throws IOException {
    return lDirAlloc.getLocalPathToRead(mapTaskId+"/file.out", conf);
  }

  /** Create a local map output file name.
   * @param mapTaskId a map task id
   * @param size the size of the file
   */
  public Path getOutputFileForWrite(String mapTaskId, long size)
    throws IOException {
    return lDirAlloc.getLocalPathForWrite(mapTaskId+"/file.out", size, conf);
  }

  /** Return the path to a local map output index file created earlier
   * @param mapTaskId a map task id
   */
  public Path getOutputIndexFile(String mapTaskId)
    throws IOException {
    return lDirAlloc.getLocalPathToRead(mapTaskId + "/file.out.index", conf);
  }

  /** Create a local map output index file name.
   * @param mapTaskId a map task id
   * @param size the size of the file
   */
  public Path getOutputIndexFileForWrite(String mapTaskId, long size)
    throws IOException {
    return lDirAlloc.getLocalPathForWrite(mapTaskId + "/file.out.index", 
                                          size, conf);
  }

  /** Return a local map spill file created earlier.
   * @param mapTaskId a map task id
   * @param spillNumber the number
   */
  public Path getSpillFile(String mapTaskId, int spillNumber)
    throws IOException {
    return lDirAlloc.getLocalPathToRead(mapTaskId+"/spill" +spillNumber+".out",
                                        conf);
  }

  /** Create a local map spill file name.
   * @param mapTaskId a map task id
   * @param spillNumber the number
   * @param size the size of the file
   */
  public Path getSpillFileForWrite(String mapTaskId, int spillNumber, 
         long size) throws IOException {
    return lDirAlloc.getLocalPathForWrite(mapTaskId+
                                                  "/spill" +spillNumber+".out",
                                                  size, conf);
  }

  /** Return a local map spill index file created earlier
   * @param mapTaskId a map task id
   * @param spillNumber the number
   */
  public Path getSpillIndexFile(String mapTaskId, int spillNumber)
    throws IOException {
    return lDirAlloc.getLocalPathToRead(
        mapTaskId+"/spill" +spillNumber+".out.index", conf);
  }

  /** Create a local map spill index file name.
   * @param mapTaskId a map task id
   * @param spillNumber the number
   * @param size the size of the file
   */
  public Path getSpillIndexFileForWrite(String mapTaskId, int spillNumber,
         long size) throws IOException {
    return lDirAlloc.getLocalPathForWrite(
        mapTaskId+"/spill" +spillNumber+".out.index", size, conf);
  }

  /** Return a local reduce input file created earlier
   * @param mapTaskId a map task id
   * @param reduceTaskId a reduce task id
   */
  public Path getInputFile(int mapId, String reduceTaskId)
    throws IOException {
    // TODO *oom* should use a format here
    return lDirAlloc.getLocalPathToRead(reduceTaskId + "/map_"+mapId+".out",
                                        conf);
  }

  /** Create a local reduce input file name.
   * @param mapTaskId a map task id
   * @param reduceTaskId a reduce task id
   * @param size the size of the file
   */
  public Path getInputFileForWrite(int mapId, String reduceTaskId, long size)
    throws IOException {
    // TODO *oom* should use a format here
    return lDirAlloc.getLocalPathForWrite(reduceTaskId + "/map_"+mapId+".out",
                                          size, conf);
  }

  /** Removes all of the files related to a task. */
  public void removeAll(String taskId) throws IOException {
    conf.deleteLocalFiles(taskId);
  }

  /** 
   * Removes all contents of temporary storage.  Called upon 
   * startup, to remove any leftovers from previous run.
   */
  public void cleanupStorage() throws IOException {
    conf.deleteLocalFiles();
  }

  public void setConf(Configuration conf) {
    if (conf instanceof JobConf) {
      this.conf = (JobConf) conf;
    } else {
      this.conf = new JobConf(conf);
    }
  }
}
