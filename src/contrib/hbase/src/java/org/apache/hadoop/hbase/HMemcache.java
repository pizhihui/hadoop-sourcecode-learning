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

import org.apache.hadoop.io.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.util.*;

/*******************************************************************************
 * The HMemcache holds in-memory modifications to the HRegion.  This is really a
 * wrapper around a TreeMap that helps us when staging the Memcache out to disk.
 ******************************************************************************/
public class HMemcache {
  private static final Log LOG = LogFactory.getLog(HMemcache.class);
  
  TreeMap<HStoreKey, BytesWritable> memcache 
      = new TreeMap<HStoreKey, BytesWritable>();
  
  Vector<TreeMap<HStoreKey, BytesWritable>> history 
      = new Vector<TreeMap<HStoreKey, BytesWritable>>();
  
  TreeMap<HStoreKey, BytesWritable> snapshot = null;

  HLocking lock = new HLocking();

  public HMemcache() {
  }

  public static class Snapshot {
    public TreeMap<HStoreKey, BytesWritable> memcacheSnapshot = null;
    public long sequenceId = 0;
    
    public Snapshot() {
    }
  }
  
  /**
   * Returns a snapshot of the current HMemcache with a known HLog 
   * sequence number at the same time.
   *
   * We need to prevent any writing to the cache during this time,
   * so we obtain a write lock for the duration of the operation.
   * 
   * <p>If this method returns non-null, client must call
   * {@link #deleteSnapshot()} to clear 'snapshot-in-progress'
   * state when finished with the returned {@link Snapshot}.
   * 
   * @return frozen HMemcache TreeMap and HLog sequence number.
   */
  public Snapshot snapshotMemcacheForLog(HLog log) throws IOException {
    Snapshot retval = new Snapshot();

    this.lock.obtainWriteLock();
    try {
      if(snapshot != null) {
        throw new IOException("Snapshot in progress!");
      }
      if(memcache.size() == 0) {
        if(LOG.isDebugEnabled()) {
          LOG.debug("memcache empty. Skipping snapshot");
        }
        return retval;
      }

      if(LOG.isDebugEnabled()) {
        LOG.debug("starting memcache snapshot");
      }
      
      retval.memcacheSnapshot = memcache;
      this.snapshot = memcache;
      history.add(memcache);
      memcache = new TreeMap<HStoreKey, BytesWritable>();
      retval.sequenceId = log.startCacheFlush();
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("memcache snapshot complete");
      }
      
      return retval;
      
    } finally {
      this.lock.releaseWriteLock();
    }
  }

  /**
   * Delete the snapshot, remove from history.
   *
   * Modifying the structure means we need to obtain a writelock.
   */
  public void deleteSnapshot() throws IOException {
    this.lock.obtainWriteLock();

    try {
      if(snapshot == null) {
        throw new IOException("Snapshot not present!");
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug("deleting snapshot");
      }
      
      for(Iterator<TreeMap<HStoreKey, BytesWritable>> it = history.iterator(); 
          it.hasNext(); ) {
        
        TreeMap<HStoreKey, BytesWritable> cur = it.next();
        if(snapshot == cur) {
          it.remove();
          break;
        }
      }
      this.snapshot = null;
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("snapshot deleted");
      }
      
    } finally {
      this.lock.releaseWriteLock();
    }
  }

  /**
   * Store a value.  
   *
   * Operation uses a write lock.
   */
  public void add(Text row, TreeMap<Text, BytesWritable> columns, long timestamp) {
    this.lock.obtainWriteLock();
    try {
      for(Iterator<Text> it = columns.keySet().iterator(); it.hasNext(); ) {
        Text column = it.next();
        BytesWritable val = columns.get(column);

        HStoreKey key = new HStoreKey(row, column, timestamp);
        memcache.put(key, val);
      }
      
    } finally {
      this.lock.releaseWriteLock();
    }
  }

  /**
   * Look back through all the backlog TreeMaps to find the target.
   *
   * We only need a readlock here.
   */
  public BytesWritable[] get(HStoreKey key, int numVersions) {
    Vector<BytesWritable> results = new Vector<BytesWritable>();
    this.lock.obtainReadLock();
    try {
      Vector<BytesWritable> result = get(memcache, key, numVersions-results.size());
      results.addAll(0, result);

      for(int i = history.size()-1; i >= 0; i--) {
        if(numVersions > 0 && results.size() >= numVersions) {
          break;
        }
        
        result = get(history.elementAt(i), key, numVersions-results.size());
        results.addAll(results.size(), result);
      }
      
      if(results.size() == 0) {
        return null;
        
      } else {
        return results.toArray(new BytesWritable[results.size()]);
      }
      
    } finally {
      this.lock.releaseReadLock();
    }
  }
  
  /**
   * Return all the available columns for the given key.  The key indicates a 
   * row and timestamp, but not a column name.
   *
   * The returned object should map column names to byte arrays (byte[]).
   */
  public TreeMap<Text, BytesWritable> getFull(HStoreKey key) throws IOException {
    TreeMap<Text, BytesWritable> results = new TreeMap<Text, BytesWritable>();
    this.lock.obtainReadLock();
    try {
      internalGetFull(memcache, key, results);
      for(int i = history.size()-1; i >= 0; i--) {
        TreeMap<HStoreKey, BytesWritable> cur = history.elementAt(i);
        internalGetFull(cur, key, results);
      }
      return results;
      
    } finally {
      this.lock.releaseReadLock();
    }
  }
  
  void internalGetFull(TreeMap<HStoreKey, BytesWritable> map, HStoreKey key, 
      TreeMap<Text, BytesWritable> results) {
    
    SortedMap<HStoreKey, BytesWritable> tailMap = map.tailMap(key);
    
    for(Iterator<HStoreKey> it = tailMap.keySet().iterator(); it.hasNext(); ) {
      HStoreKey itKey = it.next();
      Text itCol = itKey.getColumn();

      if(results.get(itCol) == null
          && key.matchesWithoutColumn(itKey)) {
        BytesWritable val = tailMap.get(itKey);
        results.put(itCol, val);
        
      } else if(key.getRow().compareTo(itKey.getRow()) > 0) {
        break;
      }
    }
  }

  /**
   * Examine a single map for the desired key.
   *
   * We assume that all locking is done at a higher-level. No locking within 
   * this method.
   *
   * TODO - This is kinda slow.  We need a data structure that allows for 
   * proximity-searches, not just precise-matches.
   */    
  Vector<BytesWritable> get(TreeMap<HStoreKey, BytesWritable> map, HStoreKey key, int numVersions) {
    Vector<BytesWritable> result = new Vector<BytesWritable>();
    HStoreKey curKey = new HStoreKey(key.getRow(), key.getColumn(), key.getTimestamp());
    SortedMap<HStoreKey, BytesWritable> tailMap = map.tailMap(curKey);

    for(Iterator<HStoreKey> it = tailMap.keySet().iterator(); it.hasNext(); ) {
      HStoreKey itKey = it.next();
      
      if(itKey.matchesRowCol(curKey)) {
        result.add(tailMap.get(itKey));
        curKey.setVersion(itKey.getTimestamp() - 1);
      }
      
      if(numVersions > 0 && result.size() >= numVersions) {
        break;
      }
    }
    return result;
  }

  /**
   * Return a scanner over the keys in the HMemcache
   */
  public HInternalScannerInterface getScanner(long timestamp, Text targetCols[], Text firstRow)
      throws IOException {
    
    return new HMemcacheScanner(timestamp, targetCols, firstRow);
  }

  //////////////////////////////////////////////////////////////////////////////
  // HMemcacheScanner implements the HScannerInterface.
  // It lets the caller scan the contents of the Memcache.
  //////////////////////////////////////////////////////////////////////////////

  class HMemcacheScanner extends HAbstractScanner {
    TreeMap<HStoreKey, BytesWritable> backingMaps[];
    Iterator<HStoreKey> keyIterators[];

    @SuppressWarnings("unchecked")
    public HMemcacheScanner(long timestamp, Text targetCols[], Text firstRow)
        throws IOException {
      
      super(timestamp, targetCols);
      
      lock.obtainReadLock();
      try {
        this.backingMaps = new TreeMap[history.size() + 1];
        
        //NOTE: Since we iterate through the backing maps from 0 to n, we need
        //      to put the memcache first, the newest history second, ..., etc.
        
        backingMaps[0] = memcache;
        for(int i = history.size() - 1; i > 0; i--) {
          backingMaps[i] = history.elementAt(i);
        }

        this.keyIterators = new Iterator[backingMaps.length];
        this.keys = new HStoreKey[backingMaps.length];
        this.vals = new BytesWritable[backingMaps.length];

        // Generate list of iterators

        HStoreKey firstKey = new HStoreKey(firstRow);
        for(int i = 0; i < backingMaps.length; i++) {
          if(firstRow.getLength() != 0) {
            keyIterators[i] = backingMaps[i].tailMap(firstKey).keySet().iterator();
            
          } else {
            keyIterators[i] = backingMaps[i].keySet().iterator();
          }
          
          while(getNext(i)) {
            if(! findFirstRow(i, firstRow)) {
              continue;
            }
            if(columnMatch(i)) {
              break;
            }
          }
        }
        
      } catch(Exception ex) {
        close();
      }
    }

    /**
     * The user didn't want to start scanning at the first row. This method
     * seeks to the requested row.
     *
     * @param i         - which iterator to advance
     * @param firstRow  - seek to this row
     * @return          - true if this is the first row
     */
    boolean findFirstRow(int i, Text firstRow) {
      return ((firstRow.getLength() == 0)
          || (keys[i].getRow().toString().startsWith(firstRow.toString())));
    }
    
    /**
     * Get the next value from the specified iterater.
     * 
     * @param i - which iterator to fetch next value from
     * @return - true if there is more data available
     */
    boolean getNext(int i) {
      if(! keyIterators[i].hasNext()) {
        closeSubScanner(i);
        return false;
      }
      this.keys[i] = keyIterators[i].next();
      this.vals[i] = backingMaps[i].get(keys[i]);
      return true;
    }

    /** Shut down an individual map iterator. */
    void closeSubScanner(int i) {
      keyIterators[i] = null;
      keys[i] = null;
      vals[i] = null;
      backingMaps[i] = null;
    }

    /** Shut down map iterators, and release the lock */
    public void close() throws IOException {
      if(! scannerClosed) {
        try {
          for(int i = 0; i < keys.length; i++) {
            if(keyIterators[i] != null) {
              closeSubScanner(i);
            }
          }
          
        } finally {
          lock.releaseReadLock();
          scannerClosed = true;
        }
      }
    }
  }
}
