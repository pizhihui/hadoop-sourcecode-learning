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
import org.apache.hadoop.ipc.*;
import org.apache.hadoop.conf.*;

import java.io.*;
import java.util.*;

/*******************************************************************************
 * HClient manages a connection to a single HRegionServer.
 ******************************************************************************/
public class HClient extends HGlobals implements HConstants {
  private static final Text[] metaColumns = {
    META_COLUMN_FAMILY
  };
  private static final Text startRow = new Text();
  
  private boolean closed;
  private Configuration conf;
  private HServerAddress masterLocation;
  private long clientTimeout;
  private int numTimeouts;
  private int numRetries;
  private HMasterInterface master;
  
  private class TableInfo {
    public HRegionInfo regionInfo;
    public HServerAddress serverAddress;

    TableInfo(HRegionInfo regionInfo, HServerAddress serverAddress) {
      this.regionInfo = regionInfo;
      this.serverAddress = serverAddress;
    }
  }
  
  // Map tableName -> (Map startRow -> (HRegionInfo, HServerAddress)
  
  private TreeMap<Text, TreeMap<Text, TableInfo>> tablesToServers;
  
  // For the "current" table: Map startRow -> (HRegionInfo, HServerAddress)
  
  private TreeMap<Text, TableInfo> tableServers;
  
  // Known region HServerAddress.toString() -> HRegionInterface
  
  private TreeMap<String, HRegionInterface> servers;
  
  // For row mutation operations

  private Text currentRegion;
  private HRegionInterface currentServer;
  private Random rand;
  private long clientid;

  /** Creates a new HClient */
  public HClient(Configuration conf) {
    this.closed = false;
    this.conf = conf;
    
    // Load config settings
    
    this.masterLocation = new HServerAddress(this.conf.get(MASTER_DEFAULT_NAME));
    this.clientTimeout = this.conf.getLong("hbase.client.timeout.length", 10 * 1000);
    this.numTimeouts = this.conf.getInt("hbase.client.timeout.number", 5);
    this.numRetries = this.conf.getInt("hbase.client.retries.number", 2);
    
    // Finish initialization

    this.master = null;
    this.tablesToServers = new TreeMap<Text, TreeMap<Text, TableInfo>>();
    this.tableServers = null;
    this.servers = new TreeMap<String, HRegionInterface>();
    
    // For row mutation operations
    
    this.currentRegion = null;
    this.currentServer = null;
    this.rand = new Random();
  }

  public synchronized void openTable(Text tableName) throws IOException {
    if(closed) {
      throw new IllegalStateException("client is not open");
    }

    tableServers = tablesToServers.get(tableName);
    if(tableServers == null ) {                 // We don't know where the table is
      findTableInMeta(tableName);               // Load the information from meta
    }
  }

  private void findTableInMeta(Text tableName) throws IOException {
    TreeMap<Text, TableInfo> metaServers = tablesToServers.get(META_TABLE_NAME);
    
    if(metaServers == null) {                   // Don't know where the meta is
      loadMetaFromRoot(tableName);
      if(tableName.equals(META_TABLE_NAME) || tableName.equals(ROOT_TABLE_NAME)) {
        // All we really wanted was the meta or root table
        return;
      }
      metaServers = tablesToServers.get(META_TABLE_NAME);
    }

    tableServers = new TreeMap<Text, TableInfo>();
    for(Iterator<TableInfo> i = metaServers.tailMap(tableName).values().iterator();
        i.hasNext(); ) {
      
      TableInfo t = i.next();
      
      scanOneMetaRegion(t, tableName);
    }
    tablesToServers.put(tableName, tableServers);
  }

  /*
   * Load the meta table from the root table.
   */
  private void loadMetaFromRoot(Text tableName) throws IOException {
    locateRootRegion();
    if(tableName.equals(ROOT_TABLE_NAME)) {   // All we really wanted was the root
      return;
    }
    scanRoot();
  }
  
  /*
   * Repeatedly try to find the root region by asking the HMaster for where it
   * could be.
   */
  private void locateRootRegion() throws IOException {
    if(master == null) {
      master = (HMasterInterface)RPC.getProxy(HMasterInterface.class, 
                   HMasterInterface.versionID,
                   masterLocation.getInetSocketAddress(), conf);
    }
    
    int tries = 0;
    HServerAddress rootRegionLocation = null;
    do {
      int localTimeouts = 0;
      while(rootRegionLocation == null && localTimeouts < numTimeouts) {
        rootRegionLocation = master.findRootRegion();

        if(rootRegionLocation == null) {
          try {
            Thread.sleep(clientTimeout);

          } catch(InterruptedException iex) {
          }
          localTimeouts++;
        }
      }
      if(rootRegionLocation == null) {
        throw new IOException("Timed out trying to locate root region");
      }
      
      // Verify that this server still serves the root region
      
      HRegionInterface rootRegion = getHRegionConnection(rootRegionLocation);

      if(rootRegion.getRegionInfo(rootRegionInfo.regionName) != null) {
        tableServers = new TreeMap<Text, TableInfo>();
        tableServers.put(startRow, new TableInfo(rootRegionInfo, rootRegionLocation));
        tablesToServers.put(ROOT_TABLE_NAME, tableServers);
        break;
      }
      rootRegionLocation = null;
      
    } while(rootRegionLocation == null && tries++ < numRetries);
    
    if(rootRegionLocation == null) {
      closed = true;
      throw new IOException("unable to locate root region server");
    }
  }

  /*
   * Scans the root region to find all the meta regions
   */
  private void scanRoot() throws IOException {
    tableServers = new TreeMap<Text, TableInfo>();
    TableInfo t = tablesToServers.get(ROOT_TABLE_NAME).get(startRow);
    scanOneMetaRegion(t, META_TABLE_NAME);
    tablesToServers.put(META_TABLE_NAME, tableServers);
  }

  /*
   * Scans a single meta region
   * @param t           - the table we're going to scan
   * @param tableName   - the name of the table we're looking for
   */
  private void scanOneMetaRegion(TableInfo t, Text tableName) throws IOException {
    HRegionInterface server = getHRegionConnection(t.serverAddress);
    HScannerInterface scanner = null;
    try {
      scanner = server.openScanner(t.regionInfo.regionName, metaColumns, tableName);
      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
      DataInputBuffer inbuf = new DataInputBuffer();

      while(scanner.next(key, results)) {
        byte hRegionInfoBytes[] = results.get(META_COL_REGIONINFO);
        inbuf.reset(hRegionInfoBytes, hRegionInfoBytes.length);
        HRegionInfo regionInfo = new HRegionInfo();
        regionInfo.readFields(inbuf);
        
        if(! regionInfo.tableDesc.getName().equals(tableName)) {
          // We're done
          break;
        }
                    
        byte serverBytes[] = results.get(META_COL_SERVER);
        String serverName = new String(serverBytes, UTF8_ENCODING);
          
        tableServers.put(regionInfo.startKey, 
            new TableInfo(regionInfo, new HServerAddress(serverName)));

        results.clear();
      }
    } finally {
      scanner.close();
    }
  }

  public synchronized HRegionInterface getHRegionConnection(HServerAddress regionServer)
      throws IOException {

      // See if we already have a connection

    HRegionInterface server = servers.get(regionServer.toString());
    
    if(server == null) {                                // Get a connection
      
      server = (HRegionInterface)RPC.waitForProxy(HRegionInterface.class, 
          HRegionInterface.versionID, regionServer.getInetSocketAddress(), conf);
      
      servers.put(regionServer.toString(), server);
    }
    return server;
  }

  /** Close the connection to the HRegionServer */
  public synchronized void close() throws IOException {
    if(! closed) {
      RPC.stopClient();
      closed = true;
    }
  }

  /**
   * List all the userspace tables.  In other words, scan the META table.
   *
   * If we wanted this to be really fast, we could implement a special
   * catalog table that just contains table names and their descriptors.
   * Right now, it only exists as part of the META table's region info.
   */
  public HTableDescriptor[] listTables() throws IOException {
    TreeSet<HTableDescriptor> uniqueTables = new TreeSet<HTableDescriptor>();
    
    TreeMap<Text, TableInfo> metaTables = tablesToServers.get(META_TABLE_NAME);
    if(metaTables == null) {
      // Meta is not loaded yet so go do that
      loadMetaFromRoot(META_TABLE_NAME);
      metaTables = tablesToServers.get(META_TABLE_NAME);
    }

    for(Iterator<TableInfo>i = metaTables.values().iterator(); i.hasNext(); ) {
      TableInfo t = i.next();
      HRegionInterface server = getHRegionConnection(t.serverAddress);
      HScannerInterface scanner = null;
      try {
        scanner = server.openScanner(t.regionInfo.regionName, metaColumns, startRow);
        HStoreKey key = new HStoreKey();
        TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
        DataInputBuffer inbuf = new DataInputBuffer();
        while(scanner.next(key, results)) {
          byte infoBytes[] = (byte[]) results.get(ROOT_COL_REGIONINFO);
          inbuf.reset(infoBytes, infoBytes.length);
          HRegionInfo info = new HRegionInfo();
          info.readFields(inbuf);

          // Only examine the rows where the startKey is zero length
          
          if(info.startKey.getLength() == 0) {
            uniqueTables.add(info.tableDesc);
          }
          results.clear();
        }
        
      } finally {
        scanner.close();
      }
    }
    return (HTableDescriptor[]) uniqueTables.toArray(new HTableDescriptor[uniqueTables.size()]);
  }

  private TableInfo getTableInfo(Text row) {
    if(tableServers == null) {
      throw new IllegalStateException("Must open table first");
    }
    
    // Only one server will have the row we are looking for
    
    Text serverKey = tableServers.tailMap(row).firstKey();
    return tableServers.get(serverKey);
  }
  
  /** Get a single value for the specified row and column */
  public byte[] get(Text row, Text column) throws IOException {
    TableInfo info = getTableInfo(row);
    return getHRegionConnection(info.serverAddress).get(
        info.regionInfo.regionName, row, column).get();
  }
 
  /** Get the specified number of versions of the specified row and column */
  public byte[][] get(Text row, Text column, int numVersions) throws IOException {
    TableInfo info = getTableInfo(row);
    BytesWritable[] values = getHRegionConnection(info.serverAddress).get(
        info.regionInfo.regionName, row, column, numVersions);
    
    ArrayList<byte[]> bytes = new ArrayList<byte[]>();
    for(int i = 0 ; i < values.length; i++) {
      bytes.add(values[i].get());
    }
    return bytes.toArray(new byte[values.length][]);
  }
  
  /** 
   * Get the specified number of versions of the specified row and column with
   * the specified timestamp.
   */
  public byte[][] get(Text row, Text column, long timestamp, int numVersions) throws IOException {
    TableInfo info = getTableInfo(row);
    BytesWritable[] values = getHRegionConnection(info.serverAddress).get(
        info.regionInfo.regionName, row, column, timestamp, numVersions);
    
    ArrayList<byte[]> bytes = new ArrayList<byte[]>();
    for(int i = 0 ; i < values.length; i++) {
      bytes.add(values[i].get());
    }
    return bytes.toArray(new byte[values.length][]);
  }

  /** Get all the data for the specified row */
  public LabelledData[] getRow(Text row) throws IOException {
    TableInfo info = getTableInfo(row);
    return getHRegionConnection(info.serverAddress).getRow(
        info.regionInfo.regionName, row);
  }

  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   */
  public HScannerInterface obtainScanner(Text[] columns, Text startRow) throws IOException {
    if(tableServers == null) {
      throw new IllegalStateException("Must open table first");
    }
    return new ClientScanner(columns, startRow);
  }

  /** Start an atomic row insertion or update */
  public long startUpdate(Text row) throws IOException {
    TableInfo info = getTableInfo(row);
    long lockid;
    try {
      currentServer = getHRegionConnection(info.serverAddress);
      currentRegion = info.regionInfo.regionName;
      clientid = rand.nextLong();
      lockid = currentServer.startUpdate(currentRegion, clientid, row);
      
    } catch(IOException e) {
      currentServer = null;
      currentRegion = null;
      throw e;
    }
    return lockid;
  }
  
  /** Change a value for the specified column */
  public void put(long lockid, Text column, byte val[]) throws IOException {
    try {
      currentServer.put(currentRegion, clientid, lockid, column, new BytesWritable(val));
      
    } catch(IOException e) {
      try {
        currentServer.abort(currentRegion, clientid, lockid);
        
      } catch(IOException e2) {
      }
      currentServer = null;
      currentRegion = null;
      throw e;
    }
  }
  
  /** Delete the value for a column */
  public void delete(long lockid, Text column) throws IOException {
    try {
      currentServer.delete(currentRegion, clientid, lockid, column);
      
    } catch(IOException e) {
      try {
        currentServer.abort(currentRegion, clientid, lockid);
        
      } catch(IOException e2) {
      }
      currentServer = null;
      currentRegion = null;
      throw e;
    }
  }
  
  /** Abort a row mutation */
  public void abort(long lockid) throws IOException {
    try {
      currentServer.abort(currentRegion, clientid, lockid);
      
    } catch(IOException e) {
      currentServer = null;
      currentRegion = null;
      throw e;
    }
  }
  
  /** Finalize a row mutation */
  public void commit(long lockid) throws IOException {
    try {
      currentServer.commit(currentRegion, clientid, lockid);
      
    } finally {
      currentServer = null;
      currentRegion = null;
    }
  }
  
  /**
   * Implements the scanner interface for the HBase client.
   * If there are multiple regions in a table, this scanner will iterate
   * through them all.
   */
  private class ClientScanner implements HScannerInterface {
    private Text[] columns;
    private Text startRow;
    private boolean closed;
    private TableInfo[] regions;
    private int currentRegion;
    private HRegionInterface server;
    private HScannerInterface scanner;
    
    public ClientScanner(Text[] columns, Text startRow) throws IOException {
      this.columns = columns;
      this.startRow = startRow;
      this.closed = false;
      Collection<TableInfo> info = tableServers.tailMap(startRow).values();
      this.regions = info.toArray(new TableInfo[info.size()]);
      this.currentRegion = -1;
      this.server = null;
      this.scanner = null;
      nextScanner();
    }
    
    /*
     * Gets a scanner for the next region.
     * Returns false if there are no more scanners.
     */
    private boolean nextScanner() throws IOException {
      if(scanner != null) {
        scanner.close();
      }
      currentRegion += 1;
      if(currentRegion == regions.length) {
        close();
        return false;
      }
      try {
        server = getHRegionConnection(regions[currentRegion].serverAddress);
        scanner = server.openScanner(regions[currentRegion].regionInfo.regionName,
            columns, startRow);
        
      } catch(IOException e) {
        close();
        throw e;
      }
      return true;
    }
    
    /* (non-Javadoc)
     * @see org.apache.hadoop.hbase.HScannerInterface#next(org.apache.hadoop.hbase.HStoreKey, java.util.TreeMap)
     */
    public boolean next(HStoreKey key, TreeMap<Text, byte[]> results) throws IOException {
      if(closed) {
        return false;
      }
      boolean status = scanner.next(key, results);
      if(! status) {
        status = nextScanner();
        if(status) {
          status = scanner.next(key, results);
        }
      }
      return status;
    }

    /* (non-Javadoc)
     * @see org.apache.hadoop.hbase.HScannerInterface#close()
     */
    public void close() throws IOException {
      if(scanner != null) {
        scanner.close();
      }
      server = null;
      closed = true;
    }
  }

}