/**
 * Copyright 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import junit.framework.TestCase;
import junit.framework.Test;
import junit.framework.TestSuite;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.MiniDFSCluster;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;

/**
 * Basic stand-alone testing of HRegion.
 * 
 * A lot of the meta information for an HRegion now lives inside other
 * HRegions or in the HBaseMaster, so only basic testing is possible.
 */
public class TestHRegion extends TestCase {
  private Logger LOG = Logger.getLogger(this.getClass().getName());
  
  /** Constructor */
  public TestHRegion(String name) {
    super(name);
  }
  
  /** Test suite so that all tests get run */
  public static Test suite() {
    TestSuite suite = new TestSuite();
    suite.addTest(new TestHRegion("testSetup"));
    suite.addTest(new TestHRegion("testLocks"));
    suite.addTest(new TestHRegion("testBadPuts"));
    suite.addTest(new TestHRegion("testBasic"));
    suite.addTest(new TestHRegion("testScan"));
    suite.addTest(new TestHRegion("testBatchWrite"));
    suite.addTest(new TestHRegion("testSplitAndMerge"));
    suite.addTest(new TestHRegion("testRead"));
    suite.addTest(new TestHRegion("testCleanup"));
    return suite;
  }
  
  
  private static final int FIRST_ROW = 1;
  private static final int N_ROWS = 1000000;
  private static final int NUM_VALS = 1000;
  private static final Text CONTENTS_BASIC = new Text("contents:basic");
  private static final String CONTENTSTR = "contentstr";
  private static final String ANCHORNUM = "anchor:anchornum-";
  private static final String ANCHORSTR = "anchorstr";
  private static final Text CONTENTS_BODY = new Text("contents:body");
  private static final Text CONTENTS_FIRSTCOL = new Text("contents:firstcol");
  private static final Text ANCHOR_SECONDCOL = new Text("anchor:secondcol");
  
  private static boolean initialized = false;
  private static boolean failures = false;
  private static Configuration conf = null;
  private static MiniDFSCluster cluster = null;
  private static FileSystem fs = null;
  private static Path parentdir = null;
  private static Path newlogdir = null;
  private static Path oldlogfile = null;
  private static HLog log = null;
  private static HTableDescriptor desc = null;
  private static HRegion region = null;
  
  private static int numInserted = 0;

  // Set up environment, start mini cluster, etc.
  
  @SuppressWarnings("unchecked")
  public void testSetup() throws IOException {
    try {
      if(System.getProperty("test.build.data") == null) {
        String dir = new File(new File("").getAbsolutePath(), "build/contrib/hbase/test").getAbsolutePath();
        System.out.println(dir);
        System.setProperty("test.build.data", dir);
      }
      conf = new HBaseConfiguration();
      
      Environment.getenv();
      if(Environment.debugging) {
        Logger rootLogger = Logger.getRootLogger();
        rootLogger.setLevel(Level.WARN);

        ConsoleAppender consoleAppender = null;
        for(Enumeration<Appender> e = (Enumeration<Appender>)rootLogger.getAllAppenders();
            e.hasMoreElements();) {
        
          Appender a = e.nextElement();
          if(a instanceof ConsoleAppender) {
            consoleAppender = (ConsoleAppender)a;
            break;
          }
        }
        if(consoleAppender != null) {
          Layout layout = consoleAppender.getLayout();
          if(layout instanceof PatternLayout) {
            PatternLayout consoleLayout = (PatternLayout)layout;
            consoleLayout.setConversionPattern("%d %-5p [%t] %l: %m%n");
          }
        }
        Logger.getLogger("org.apache.hadoop.hbase").setLevel(Environment.logLevel);
      }
      
      cluster = new MiniDFSCluster(conf, 2, true, (String[])null);
      fs = cluster.getFileSystem();
      parentdir = new Path("/hbase");
      fs.mkdirs(parentdir);
      newlogdir = new Path(parentdir, "log");
      oldlogfile = new Path(parentdir, "oldlogfile");

      log = new HLog(fs, newlogdir, conf);
      desc = new HTableDescriptor("test", 3);
      desc.addFamily(new Text("contents:"));
      desc.addFamily(new Text("anchor:"));
      region = new HRegion(parentdir, log, fs, conf, 
          new HRegionInfo(1, desc, null, null), null, oldlogfile);
      
    } catch(IOException e) {
      failures = true;
      throw e;
    }
    initialized = true;
  }

  // Test basic functionality. Writes to contents:basic and anchor:anchornum-*

  public void testBasic() throws IOException {
    if(!initialized) {
      throw new IllegalStateException();
    }

    try {
      long startTime = System.currentTimeMillis();
      
      // Write out a bunch of values
      
      for (int k = FIRST_ROW; k <= NUM_VALS; k++) {
        long writeid = region.startUpdate(new Text("row_" + k));
        region.put(writeid, CONTENTS_BASIC,
            new BytesWritable((CONTENTSTR + k).getBytes()));
        
        region.put(writeid, new Text(ANCHORNUM + k),
            new BytesWritable((ANCHORSTR + k).getBytes()));
        region.commit(writeid);
      }
      System.out.println("Write " + NUM_VALS + " rows. Elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));

      // Flush cache
      
      startTime = System.currentTimeMillis();
      
      region.flushcache(false);
      
      System.out.println("Cache flush elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));

      // Read them back in

      startTime = System.currentTimeMillis();
      
      Text collabel = null;
      for (int k = FIRST_ROW; k <= NUM_VALS; k++) {
        Text rowlabel = new Text("row_" + k);

        BytesWritable bodydata = region.get(rowlabel, CONTENTS_BASIC);
        assertNotNull(bodydata);
        byte[] bytes = new byte[bodydata.getSize()];
        System.arraycopy(bodydata.get(), 0, bytes, 0, bytes.length);
        String bodystr = new String(bytes).toString().trim();
        String teststr = CONTENTSTR + k;
        assertEquals("Incorrect value for key: (" + rowlabel + "," + CONTENTS_BASIC
            + "), expected: '" + teststr + "' got: '" + bodystr + "'",
            bodystr, teststr);
        collabel = new Text(ANCHORNUM + k);
        bodydata = region.get(rowlabel, collabel);
        bytes = new byte[bodydata.getSize()];
        System.arraycopy(bodydata.get(), 0, bytes, 0, bytes.length);
        bodystr = new String(bytes).toString().trim();
        teststr = ANCHORSTR + k;
        assertEquals("Incorrect value for key: (" + rowlabel + "," + collabel
            + "), expected: '" + teststr + "' got: '" + bodystr + "'",
            bodystr, teststr);
      }
      
      System.out.println("Read " + NUM_VALS + " rows. Elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));

    } catch(IOException e) {
      failures = true;
      throw e;
    }
  }
  
  public void testBadPuts() throws IOException {
    if(!initialized) {
      throw new IllegalStateException();
    }  
    
    // Try put with bad lockid.
    boolean exceptionThrown = false;
    try {
      region.put(-1, CONTENTS_BASIC, new BytesWritable("bad input".getBytes()));
    } catch (LockException e) {
      exceptionThrown = true;
    }
    assertTrue("Bad lock id", exceptionThrown);

    // Try column name not registered in the table.
    exceptionThrown = false;
    long lockid = -1;
    try {
      lockid = region.startUpdate(new Text("Some old key"));
      String unregisteredColName = "FamilyGroup:FamilyLabel";
      region.put(lockid, new Text(unregisteredColName),
          new BytesWritable(unregisteredColName.getBytes()));
    } catch (IOException e) {
      exceptionThrown = true;
    } finally {
      if (lockid != -1) {
        region.abort(lockid);
      }
    }
    assertTrue("Bad family", exceptionThrown);
  }
  
  /**
   * Test getting and releasing locks.
   */
  public void testLocks() {
    final int threadCount = 10;
    final int lockCount = 10;
    
    List<Thread>threads = new ArrayList<Thread>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      threads.add(new Thread(Integer.toString(i)) {
        public void run() {
          long [] lockids = new long[lockCount];
          // Get locks.
          for (int i = 0; i < lockCount; i++) {
            try {
              Text rowid = new Text(Integer.toString(i));
              lockids[i] = region.obtainLock(rowid);
              rowid.equals(region.getRowFromLock(lockids[i]));
              LOG.debug(getName() + " locked " + rowid.toString());
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
          LOG.debug(getName() + " set " +
              Integer.toString(lockCount) + " locks");
          
          // Abort outstanding locks.
          for (int i = lockCount - 1; i >= 0; i--) {
            try {
              region.abort(lockids[i]);
              LOG.debug(getName() + " unlocked " +
                  Integer.toString(i));
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
          LOG.debug(getName() + " released " +
              Integer.toString(lockCount) + " locks");
        }
      });
    }
    
    // Startup all our threads.
    for (Thread t : threads) {
      t.start();
    }
    
    // Now wait around till all are done.
    for (Thread t: threads) {
      while (t.isAlive()) {
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          // Go around again.
        }
      }
    }
  }

  // Test scanners. Writes contents:firstcol and anchor:secondcol
  
  public void testScan() throws IOException {
    if(!initialized) {
      throw new IllegalStateException();
    }

    Text cols[] = new Text[] {
        CONTENTS_FIRSTCOL,
        ANCHOR_SECONDCOL
    };

    // Test the Scanner!!!
    String[] vals1 = new String[1000];
    for(int k = 0; k < vals1.length; k++) {
      vals1[k] = Integer.toString(k);
    }

    // 1.  Insert a bunch of values
    
    long startTime = System.currentTimeMillis();

    for(int k = 0; k < vals1.length / 2; k++) {
      String kLabel = String.format("%1$03d", k);

      long lockid = region.startUpdate(new Text("row_vals1_" + kLabel));
      region.put(lockid, cols[0], new BytesWritable(vals1[k].getBytes()));
      region.put(lockid, cols[1], new BytesWritable(vals1[k].getBytes()));
      region.commit(lockid);
      numInserted += 2;
    }

    System.out.println("Write " + (vals1.length / 2) + " elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 2.  Scan from cache
    
    startTime = System.currentTimeMillis();

    HInternalScannerInterface s = region.getScanner(cols, new Text());
    int numFetched = 0;
    try {
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for(int j = 0; j < cols.length; j++) {
            if(col.compareTo(cols[j]) == 0) {
              assertEquals("Error at:" + curKey.getRow() + "/" + curKey.getTimestamp()
                  + ", Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, k, curval);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
    } finally {
      s.close();
    }
    assertEquals("Inserted " + numInserted + " values, but fetched " + numFetched, numInserted, numFetched);

    System.out.println("Scanned " + (vals1.length / 2)
        + " rows from cache. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 3.  Flush to disk
    
    startTime = System.currentTimeMillis();
    
    region.flushcache(false);

    System.out.println("Cache flush elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 4.  Scan from disk
    
    startTime = System.currentTimeMillis();
    
    s = region.getScanner(cols, new Text());
    numFetched = 0;
    try {
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for(int j = 0; j < cols.length; j++) {
            if(col.compareTo(cols[j]) == 0) {
              assertEquals("Error at:" + curKey.getRow() + "/" + curKey.getTimestamp()
                  + ", Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, k, curval);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
    } finally {
      s.close();
    }
    assertEquals("Inserted " + numInserted + " values, but fetched " + numFetched, numInserted, numFetched);

    System.out.println("Scanned " + (vals1.length / 2)
        + " rows from disk. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 5.  Insert more values
    
    startTime = System.currentTimeMillis();

    for(int k = vals1.length/2; k < vals1.length; k++) {
      String kLabel = String.format("%1$03d", k);
      
      long lockid = region.startUpdate(new Text("row_vals1_" + kLabel));
      region.put(lockid, cols[0], new BytesWritable(vals1[k].getBytes()));
      region.put(lockid, cols[1], new BytesWritable(vals1[k].getBytes()));
      region.commit(lockid);
      numInserted += 2;
    }

    System.out.println("Write " + (vals1.length / 2) + " rows. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 6.  Scan from cache and disk
    
    startTime = System.currentTimeMillis();

    s = region.getScanner(cols, new Text());
    numFetched = 0;
    try {
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for(int j = 0; j < cols.length; j++) {
            if(col.compareTo(cols[j]) == 0) {
              assertEquals("Error at:" + curKey.getRow() + "/" + curKey.getTimestamp()
                  + ", Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, k, curval);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
    } finally {
      s.close();
    }
    assertEquals("Inserted " + numInserted + " values, but fetched " + numFetched, numInserted, numFetched);

    System.out.println("Scanned " + vals1.length
        + " rows from cache and disk. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));
    
    // 7.  Flush to disk
    
    startTime = System.currentTimeMillis();
    
    region.flushcache(false);

    System.out.println("Cache flush elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));
    
    // 8.  Scan from disk
    
    startTime = System.currentTimeMillis();
    
    s = region.getScanner(cols, new Text());
    numFetched = 0;
    try {
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for (int j = 0; j < cols.length; j++) {
            if (col.compareTo(cols[j]) == 0) {
              assertEquals("Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, curval, k);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
    } finally {
      s.close();
    }
    assertEquals("Inserted " + numInserted + " values, but fetched " + numFetched, numInserted, numFetched);
    
    System.out.println("Scanned " + vals1.length
        + " rows from disk. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));

    // 9. Scan with a starting point

    startTime = System.currentTimeMillis();
    
    s = region.getScanner(cols, new Text("row_vals1_500"));
    numFetched = 0;
    try {
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 500;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for (int j = 0; j < cols.length; j++) {
            if (col.compareTo(cols[j]) == 0) {
              assertEquals("Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, curval, k);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
    } finally {
      s.close();
    }
    assertEquals("Should have fetched " + (numInserted / 2) + " values, but fetched " + numFetched, (numInserted / 2), numFetched);
    
    System.out.println("Scanned " + (numFetched / 2)
        + " rows from disk with specified start point. Elapsed time: "
        + ((System.currentTimeMillis() - startTime) / 1000.0));
  }
  
  // Do a large number of writes. Disabled if not debugging because it takes a 
  // long time to run.
  // Creates contents:body
  
  public void testBatchWrite() throws IOException {
    if(!initialized || failures) {
      throw new IllegalStateException();
    }
    if(! Environment.debugging) {
      return;
    }

    try {
      long totalFlush = 0;
      long totalCompact = 0;
      long totalLog = 0;
      long startTime = System.currentTimeMillis();

      // 1M writes

      int valsize = 1000;
      for (int k = FIRST_ROW; k <= N_ROWS; k++) {
        // Come up with a random 1000-byte string
        String randstr1 = "" + System.currentTimeMillis();
        StringBuffer buf1 = new StringBuffer("val_" + k + "__");
        while (buf1.length() < valsize) {
          buf1.append(randstr1);
        }

        // Write to the HRegion
        long writeid = region.startUpdate(new Text("row_" + k));
        region.put(writeid, CONTENTS_BODY, new BytesWritable(buf1.toString().getBytes()));
        region.commit(writeid);
        if (k > 0 && k % (N_ROWS / 100) == 0) {
          System.out.println("Flushing write #" + k);

          long flushStart = System.currentTimeMillis();
          region.flushcache(false);
          long flushEnd = System.currentTimeMillis();
          totalFlush += (flushEnd - flushStart);

          if (k % (N_ROWS / 10) == 0) {
            System.out.print("Rolling log...");
            long logStart = System.currentTimeMillis();
            log.rollWriter();
            long logEnd = System.currentTimeMillis();
            totalLog += (logEnd - logStart);
            System.out.println("  elapsed time: " + ((logEnd - logStart) / 1000.0));
          }
        }
      }
      long startCompact = System.currentTimeMillis();
      if(region.compactStores()) {
        totalCompact = System.currentTimeMillis() - startCompact;
        System.out.println("Region compacted - elapsedTime: " + (totalCompact / 1000.0));
        
      } else {
        System.out.println("No compaction required.");
      }
      long endTime = System.currentTimeMillis();

      long totalElapsed = (endTime - startTime);
      System.out.println();
      System.out.println("Batch-write complete.");
      System.out.println("Wrote " + N_ROWS + " rows, each of ~" + valsize + " bytes");
      System.out.println("Total flush-time: " + (totalFlush / 1000.0));
      System.out.println("Total compact-time: " + (totalCompact / 1000.0));
      System.out.println("Total log-time: " + (totalLog / 1000.0));
      System.out.println("Total time elapsed: " + (totalElapsed / 1000.0));
      System.out.println("Total time, rows/second: " + (N_ROWS / (totalElapsed / 1000.0)));
      System.out.println("Adjusted time (not including flush, compact, or log): " + ((totalElapsed - totalFlush - totalCompact - totalLog) / 1000.0));
      System.out.println("Adjusted time, rows/second: " + (N_ROWS / ((totalElapsed - totalFlush - totalCompact - totalLog) / 1000.0)));
      System.out.println();
      
    } catch(IOException e) {
      failures = true;
      throw e;
    }
  }

  // NOTE: This test depends on testBatchWrite succeeding
  
  public void testSplitAndMerge() throws IOException {
    if(!initialized || failures) {
      throw new IllegalStateException();
    }
    
    try {
      Text midKey = new Text();
      
      if(region.needsSplit(midKey)) {
        System.out.println("Needs split");
      }
      
      // Split it anyway

      Text midkey = new Text("row_" + (Environment.debugging ? (N_ROWS / 2) : (NUM_VALS/2)));
      Path oldRegionPath = region.getRegionDir();
      
      long startTime = System.currentTimeMillis();
      
      HRegion subregions[] = region.closeAndSplit(midkey);
      
      System.out.println("Split region elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));
      
      assertEquals("Number of subregions", subregions.length, 2);

      // Now merge it back together

      Path oldRegion1 = subregions[0].getRegionDir();
      Path oldRegion2 = subregions[1].getRegionDir();
      
      startTime = System.currentTimeMillis();
      
      region = HRegion.closeAndMerge(subregions[0], subregions[1]);

      System.out.println("Merge regions elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));
      
      fs.delete(oldRegionPath);
      fs.delete(oldRegion1);
      fs.delete(oldRegion2);
      
    } catch(IOException e) {
      failures = true;
      throw e;
    }
  }

  // This test verifies that everything is still there after splitting and merging
  
  public void testRead() throws IOException {
    if(!initialized || failures) {
      throw new IllegalStateException();
    }

    // First verify the data written by testBasic()

    Text[] cols = new Text[] {
        new Text(ANCHORNUM + "[0-9]+"),
        new Text(CONTENTS_BASIC)
    };
    
    long startTime = System.currentTimeMillis();
    
    HInternalScannerInterface s = region.getScanner(cols, new Text());

    try {

      int contentsFetched = 0;
      int anchorFetched = 0;
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          String curval = new String(bytes).trim();

          if(col.compareTo(CONTENTS_BASIC) == 0) {
            assertTrue("Error at:" + curKey.getRow() + "/" + curKey.getTimestamp()
                + ", Value for " + col + " should start with: " + CONTENTSTR
                + ", but was fetched as: " + curval,
                curval.startsWith(CONTENTSTR));
            contentsFetched++;
            
          } else if(col.toString().startsWith(ANCHORNUM)) {
            assertTrue("Error at:" + curKey.getRow() + "/" + curKey.getTimestamp()
                + ", Value for " + col + " should start with: " + ANCHORSTR
                + ", but was fetched as: " + curval,
                curval.startsWith(ANCHORSTR));
            anchorFetched++;
            
          } else {
            System.out.println(col);
          }
        }
        curVals.clear();
        k++;
      }
      assertEquals("Expected " + NUM_VALS + " " + CONTENTS_BASIC + " values, but fetched " + contentsFetched, NUM_VALS, contentsFetched);
      assertEquals("Expected " + NUM_VALS + " " + ANCHORNUM + " values, but fetched " + anchorFetched, NUM_VALS, anchorFetched);

      System.out.println("Scanned " + NUM_VALS
          + " rows from disk. Elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));
      
    } finally {
      s.close();
    }
    
    // Verify testScan data
    
    cols = new Text[] {
        CONTENTS_FIRSTCOL,
        ANCHOR_SECONDCOL
    };
    
    startTime = System.currentTimeMillis();

    s = region.getScanner(cols, new Text());
    try {
      int numFetched = 0;
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      int k = 0;
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          Text col = it.next();
          BytesWritable val = curVals.get(col);
          byte[] bytes = new byte[val.getSize()];
          System.arraycopy(val.get(), 0, bytes, 0, bytes.length);
          int curval = Integer.parseInt(new String(bytes).trim());

          for (int j = 0; j < cols.length; j++) {
            if (col.compareTo(cols[j]) == 0) {
              assertEquals("Value for " + col + " should be: " + k
                  + ", but was fetched as: " + curval, curval, k);
              numFetched++;
            }
          }
        }
        curVals.clear();
        k++;
      }
      assertEquals("Inserted " + numInserted + " values, but fetched " + numFetched, numInserted, numFetched);

      System.out.println("Scanned " + (numFetched / 2)
          + " rows from disk. Elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));
      
    } finally {
      s.close();
    }
    
    // Verify testBatchWrite data

    if(Environment.debugging) {
      startTime = System.currentTimeMillis();
      
      s = region.getScanner(new Text[] { CONTENTS_BODY }, new Text());
      try {
        int numFetched = 0;
        HStoreKey curKey = new HStoreKey();
        TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
        int k = 0;
        while(s.next(curKey, curVals)) {
          for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
            Text col = it.next();
            BytesWritable val = curVals.get(col);

            assertTrue(col.compareTo(CONTENTS_BODY) == 0);
            assertNotNull(val);
            numFetched++;
          }
          curVals.clear();
          k++;
        }
        assertEquals("Inserted " + N_ROWS + " values, but fetched " + numFetched, N_ROWS, numFetched);

        System.out.println("Scanned " + N_ROWS
            + " rows from disk. Elapsed time: "
            + ((System.currentTimeMillis() - startTime) / 1000.0));
        
      } finally {
        s.close();
      }
    }
    
    // Test a scanner which only specifies the column family name
    
    cols = new Text[] {
        new Text("anchor:")
    };
    
    startTime = System.currentTimeMillis();
    
    s = region.getScanner(cols, new Text());

    try {
      int fetched = 0;
      HStoreKey curKey = new HStoreKey();
      TreeMap<Text, BytesWritable> curVals = new TreeMap<Text, BytesWritable>();
      while(s.next(curKey, curVals)) {
        for(Iterator<Text> it = curVals.keySet().iterator(); it.hasNext(); ) {
          it.next();
          fetched++;
        }
        curVals.clear();
      }
      assertEquals("Inserted " + (NUM_VALS + numInserted/2) + " values, but fetched " + fetched, (NUM_VALS + numInserted/2), fetched);

      System.out.println("Scanned " + fetched
          + " rows from disk. Elapsed time: "
          + ((System.currentTimeMillis() - startTime) / 1000.0));
      
    } finally {
      s.close();
    }
  }

  
  private static void deleteFile(File f) {
    if(f.isDirectory()) {
      File[] children = f.listFiles();
      for(int i = 0; i < children.length; i++) {
        deleteFile(children[i]);
      }
    }
    f.delete();
  }
  
  public void testCleanup() throws IOException {
    if(!initialized) {
      throw new IllegalStateException();
    }

    // Shut down the mini cluster
    
    cluster.shutdown();
    
    // Delete all the DFS files
    
    deleteFile(new File(System.getProperty("test.build.data"), "dfs"));
    
    }
}
