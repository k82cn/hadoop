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
package org.apache.hadoop.hdfs;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.hadoop.hdfs.StripedFileTestUtil.blockSize;
import static org.apache.hadoop.hdfs.StripedFileTestUtil.numDNs;

public class TestWriteStripedFileWithFailure {
  public static final Log LOG = LogFactory
      .getLog(TestWriteStripedFileWithFailure.class);
  private static MiniDFSCluster cluster;
  private static FileSystem fs;
  private static Configuration conf = new HdfsConfiguration();
  private final short dataBlocks = StripedFileTestUtil.NUM_DATA_BLOCKS;
  private final short parityBlocks = StripedFileTestUtil.NUM_PARITY_BLOCKS;
  private final int smallFileLength = blockSize * dataBlocks - 123;
  private final int largeFileLength = blockSize * dataBlocks + 123;
  private final int[] fileLengths = {smallFileLength, largeFileLength};

  public void setup() throws IOException {
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, blockSize);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDNs).build();
    cluster.getFileSystem().getClient().setErasureCodingPolicy("/", null);
    fs = cluster.getFileSystem();
  }

  public void tearDown() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  // Test writing file with some Datanodes failure
  // TODO: enable this test after HDFS-8704 and HDFS-9040
  @Ignore
  @Test(timeout = 300000)
  public void testWriteStripedFileWithDNFailure() throws IOException {
    for (int fileLength : fileLengths) {
      for (int dataDelNum = 1; dataDelNum < 4; dataDelNum++) {
        for (int parityDelNum = 0; (dataDelNum+parityDelNum) < 4; parityDelNum++) {
          try {
            // setup a new cluster with no dead datanode
            setup();
            writeFileWithDNFailure(fileLength, dataDelNum, parityDelNum);
          } catch (IOException ioe) {
            String fileType = fileLength < (blockSize * dataBlocks) ?
                "smallFile" : "largeFile";
            LOG.error("Failed to write file with DN failure:"
                + " fileType = "+ fileType
                + ", dataDelNum = " + dataDelNum
                + ", parityDelNum = " + parityDelNum);
            throw ioe;
          } finally {
            // tear down the cluster
            tearDown();
          }
        }
      }
    }
  }

  /**
   * Test writing a file with shutting down some DNs(data DNs or parity DNs or both).
   * @param fileLength file length
   * @param dataDNFailureNum the shutdown number of data DNs
   * @param parityDNFailureNum the shutdown number of parity DNs
   * @throws IOException
   */
  private void writeFileWithDNFailure(int fileLength,
      int dataDNFailureNum, int parityDNFailureNum) throws IOException {
    String fileType = fileLength < (blockSize * dataBlocks) ?
        "smallFile" : "largeFile";
    String src = "/dnFailure_" + dataDNFailureNum + "_" + parityDNFailureNum
        + "_" + fileType;
    LOG.info("writeFileWithDNFailure: file = " + src
        + ", fileType = " + fileType
        + ", dataDNFailureNum = " + dataDNFailureNum
        + ", parityDNFailureNum = " + parityDNFailureNum);

    Path srcPath = new Path(src);
    final AtomicInteger pos = new AtomicInteger();
    final FSDataOutputStream out = fs.create(srcPath);
    final DFSStripedOutputStream stripedOut
        = (DFSStripedOutputStream)out.getWrappedStream();

    int[] dataDNFailureIndices = StripedFileTestUtil.randomArray(0, dataBlocks,
        dataDNFailureNum);
    Assert.assertNotNull(dataDNFailureIndices);
    int[] parityDNFailureIndices = StripedFileTestUtil.randomArray(dataBlocks,
        dataBlocks + parityBlocks, parityDNFailureNum);
    Assert.assertNotNull(parityDNFailureIndices);

    int[] failedDataNodes = new int[dataDNFailureNum + parityDNFailureNum];
    System.arraycopy(dataDNFailureIndices, 0, failedDataNodes,
        0, dataDNFailureIndices.length);
    System.arraycopy(parityDNFailureIndices, 0, failedDataNodes,
        dataDNFailureIndices.length, parityDNFailureIndices.length);

    final int killPos = fileLength/2;
    for (; pos.get() < fileLength; ) {
      final int i = pos.getAndIncrement();
      if (i == killPos) {
        for(int failedDn : failedDataNodes) {
          StripedFileTestUtil.killDatanode(cluster, stripedOut, failedDn, pos);
        }
      }
      write(out, i);
    }
    out.close();

    // make sure the expected number of Datanode have been killed
    int dnFailureNum = dataDNFailureNum + parityDNFailureNum;
    Assert.assertEquals(cluster.getDataNodes().size(), numDNs - dnFailureNum);

    byte[] smallBuf = new byte[1024];
    byte[] largeBuf = new byte[fileLength + 100];
    final byte[] expected = StripedFileTestUtil.generateBytes(fileLength);
    StripedFileTestUtil.verifyLength(fs, srcPath, fileLength);
    StripedFileTestUtil.verifySeek(fs, srcPath, fileLength);
    StripedFileTestUtil.verifyStatefulRead(fs, srcPath, fileLength, expected,
        smallBuf);
    StripedFileTestUtil.verifyPread(fs, srcPath, fileLength, expected, largeBuf);

    // delete the file
    fs.delete(srcPath, true);
  }

  void write(FSDataOutputStream out, int i) throws IOException {
    try {
      out.write(StripedFileTestUtil.getByte(i));
    } catch (IOException e) {
      throw new IOException("Failed at i=" + i, e);
    }
  }
}
