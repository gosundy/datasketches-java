/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.quantiles;

import static com.yahoo.memory.UnsafeUtil.unsafe;
import static com.yahoo.sketches.Family.idToFamily;
import static com.yahoo.sketches.quantiles.Util.LS;
import static com.yahoo.sketches.quantiles.Util.computeRetainedItems;

import java.nio.ByteOrder;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;

//@formatter:off

/**
 * This class defines the preamble data structure and provides basic utilities for some of the key
 * fields.
 * <p>The intent of the design of this class was to isolate the detailed knowledge of the bit and 
 * byte layout of the serialized form of the sketches derived from the Sketch class into one place.
 * This allows the possibility of the introduction of different serialization 
 * schemes with minimal impact on the rest of the library.</p>
 *  
 * <p>
 * MAP: Low significance bytes of this <i>long</i> data structure are on the right. However, the
 * multi-byte integers (<i>int</i> and <i>long</i>) are stored in native byte order. The
 * <i>byte</i> values are treated as unsigned.</p>
 * 
 * <p>An empty QuantilesSketch only requires 8 bytes. All others require 24 bytes of preamble.</p> 
 * 
 * <pre>
 * Long || Start Byte Adr: Common for both DoublesSketch and ItemsSketch
 * Adr: 
 *      ||    7   |    6   |    5   |    4   |    3   |    2   |    1   |     0          |
 *  0   ||------SerDeId----|--------K--------|  Flags | FamID  | SerVer | Preamble_Longs |
 *  
 *      ||   15   |   14   |   13   |   12   |   11   |   10   |    9   |     8          |
 *  1   ||-----------------------------------N_LONG--------------------------------------|
 *  
 *  Applies only to DoublesSketch:
 *  
 *      ||   23   |   22   |   21   |   20   |   19   |   18   |   17   |    16          |
 *  2   ||---------------------------START OF DATA, MIN_DOUBLE---------------------------|
 *
 *      ||   31   |   30   |   29   |   28   |   27   |   26   |   25   |    24          |
 *  3   ||----------------------------------MAX_DOUBLE-----------------------------------|
 *
 *      ||   39   |   38   |   37   |   36   |   35   |   34   |   33   |    32          |
 *  4   ||---------------------------------REST OF DATA----------------------------------|
 *  </pre>
 *  
 *  @author Lee Rhodes
 */
final class PreambleUtil {

  private PreambleUtil() {}

  // ###### DO NOT MESS WITH THIS FROM HERE ...
  // Preamble byte Addresses
  static final int PREAMBLE_LONGS_BYTE        = 0;
  static final int SER_VER_BYTE               = 1;
  static final int FAMILY_BYTE                = 2;
  static final int FLAGS_BYTE                 = 3;
  static final int K_SHORT                    = 4;  //to 5
  static final int SER_DE_ID_SHORT            = 6;  //to 7 
  static final int N_LONG                     = 8;  //to 15
  
  //After Preamble:
  static final int MIN_DOUBLE                 = 16; //to 23 (Only for DoublesSketch)
  static final int MAX_DOUBLE                 = 24; //to 31 (Only for DoublesSketch)
  static final int COMBINED_BUFFER            = 32; //to 39 (Only for DoublesSketch)
  
  //Specific values for this implementation
  static final int SER_VER                    = 2;

  // flag bit masks
  static final int BIG_ENDIAN_FLAG_MASK       = 1;
  //static final int READ_ONLY_FLAG_MASK        = 2;   //reserved
  static final int EMPTY_FLAG_MASK            = 4;
  //static final int COMPACT_FLAG_MASK          = 8;   //reserved
  //static final int ORDERED_FLAG_MASK          = 16;  //reserved
  
  static final boolean NATIVE_ORDER_IS_BIG_ENDIAN  = 
      (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
  
  // STRINGS
  /**
   * Returns a human readable string summary of the internal state of the given byte array. 
   * Used primarily in testing.
   * 
   * @param byteArr the given byte array.
   * @return the summary string.
   */
  public static String toString(byte[] byteArr) {
    Memory mem = new NativeMemory(byteArr);
    return toString(mem);
  }
  
  /**
   * Returns a human readable string summary of the internal state of the given Memory. 
   * Used primarily in testing.
   * 
   * @param mem the given Memory
   * @return the summary string.
   */
  public static String toString(Memory mem) {
    return memoryToString(mem);
  }

  private static String memoryToString(Memory mem) {
    //pre0
    int preLongs = (mem.getByte(PREAMBLE_LONGS_BYTE)) & 0XFF; //either 1 or 2
    int serVer = mem.getByte(SER_VER_BYTE);
    int familyID = mem.getByte(FAMILY_BYTE);
    String famName = idToFamily(familyID).toString();
    int flags = mem.getByte(FLAGS_BYTE);
    boolean bigEndian = (flags & BIG_ENDIAN_FLAG_MASK) > 0;
    String nativeOrder = ByteOrder.nativeOrder().toString();
    boolean empty = (flags & EMPTY_FLAG_MASK) > 0;
    int k = mem.getShort(K_SHORT);
    short serDeId = mem.getShort(SER_DE_ID_SHORT);
    boolean dblSkInstance = serDeId == DoublesSketch.ARRAY_OF_DOUBLES_SERDE_ID;
    long n;
    double minDouble = Double.POSITIVE_INFINITY;
    double maxDouble = Double.NEGATIVE_INFINITY;
    if (preLongs == 1) {
      n = 0;
    } else { // preLongs == 2
      n = mem.getLong(N_LONG);
      if (dblSkInstance) {
        minDouble = mem.getDouble(MIN_DOUBLE);
        maxDouble = mem.getDouble(MAX_DOUBLE);
      }
    } 
    
    StringBuilder sb = new StringBuilder();
    sb.append(LS);
    sb.append("### QUANTILES SKETCH PREAMBLE SUMMARY:").append(LS);
    sb.append("Byte  0: Preamble Longs       : ").append(preLongs).append(LS);
    sb.append("Byte  1: Serialization Version: ").append(serVer).append(LS);
    sb.append("Byte  2: Family               : ").append(famName).append(LS);
    sb.append("Byte  3: Flags Field          : ").append(String.format("%02o", flags)).append(LS);
    sb.append("  BIG_ENDIAN_STORAGE          : ").append(bigEndian).append(LS);
    sb.append("  (Native Byte Order)         : ").append(nativeOrder).append(LS);
    sb.append("  EMPTY                       : ").append(empty).append(LS);
    sb.append("Bytes  4-5  : K               : ").append(k).append(LS);
    sb.append("Bytes  6-7  : SerDeId         : ").append(serDeId).append(LS);
    if (preLongs == 1) {
      sb.append(" --ABSENT, ASSUMED:").append(LS);
    }
    sb.append("Bytes  8-15 : N                : ").append(n).append(LS);
    if (dblSkInstance) {
      sb.append("Bytes 16-23 : Min Double       : ").append(minDouble).append(LS);
      sb.append("Bytes 24-31 : Max Double       : ").append(maxDouble).append(LS);
    }
    sb.append("Retained Items                 : ").append(computeRetainedItems(k, n)).append(LS);
    sb.append("Total Bytes                    : ").append(mem.getCapacity()).append(LS);
    sb.append("### END SKETCH PREAMBLE SUMMARY").append(LS);
    return sb.toString();
  }
  
//@formatter:on

  static int extractPreLongs(final Object arr, final long cumOffset) {
    return unsafe.getByte(arr, cumOffset + PREAMBLE_LONGS_BYTE) & 0XFF;
  }

  static int extractSerVer(final Object arr, final long cumOffset) {
    return unsafe.getByte(arr, cumOffset + SER_VER_BYTE) & 0XFF;
  }

  static int extractFamilyID(final Object arr, final long cumOffset) {
    return unsafe.getByte(arr, cumOffset + FAMILY_BYTE) & 0XFF;
  }

  static int extractFlags(final Object arr, final long cumOffset) {
    return unsafe.getByte(arr, cumOffset + FLAGS_BYTE) & 0XFF;
  }

  static int extractK(final Object arr, final long cumOffset) {
    return unsafe.getShort(arr, cumOffset + K_SHORT) & 0XFFFF;
  }

  static short extractSerDeId(final Object arr, final long cumOffset) {
    return unsafe.getShort(arr, cumOffset + SER_DE_ID_SHORT);
  }
  
  static long extractN(final Object arr, final long cumOffset) {
    return unsafe.getLong(arr, cumOffset + N_LONG);
  }
  
  static double extractMinDouble(final Object arr, final long cumOffset) {
    return unsafe.getDouble(arr, cumOffset + MIN_DOUBLE);
  }
  
  static double extractMaxDouble(final Object arr, final long cumOffset) {
    return unsafe.getDouble(arr, cumOffset + MAX_DOUBLE);
  }

  static void insertPreLongs(Object arr, long cumOffset, int value) {
    unsafe.putByte(arr, cumOffset + PREAMBLE_LONGS_BYTE, (byte) value);
  }
  
  static void insertSerVer(Object arr, long cumOffset, int value) {
    unsafe.putByte(arr, cumOffset + SER_VER_BYTE, (byte) value);
  }
  
  static void insertFamilyID(Object arr, long cumOffset, int value) {
    unsafe.putByte(arr, cumOffset + FAMILY_BYTE, (byte) value);
  }
  
  static void insertFlags(Object arr, long cumOffset, int value) {
    unsafe.putByte(arr, cumOffset + FLAGS_BYTE, (byte) value);
  }
  
  static void insertK(Object arr, long cumOffset, int value) {
    unsafe.putShort(arr, cumOffset + K_SHORT, (short) value);
  }
  
  static void insertSerDeId(Object arr, long cumOffset, int value) {
    unsafe.putShort(arr, cumOffset + SER_DE_ID_SHORT, (short) value);
  }
  
  static void insertN(Object arr, long cumOffset, long value) {
    unsafe.putLong(arr, cumOffset + N_LONG, value);
  }
  
  static void insertMinDouble(Object arr, long cumOffset, double value) {
    unsafe.putDouble(arr, cumOffset + MIN_DOUBLE, value);
  }
  
  static void insertMaxDouble(Object arr, long cumOffset, double value) {
    unsafe.putDouble(arr, cumOffset + MAX_DOUBLE, value);
  }
}
