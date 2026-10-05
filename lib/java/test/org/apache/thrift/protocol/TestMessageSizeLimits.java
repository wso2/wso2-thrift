/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.thrift.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.AutoExpandingBufferReadTransport;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransportException;

import junit.framework.TestCase;

/**
 * Tests that message size limits are enforced in the fast-path buffer reads of
 * TBinaryProtocol/TCompactProtocol and that consumeBuffer() correctly tracks consumed bytes.
 * Ported from upstream 16b36738; JUnit 3 style to match this tree.
 */
public class TestMessageSizeLimits extends TestCase {

  private static TConfiguration maxMessageSize(int max) {
    return new TConfiguration(max, TConfiguration.DEFAULT_MAX_FRAME_SIZE, TConfiguration.DEFAULT_RECURSION_DEPTH);
  }

  /** TBinaryProtocol-encoded string: 4-byte big-endian length + raw bytes. */
  private byte[] encodeBinaryString(String s) {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[4 + data.length];
    int len = data.length;
    out[0] = (byte) (len >> 24);
    out[1] = (byte) (len >> 16);
    out[2] = (byte) (len >> 8);
    out[3] = (byte) len;
    System.arraycopy(data, 0, out, 4, data.length);
    return out;
  }

  /** TCompactProtocol-encoded string: varint length + raw bytes (length < 128). */
  private byte[] encodeCompactString(String s) {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[1 + data.length];
    out[0] = (byte) data.length;
    System.arraycopy(data, 0, out, 1, data.length);
    return out;
  }

  private static String repeat(char c, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  public void testBinaryProtocol_stringLengthLimitEnforcedInFastPath() throws Exception {
    byte[] buf = encodeBinaryString(repeat('A', 100));
    TBinaryProtocol proto = new TBinaryProtocol(new TMemoryInputTransport(buf), 10L, -1L, false, true);
    try {
      proto.readString();
      fail("stringLengthLimit must be enforced even when the fast path is taken");
    } catch (TProtocolException expected) {
    }
  }

  public void testBinaryProtocol_stringLengthLimitAllowsValidString() throws Exception {
    byte[] buf = encodeBinaryString("Hello");
    TBinaryProtocol proto = new TBinaryProtocol(new TMemoryInputTransport(buf), 10L, -1L, false, true);
    assertEquals("Hello", proto.readString());
  }

  public void testCompactProtocol_stringLengthLimitEnforcedInFastPath() throws Exception {
    byte[] buf = encodeCompactString(repeat('A', 100));
    TCompactProtocol proto = new TCompactProtocol(new TMemoryInputTransport(buf), 10L, -1L);
    try {
      proto.readString();
      fail("TCompactProtocol stringLengthLimit must reject oversized strings in fast path");
    } catch (TProtocolException expected) {
    }
  }

  public void testConsumeBuffer_decrementsRemainingMessageSize() throws Exception {
    TMemoryInputTransport transport = new TMemoryInputTransport(new byte[20]);
    transport.consumeBuffer(15);
    transport.checkReadBytesAvailable(5);
    try {
      transport.checkReadBytesAvailable(6);
      fail("checkReadBytesAvailable must reflect bytes consumed via consumeBuffer");
    } catch (TTransportException expected) {
    }
  }

  public void testBinaryProtocol_fastPathReadsDrainRemainingMessageSize() throws Exception {
    TMemoryInputTransport transport = new TMemoryInputTransport(maxMessageSize(40), new byte[40]);
    TBinaryProtocol proto = new TBinaryProtocol(transport);
    for (int i = 0; i < 8; i++) {
      proto.readI32();
    }
    transport.checkReadBytesAvailable(8);
    try {
      transport.checkReadBytesAvailable(9);
      fail("fast-path reads must decrement remaining message size so limits can be enforced");
    } catch (TTransportException expected) {
    }
  }

  public void testRead_doesNotDoubleCountConsumedBytes() throws Exception {
    TMemoryInputTransport transport = new TMemoryInputTransport(new byte[20]);
    transport.read(new byte[10], 0, 10);
    transport.checkReadBytesAvailable(10);
    try {
      transport.checkReadBytesAvailable(11);
      fail("read() must not double-count consumed bytes after consumeBuffer fix");
    } catch (TTransportException expected) {
    }
  }

  public void testAutoExpandingBufferReadTransport_fillResetsMessageSizePerFrame() throws Exception {
    AutoExpandingBufferReadTransport readBuf = new AutoExpandingBufferReadTransport(maxMessageSize(80), 100);
    TMemoryInputTransport source = new TMemoryInputTransport(new byte[200]);
    readBuf.fill(source, 70);
    readBuf.consumeBuffer(70);
    readBuf.fill(source, 70);
    readBuf.consumeBuffer(70);
    readBuf.checkReadBytesAvailable(10);
    try {
      readBuf.checkReadBytesAvailable(11);
      fail("remaining budget after fill-reset and 70-byte consume should be exactly 10");
    } catch (TTransportException expected) {
    }
  }
}
