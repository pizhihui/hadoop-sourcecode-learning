package org.apache.hadoop.dfs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetworkTopology;

import junit.framework.TestCase;

public class TestReplicationPolicy extends TestCase {
  private static final int BLOCK_SIZE = 1024;
  private static final int NUM_OF_DATANODES = 6;
  private static final Configuration CONF = new Configuration();
  private static final NetworkTopology cluster;
  private static NameNode namenode;
  private static ReplicationTargetChooser replicator;
  private static DatanodeDescriptor dataNodes[] = 
    new DatanodeDescriptor[] {
      new DatanodeDescriptor(new DatanodeID("h1:5020", "0", -1), "/d1/r1"),
      new DatanodeDescriptor(new DatanodeID("h2:5020", "0", -1), "/d1/r1"),
      new DatanodeDescriptor(new DatanodeID("h3:5020", "0", -1), "/d1/r2"),
      new DatanodeDescriptor(new DatanodeID("h4:5020", "0", -1), "/d1/r2"),
      new DatanodeDescriptor(new DatanodeID("h5:5020", "0", -1), "/d2/r3"),
      new DatanodeDescriptor(new DatanodeID("h6:5020", "0", -1), "/d2/r3")
    };
   
  private final static DatanodeDescriptor NODE = 
    new DatanodeDescriptor(new DatanodeID("h7:5020", "0", -1), "/d2/r4");
  
  static {
    try {
      CONF.set("fs.default.name", "localhost:0");
      NameNode.format(CONF);
      namenode = new NameNode(CONF);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    FSNamesystem fsNamesystem = FSNamesystem.getFSNamesystem();
    replicator = fsNamesystem.replicator;
    cluster = fsNamesystem.clusterMap;
    // construct network topology
    for(int i=0; i<NUM_OF_DATANODES; i++) {
      cluster.add(dataNodes[i]);
    }
    for(int i=0; i<NUM_OF_DATANODES; i++) {
      dataNodes[i].updateHeartbeat(
                                   2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                   2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0);
    }
  }
  
  /**
   * In this testcase, client is dataNodes[0]. So the 1st replica should be
   * placed on dataNodes[0], the 2nd replica should be placed on dataNodes[1],
   * and the rest should be placed on different racks.
   * The only excpetion is when the <i>numOfReplicas</i> is 2, the 1st is on
   * dataNodes[0] and the 2nd is on a different rack.
   * @throws Exception
   */
  public void testChooseTarget1() throws Exception {
    dataNodes[0].updateHeartbeat(
                                 2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                 FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 4); // overloaded

    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertEquals(targets[0], dataNodes[0]);
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertEquals(targets[0], dataNodes[0]);
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      3, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    assertEquals(targets[0], dataNodes[0]);
    assertTrue(cluster.isOnSameRack(targets[0], targets[1]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[2]));
    
    targets = replicator.chooseTarget(
                                      4, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 4);
    assertEquals(targets[0], dataNodes[0]);
    assertTrue(cluster.isOnSameRack(targets[0], targets[1]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[2]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[3]));

    dataNodes[0].updateHeartbeat(
                                 2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                 FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0); 
  }

  /**
   * In this testcase, client is dataNodes[0], but the dataNodes[1] is
   * not allowed to be choosen. So the 1st replica should be
   * placed on dataNodes[0], the 2nd replica should be placed on a different
   * rack, the 3rd should the same rack as the 3nd replic, and the rest
   * should be placed on a third rack.
   * @throws Exception
   */
  public void testChooseTarget2() throws Exception { 
    List<DatanodeDescriptor> excludedNodes;
    DatanodeDescriptor[] targets;
    
    excludedNodes = new ArrayList<DatanodeDescriptor>();
    excludedNodes.add(dataNodes[1]); 
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], excludedNodes, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    excludedNodes = new ArrayList<DatanodeDescriptor>();
    excludedNodes.add(dataNodes[1]); 
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], excludedNodes, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertEquals(targets[0], dataNodes[0]);
    
    excludedNodes = new ArrayList<DatanodeDescriptor>();
    excludedNodes.add(dataNodes[1]); 
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], excludedNodes, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertEquals(targets[0], dataNodes[0]);
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    excludedNodes = new ArrayList<DatanodeDescriptor>();
    excludedNodes.add(dataNodes[1]); 
    targets = replicator.chooseTarget(
                                      3, dataNodes[0], excludedNodes, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    assertEquals(targets[0], dataNodes[0]);
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    assertTrue(cluster.isOnSameRack(targets[1], targets[2]));
    
    excludedNodes = new ArrayList<DatanodeDescriptor>();
    excludedNodes.add(dataNodes[1]); 
    targets = replicator.chooseTarget(
                                      4, dataNodes[0], excludedNodes, BLOCK_SIZE);
    assertEquals(targets.length, 4);
    assertEquals(targets[0], dataNodes[0]);
    for(int i=1; i<4; i++) {
      assertFalse(cluster.isOnSameRack(targets[0], targets[i]));
    }
    assertTrue(cluster.isOnSameRack(targets[1], targets[2]) ||
               cluster.isOnSameRack(targets[2], targets[3]));
    assertFalse(cluster.isOnSameRack(targets[1], targets[3]));
  }

  /**
   * In this testcase, client is dataNodes[0], but dataNodes[0] is not qualified
   * to be choosen. So the 1st replica should be placed on dataNodes[1], 
   * the 2nd replica should be placed on a different rack,
   * the 3rd replica should be placed on the same rack as the 2nd replica,
   * and the rest should be placed on the third rack.
   * @throws Exception
   */
  public void testChooseTarget3() throws Exception {
    // make data node 0 to be not qualified to choose
    dataNodes[0].updateHeartbeat(
                                 2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                 (FSConstants.MIN_BLOCKS_FOR_WRITE-1)*BLOCK_SIZE, 0); // no space
        
    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertEquals(targets[0], dataNodes[1]);
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertEquals(targets[0], dataNodes[1]);
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      3, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    assertEquals(targets[0], dataNodes[1]);
    assertTrue(cluster.isOnSameRack(targets[1], targets[2]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      4, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 4);
    assertEquals(targets[0], dataNodes[1]);
    for(int i=1; i<4; i++) {
      assertFalse(cluster.isOnSameRack(targets[0], targets[i]));
    }
    assertTrue(cluster.isOnSameRack(targets[1], targets[2]) ||
               cluster.isOnSameRack(targets[2], targets[3]));
    assertFalse(cluster.isOnSameRack(targets[1], targets[3]));

    dataNodes[0].updateHeartbeat(
                                 2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                 FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0); 
  }
  
  /**
   * In this testcase, client is dataNodes[0], but none of the nodes on rack 1
   * is qualified to be choosen. So the 1st replica should be placed on either
   * rack 2 or rack 3. 
   * the 2nd replica should be placed on a different rack,
   * the 3rd replica should be placed on the same rack as the 1st replica,
   * @throws Exception
   */
  public void testChoooseTarget4() throws Exception {
    // make data node 0 & 1 to be not qualified to choose: not enough disk space
    for(int i=0; i<2; i++) {
      dataNodes[i].updateHeartbeat(
                                   2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                   (FSConstants.MIN_BLOCKS_FOR_WRITE-1)*BLOCK_SIZE, 0);
    }
      
    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertFalse(cluster.isOnSameRack(targets[0], dataNodes[0]));
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertFalse(cluster.isOnSameRack(targets[0], dataNodes[0]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      3, dataNodes[0], null, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    for(int i=0; i<3; i++) {
      assertFalse(cluster.isOnSameRack(targets[i], dataNodes[0]));
    }
    assertTrue(cluster.isOnSameRack(targets[0], targets[1]) ||
               cluster.isOnSameRack(targets[1], targets[2]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[2]));
    
    for(int i=0; i<2; i++) {
      dataNodes[i].updateHeartbeat(
                                   2*FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 
                                   FSConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0);
    }
  }
  /**
   * In this testcase, client is is a node outside of file system.
   * So the 1st replica can be placed on any node. 
   * the 2nd replica should be placed on a different rack,
   * the 3rd replica should be placed on the same rack as the 1st replica,
   * @throws Exception
   */
  public void testChooseTarget5() throws Exception {
    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, NODE, null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, NODE, null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    
    targets = replicator.chooseTarget(
                                      2, NODE, null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertFalse(cluster.isOnSameRack(targets[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      3, NODE, null, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    assertTrue(cluster.isOnSameRack(targets[0], targets[1]));
    assertFalse(cluster.isOnSameRack(targets[0], targets[2]));    
  }
  
  /**
   * This testcase tests re-replication, when dataNodes[0] is already choosen.
   * So the 1st replica can be placed on rack 1. 
   * the 2nd replica should be placed on a different rack,
   * the 3rd replica can be placed randomly,
   * @throws Exception
   */
  public void testRereplicate1() throws Exception {
    List<DatanodeDescriptor> choosenNodes = new ArrayList<DatanodeDescriptor>();
    choosenNodes.add(dataNodes[0]);    
    DatanodeDescriptor[] targets;
    
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[0]));
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertTrue(cluster.isOnSameRack(dataNodes[0], targets[0]));
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[1]));
    
    targets = replicator.chooseTarget(
                                      3, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 3);
    assertTrue(cluster.isOnSameRack(dataNodes[0], targets[0]));
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[1]));
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[2]));    
  }

  /**
   * This testcase tests re-replication, 
   * when dataNodes[0] and dataNodes[1] are already choosen.
   * So the 1st replica should be placed on a different rack than rack 1. 
   * the rest replicas can be placed randomly,
   * @throws Exception
   */
  public void testRereplicate2() throws Exception {
    List<DatanodeDescriptor> choosenNodes = new ArrayList<DatanodeDescriptor>();
    choosenNodes.add(dataNodes[0]);
    choosenNodes.add(dataNodes[1]);

    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[0]));
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[0]));
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[1]));
  }

  /**
   * This testcase tests re-replication, 
   * when dataNodes[0] and dataNodes[2] are already choosen.
   * So the 1st replica should be placed on rack 1. 
   * the rest replicas can be placed randomly,
   * @throws Exception
   */
  public void testRereplicate3() throws Exception {
    List<DatanodeDescriptor> choosenNodes = new ArrayList<DatanodeDescriptor>();
    choosenNodes.add(dataNodes[0]);
    choosenNodes.add(dataNodes[2]);
    
    DatanodeDescriptor[] targets;
    targets = replicator.chooseTarget(
                                      0, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 0);
    
    targets = replicator.chooseTarget(
                                      1, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 1);
    assertTrue(cluster.isOnSameRack(dataNodes[0], targets[0]));
    
    targets = replicator.chooseTarget(
                                      2, dataNodes[0], choosenNodes, null, BLOCK_SIZE);
    assertEquals(targets.length, 2);
    assertTrue(cluster.isOnSameRack(dataNodes[0], targets[0]));
    assertFalse(cluster.isOnSameRack(dataNodes[0], targets[1]));
  }
}
