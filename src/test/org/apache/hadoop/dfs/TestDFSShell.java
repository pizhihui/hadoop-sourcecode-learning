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
package org.apache.hadoop.dfs;

import junit.framework.TestCase;
import java.io.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FsShell;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.StringUtils;


/**
 * This class tests commands from DFSShell.
 * @author Dhruba Borthakur
 */
public class TestDFSShell extends TestCase {
  private static String TEST_ROOT_DIR =
    new Path(System.getProperty("test.build.data","/tmp"))
    .toString().replace(' ', '+');
  
  private void writeFile(FileSystem fileSys, Path name) throws IOException {
    DataOutputStream stm = fileSys.create(name);
    stm.writeBytes("dhruba");
    stm.close();
  }
  
  /**
   * Tests various options of DFSShell.
   */
  public void testDFSShell() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 2, true, null);
    FileSystem fs = cluster.getFileSystem();
    assertTrue("Not a HDFS: "+fs.getUri(),
               fs instanceof DistributedFileSystem);
    DistributedFileSystem fileSys = (DistributedFileSystem)fs;
    FsShell shell = new FsShell();
    shell.setConf(conf);

    try {
      // First create a new directory with mkdirs
      Path myPath = new Path("/test/mkdirs");
      assertTrue(fileSys.mkdirs(myPath));
      assertTrue(fileSys.exists(myPath));
      assertTrue(fileSys.mkdirs(myPath));

      // Second, create a file in that directory.
      Path myFile = new Path("/test/mkdirs/myFile");
      writeFile(fileSys, myFile);
      assertTrue(fileSys.exists(myFile));

      // Verify that we can read the file
      {
        String[] args = new String[2];
        args[0] = "-cat";
        args[1] = "/test/mkdirs/myFile";
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run: " +
                             StringUtils.stringifyException(e)); 
        }
        assertTrue(val == 0);
      }

      // Verify that we can get with and without crc
      {
        File testFile = new File(TEST_ROOT_DIR, "myFile");
        File checksumFile = new File(fileSys.getChecksumFile(
                                                             new Path(testFile.getAbsolutePath())).toString());
        testFile.delete();
        checksumFile.delete();
          
        String[] args = new String[3];
        args[0] = "-get";
        args[1] = "/test/mkdirs";
        args[2] = TEST_ROOT_DIR;
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage()); 
        }
        assertTrue(val == 0);
        assertTrue("Copying failed.", testFile.exists());
        assertTrue("Checksum file " + checksumFile+" is copied.", !checksumFile.exists());
        testFile.delete();
      }
      {
        File testFile = new File(TEST_ROOT_DIR, "myFile");
        File checksumFile = new File(fileSys.getChecksumFile(
                                                             new Path(testFile.getAbsolutePath())).toString());
        testFile.delete();
        checksumFile.delete();
          
        String[] args = new String[4];
        args[0] = "-get";
        args[1] = "-crc";
        args[2] = "/test/mkdirs";
        args[3] = TEST_ROOT_DIR;
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage()); 
        }
        assertTrue(val == 0);
          
        assertTrue("Copying data file failed.", testFile.exists());
        assertTrue("Checksum file " + checksumFile+" not copied.", checksumFile.exists());
        testFile.delete();
        checksumFile.delete();
      }
      // Verify that we get an error while trying to read an nonexistent file
      {
        String[] args = new String[2];
        args[0] = "-cat";
        args[1] = "/test/mkdirs/myFile1";
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage()); 
        }
        assertTrue(val != 0);
      }

      // Verify that we get an error while trying to delete an nonexistent file
      {
        String[] args = new String[2];
        args[0] = "-rm";
        args[1] = "/test/mkdirs/myFile1";
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage()); 
        }
        assertTrue(val != 0);
      }

      // Verify that we succeed in removing the file we created
      {
        String[] args = new String[2];
        args[0] = "-rm";
        args[1] = "/test/mkdirs/myFile";
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage()); 
        }
        assertTrue(val == 0);
      }

      // Verify that cp from a directory to a subdirectory fails
      {
        String[] args = new String[2];
        args[0] = "-mkdir";
        args[1] = "/test/dir1";
        int val = -1;
        try {
          val = shell.run(args);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage());
        }
        assertTrue(val == 0);

        // this should fail
        String[] args1 = new String[3];
        args1[0] = "-cp";
        args1[1] = "/test/dir1";
        args1[2] = "/test/dir1/dir2";
        val = 0;
        try {
          val = shell.run(args1);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage());
        }
        assertTrue(val == -1);

        // this should succeed
        args1[0] = "-cp";
        args1[1] = "/test/dir1";
        args1[2] = "/test/dir1foo";
        val = -1;
        try {
          val = shell.run(args1);
        } catch (Exception e) {
          System.err.println("Exception raised from DFSShell.run " +
                             e.getLocalizedMessage());
        }
        assertTrue(val == 0);
      }
        
    } finally {
      try {
        fileSys.close();
      } catch (Exception e) {
      }
      cluster.shutdown();
    }
  }
}
