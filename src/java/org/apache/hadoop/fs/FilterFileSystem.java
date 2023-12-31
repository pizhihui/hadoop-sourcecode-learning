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
import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Progressable;

/****************************************************************
 * A <code>FilterFileSystem</code> contains
 * some other file system, which it uses as
 * its  basic file system, possibly transforming
 * the data along the way or providing  additional
 * functionality. The class <code>FilterFileSystem</code>
 * itself simply overrides all  methods of
 * <code>FileSystem</code> with versions that
 * pass all requests to the contained  file
 * system. Subclasses of <code>FilterFileSystem</code>
 * may further override some of  these methods
 * and may also provide additional methods
 * and fields.
 *
 * @author Hairong Kuang
 *****************************************************************/
public class FilterFileSystem extends FileSystem {
  
  protected FileSystem fs;
  
  public FilterFileSystem(FileSystem fs) {
    this.fs = fs;
  }

  /** Called after a new FileSystem instance is constructed.
   * @param name a uri whose authority section names the host, port, etc.
   *   for this FileSystem
   * @param conf the configuration
   */
  public void initialize(URI name, Configuration conf) throws IOException {
    fs.initialize(name, conf);
  }

  /** Returns a URI whose scheme and authority identify this FileSystem.*/
  public URI getUri() {
    return fs.getUri();
  }

  /** @deprecated call #getUri() instead.*/
  public String getName() {
    return fs.getName();
  }

  /** Make sure that a path specifies a FileSystem. */
  public Path makeQualified(Path path) {
    return fs.makeQualified(path);
  }
  
  ///////////////////////////////////////////////////////////////
  // FileSystem
  ///////////////////////////////////////////////////////////////

  /** Check that a Path belongs to this FileSystem. */
  protected void checkPath(Path path) {
    fs.checkPath(path);
  }

  /**
   * Return a 2D array of size 1x1 or greater, containing hostnames 
   * where portions of the given file can be found.  For a nonexistent 
   * file or regions, null will be returned.
   *
   * This call is most helpful with DFS, where it returns 
   * hostnames of machines that contain the given file.
   *
   * The FileSystem will simply return an elt containing 'localhost'.
   */
  public String[][] getFileCacheHints(Path f, long start, long len)
    throws IOException {
    return fs.getFileCacheHints(f, start, len);
  }

  /**
   * Opens an FSDataInputStream at the indicated Path.
   * @param f the file name to open
   * @param bufferSize the size of the buffer to be used.
   */
  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    return fs.open(f, bufferSize);
  }
  
  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * @param f the file name to open
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   * @param replication required block replication for the file. 
   */
  public FSDataOutputStream create(Path f, 
                                   boolean overwrite,
                                   int bufferSize,
                                   short replication,
                                   long blockSize,
                                   Progressable progress
                                   ) throws IOException {
    return fs.create(f, overwrite, bufferSize, replication, blockSize, progress);
  }

  /**
   * Get replication.
   * 
   * @param src file name
   * @return file replication
   * @throws IOException
   */
  public short getReplication(Path src) throws IOException {
    return fs.getReplication(src);
  }

  /**
   * Set replication for an existing file.
   * 
   * @param src file name
   * @param replication new replication
   * @throws IOException
   * @return true if successful;
   *         false if file does not exist or is a directory
   */
  public boolean setReplication(Path src, short replication) throws IOException {
    return fs.setReplication(src, replication);
  }
  
  /**
   * Renames Path src to Path dst.  Can take place on local fs
   * or remote DFS.
   */
  public boolean rename(Path src, Path dst) throws IOException {
    return fs.rename(src, dst);
  }
  
  /** Delete a file */
  public boolean delete(Path f) throws IOException {
    return fs.delete(f);
  }
  
  /** Check if exists.
   * @param f source file
   */
  public boolean exists(Path f) throws IOException {
    return fs.exists(f);
  }

  /** True iff the named path is a directory. */
  public boolean isDirectory(Path f) throws IOException {
    return fs.isDirectory(f);
  }

  /** The number of bytes in a file. */
  public long getLength(Path f) throws IOException {
    return fs.getLength(f);
  }
  
  /** List files in a directory. */
  public Path[] listPaths(Path f) throws IOException {
    return fs.listPaths(f);
  }
  
  /**
   * Set the current working directory for the given file system. All relative
   * paths will be resolved relative to it.
   * 
   * @param newDir
   */
  public void setWorkingDirectory(Path newDir) {
    fs.setWorkingDirectory(newDir);
  }
  
  /**
   * Get the current working directory for the given file system
   * 
   * @return the directory pathname
   */
  public Path getWorkingDirectory() {
    return fs.getWorkingDirectory();
  }
  
  /**
   * Make the given file and all non-existent parents into directories. Has
   * the semantics of Unix 'mkdir -p'. Existence of the directory hierarchy is
   * not an error.
   */
  public boolean mkdirs(Path f) throws IOException {
    return fs.mkdirs(f);
  }

  /**
   * Obtain a lock on the given Path
   * 
   * @deprecated FS does not support file locks anymore.
   */
  @Deprecated
  public void lock(Path f, boolean shared) throws IOException {
    fs.lock(f, shared);
  }

  /**
   * Release the lock
   * 
   * @deprecated FS does not support file locks anymore.     
   */
  @Deprecated
  public void release(Path f) throws IOException {
    fs.release(f);
  }

  /**
   * The src file is on the local disk.  Add it to FS at
   * the given dst name.
   * delSrc indicates if the source should be removed
   */
  public void copyFromLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {
    fs.copyFromLocalFile(delSrc, src, dst);
  }
  
  /**
   * The src file is under FS, and the dst is on the local disk.
   * Copy it from FS control to the local dst name.
   * delSrc indicates if the src will be removed or not.
   */   
  public void copyToLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {
    fs.copyToLocalFile(delSrc, src, dst);
  }
  
  /**
   * Returns a local File that the user can write output to.  The caller
   * provides both the eventual FS target name and the local working
   * file.  If the FS is local, we write directly into the target.  If
   * the FS is remote, we write into the tmp local area.
   */
  public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
    return fs.startLocalOutput(fsOutputFile, tmpLocalFile);
  }

  /**
   * Called when we're all done writing to the target.  A local FS will
   * do nothing, because we've written to exactly the right place.  A remote
   * FS will copy the contents of tmpLocalFile to the correct target at
   * fsOutputFile.
   */
  public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
    fs.completeLocalOutput(fsOutputFile, tmpLocalFile);
  }

  /**
   * Get the block size for a particular file.
   * @param f the filename
   * @return the number of bytes in a block
   */
  public long getBlockSize(Path f) throws IOException {
    return fs.getBlockSize(f);
  }
  
  /** Return the number of bytes that large input files should be optimally
   * be split into to minimize i/o time. */
  public long getDefaultBlockSize() {
    return fs.getDefaultBlockSize();
  }
  
  /**
   * Get the default replication.
   */
  public short getDefaultReplication() {
    return fs.getDefaultReplication();
  }

  @Override
  public Configuration getConf() {
    return fs.getConf();
  }
  
  @Override
  public void close() throws IOException {
    super.close();
    fs.close();
  }
}
