package org.apache.hadoop.hdfs.server.namenode;

import junit.framework.TestCase;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;

import java.io.IOException;

public class TestDFSConcurrentFileOperations extends TestCase {

  MiniDFSCluster cluster;
  FileSystem fs;
  private int writeSize;
  private long blockSize;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    writeSize = 64 * 1024;
    blockSize = 2 * writeSize;
  }

  private void init() throws IOException {
    init(new Configuration());
  }

  private void init(Configuration conf) throws IOException {
    cluster = new MiniDFSCluster(conf, 3, true, new String[]{"/rack1", "/rack2", "/rack1"});
    cluster.waitClusterUp();
    fs = cluster.getFileSystem();
  }

  @Override
  protected void tearDown() throws Exception {
    fs.close();
    cluster.shutdown();
    super.tearDown();
  }

  /*
   * test case: 
   * 1. file is opened
   * 2. file is moved while being written to (including move to trash on delete)
   * 3. blocks complete and are finalized
   * 4. close fails
   * 5. lease recovery tries to finalize blocks and should succeed
   */
  public void testLeaseRecoveryOnTrashedFile() throws Exception {
    Configuration conf = new Configuration();
    
    conf.setLong("dfs.block.size", blockSize);
    
    init(conf);
    
    String src = "/file-1";
    String dst = "/file-2";
    Path srcPath = new Path(src);
    Path dstPath = new Path(dst);
    FSDataOutputStream fos = fs.create(srcPath);
    
    fos.write(new byte[writeSize]);
    fos.sync();
    
    LocatedBlocks blocks;
    int i = 0;
    do {
      blocks = cluster
        .getNameNode()
        .getNamesystem()
        .getBlockLocations(src, 0, writeSize);
    } while (blocks.getLocatedBlocks().isEmpty() && ++i < 1000);
    
    assertTrue("failed to get block for file", i < 1000);

    Block block = blocks.get(blocks.getLocatedBlocks().size()-1).getBlock();
    
    // renaming a file out from under a client will cause close to fail
    // and result in the lease remaining while the blocks are finalized on
    // the DNs
    fs.rename(srcPath, dstPath);

    try {
      fos.close();
      fail("expected IOException");
    } catch (IOException e) {
      //expected
    }

    // simulate what lease recovery does--tries to update block and finalize
    cluster.getDataNodes().get(0).updateBlock(block, block, true);
  }
}
