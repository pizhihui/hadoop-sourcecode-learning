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

package org.apache.hadoop.fs;

import java.io.*;
import java.util.*;

import org.apache.commons.logging.*;

import org.apache.hadoop.util.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.conf.Configuration; 

/** An implementation of a round-robin scheme for disk allocation for creating
 * files. The way it works is that it is kept track what disk was last
 * allocated for a file write. For the current request, the next disk from
 * the set of disks would be allocated if the free space on the disk is 
 * sufficient enough to accomodate the file that is being considered for
 * creation. If the space requirements cannot be met, the next disk in order
 * would be tried and so on till a disk is found with sufficient capacity.
 * Once a disk with sufficient space is identified, a check is done to make
 * sure that the disk is writable. Also, there is an API provided that doesn't
 * take the space requirements into consideration but just checks whether the
 * disk under consideration is writable (this should be used for cases where
 * the file size is not known apriori). An API is provided to read a path that
 * was created earlier. That API works by doing a scan of all the disks for the
 * input pathname.
 * This implementation also provides the functionality of having multiple 
 * allocators per JVM (one for each unique functionality or context, like 
 * mapred, dfs-client, etc.). It ensures that there is only one instance of
 * an allocator per context per JVM.
 * Note:
 * 1. The contexts referred above are actually the configuration items defined
 * in the Configuration class like "mapred.local.dir" (for which we want to 
 * control the dir allocations). The context-strings are exactly those 
 * configuration items.
 * 2. This implementation does not take into consideration cases where
 * a disk becomes read-only or goes out of space while a file is being written
 * to (disks are shared between multiple processes, and so the latter situation
 * is probable).
 * 3. In the class implementation, "Disk" is referred to as "Dir", which
 * actually points to the configured directory on the Disk which will be the
 * parent for all file write/read allocations.
 */
public class LocalDirAllocator {
  
  //A Map from the config item names like "mapred.local.dir", 
  //"dfs.client.buffer.dir" to the instance of the AllocatorPerContext. This
  //is a static object to make sure there exists exactly one instance per JVM
  private static Map <String, AllocatorPerContext> contexts = 
                 new TreeMap<String, AllocatorPerContext>();
  private String contextCfgItemName;

  /**Create an allocator object
   * @param contextCfgItemName
   */
  public LocalDirAllocator(String contextCfgItemName) {
    this.contextCfgItemName = contextCfgItemName;
  }
  
  /** This method must be used to obtain the dir allocation context for a 
   * particular value of the context name. The context name must be an item
   * defined in the Configuration object for which we want to control the 
   * dir allocations (e.g., <code>mapred.local.dir</code>). The method will
   * create a context for that name if it doesn't already exist.
   */
  private AllocatorPerContext obtainContext(String contextCfgItemName) {
    synchronized (contexts) {
      AllocatorPerContext l = contexts.get(contextCfgItemName);
      if (l == null) {
        contexts.put(contextCfgItemName, 
                    (l = new AllocatorPerContext(contextCfgItemName)));
      }
      return l;
    }
  }
  
  /** Get a path from the local FS. This method should be used if the size of 
   *  the file is not known apriori. We go round-robin over the set of disks
   *  (via the configured dirs) and return the first complete path where
   *  we could create the parent directory of the passed path. 
   *  @param pathStr the requested path (this will be created on the first 
   *  available disk)
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   */
  public Path getLocalPathForWrite(String pathStr, 
      Configuration conf) throws IOException {
    return getLocalPathForWrite(pathStr, -1, conf);
  }
  
  /** Get a path from the local FS. Pass size as -1 if not known apriori. We
   *  round-robin over the set of disks (via the configured dirs) and return
   *  the first complete path which has enough space 
   *  @param pathStr the requested path (this will be created on the first 
   *  available disk)
   *  @param size the size of the file that is going to be written
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   */
  public Path getLocalPathForWrite(String pathStr, long size, 
      Configuration conf) throws IOException {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.getLocalPathForWrite(pathStr, size, conf);
  }
  
  /** Get a path from the local FS for reading. We search through all the
   *  configured dirs for the file's existence and return the complete
   *  path to the file when we find one 
   *  @param pathStr the requested file (this will be searched)
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   */
  public Path getLocalPathToRead(String pathStr, 
      Configuration conf) throws IOException {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.getLocalPathToRead(pathStr, conf);
  }
  
  /** Method to check whether a context is valid
   * @param contextCfgItemName
   * @return true/false
   */
  public static boolean isContextValid(String contextCfgItemName) {
    synchronized (contexts) {
      return contexts.containsKey(contextCfgItemName);
    }
  }
    
  private class AllocatorPerContext {

    private final Log LOG =
      LogFactory.getLog("org.apache.hadoop.fs.AllocatorPerContext");

    private int dirNumLastAccessed;
    private FileSystem localFS;
    private DF[] dirDF;
    private String contextCfgItemName;
    private String[] localDirs;
    private String savedLocalDirs = "";

    public AllocatorPerContext(String contextCfgItemName) {
      this.contextCfgItemName = contextCfgItemName;
    }

    /** This method gets called everytime before any read/write to make sure
     * that any change to localDirs is reflected immediately.
     */
    private void confChanged(Configuration conf) throws IOException {
      String newLocalDirs = conf.get(contextCfgItemName);
      if (!newLocalDirs.equals(savedLocalDirs)) {
        localDirs = conf.getStrings(contextCfgItemName);
        localFS = FileSystem.getLocal(conf);
        int numDirs = localDirs.length;
        dirDF = new DF[numDirs];
        for (int i = 0; i < numDirs; i++) {
          try {
            localFS.mkdirs(new Path(localDirs[i]));
          } catch (IOException ie) { } //ignore
          dirDF[i] = new DF(new File(localDirs[i]), 30000);
        }
        dirNumLastAccessed = 0;
        savedLocalDirs = newLocalDirs;
      }
    }

    private Path createPath(String path) throws IOException {
      Path file = new Path(new Path(localDirs[dirNumLastAccessed]),
                                    path);
      //check whether we are able to create a directory here. If the disk
      //happens to be RDONLY we will fail
      try {
        DiskChecker.checkDir(new File(file.getParent().toUri().getPath()));
        return file;
      } catch (DiskErrorException d) {
        LOG.warn(StringUtils.stringifyException(d));
        return null;
      }
    }

    /** Get a path from the local FS. This method should be used if the size of 
     *  the file is not known apriori. We go round-robin over the set of disks
     *  (via the configured dirs) and return the first complete path where
     *  we could create the parent directory of the passed path. 
     */
    public synchronized Path getLocalPathForWrite(String path, 
        Configuration conf) throws IOException {
      return getLocalPathForWrite(path, -1, conf);
    }

    /** Get a path from the local FS. Pass size as -1 if not known apriori. We
     *  round-robin over the set of disks (via the configured dirs) and return
     *  the first complete path which has enough space 
     */
    public synchronized Path getLocalPathForWrite(String pathStr, long size, 
        Configuration conf) throws IOException {
      confChanged(conf);
      int numDirs = localDirs.length;
      int numDirsSearched = 0;
      //remove the leading slash from the path (to make sure that the uri
      //resolution results in a valid path on the dir being checked)
      if (pathStr.startsWith("/")) {
        pathStr = pathStr.substring(1);
      }
      Path returnPath = null;
      while (numDirsSearched < numDirs && returnPath == null) {
        if (size >= 0) {
          long capacity = dirDF[dirNumLastAccessed].getAvailable();
          if (capacity > size) {
            returnPath = createPath(pathStr);
          }
        } else {
          returnPath = createPath(pathStr);
        }
        dirNumLastAccessed++;
        dirNumLastAccessed = dirNumLastAccessed % numDirs; 
        numDirsSearched++;
      } 

      if (returnPath != null) {
        return returnPath;
      }
      
      //no path found
      throw new DiskErrorException("Could not find any valid local " +
          "directory for " + pathStr);
    }

    /** Get a path from the local FS for reading. We search through all the
     *  configured dirs for the file's existence and return the complete
     *  path to the file when we find one 
     */
    public synchronized Path getLocalPathToRead(String pathStr, 
        Configuration conf) throws IOException {
      confChanged(conf);
      int numDirs = localDirs.length;
      int numDirsSearched = 0;
      //remove the leading slash from the path (to make sure that the uri
      //resolution results in a valid path on the dir being checked)
      if (pathStr.startsWith("/")) {
        pathStr = pathStr.substring(1);
      }
      while (numDirsSearched < numDirs) {
        Path file = new Path(localDirs[numDirsSearched], pathStr);
        if (localFS.exists(file)) {
          return file;
        }
        numDirsSearched++;
      }

      //no path found
      throw new DiskErrorException ("Could not find " + pathStr +" in any of" +
      " the configured local directories");
    }
  }
}
