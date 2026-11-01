/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads information from font files supported by the libass subtitle renderer: TrueType (.ttf),
 * OpenType (.otf) and TrueType collections (.ttc).
 */
@UnstableApi
public final class LibassFontFile {

  private static final int TTF_TAG = tag("ttcf");
  private static final int NAME_TABLE_TAG = tag("name");
  private static final int FAMILY_NAME_ID = 1;
  private static final int TYPOGRAPHIC_FAMILY_NAME_ID = 16;
  private static final int PLATFORM_WINDOWS = 3;
  private static final int PLATFORM_MACINTOSH = 1;

  private LibassFontFile() {}

  /**
   * Returns the family name of the given font file.
   *
   * @param file The font file.
   * @return The family name, or null if it could not be read.
   * @throws IOException If the file could not be read.
   */
  @Nullable
  public static String getFamilyName(File file) throws IOException {
    InputStream inputStream = new FileInputStream(file);
    try {
      return getFamilyName(inputStream);
    } finally {
      try {
        inputStream.close();
      } catch (IOException e) {
        // Ignored.
      }
    }
  }

  /**
   * Returns the family name of the font data in the given stream. The stream is not closed.
   *
   * @param inputStream The stream positioned at the start of the font data.
   * @return The family name, or null if it could not be read.
   * @throws IOException If the data could not be read.
   */
  @Nullable
  public static String getFamilyName(InputStream inputStream) throws IOException {
    int firstTag = readInt32(inputStream);
    if (firstTag == TTF_TAG) {
      // TrueType collection: use the first font of the collection.
      skip(inputStream, 4);
      int offset = readInt32(inputStream);
      skip(inputStream, offset - 12);
      return readFamilyName(inputStream);
    }
    return readFamilyName(inputStream);
  }

  @Nullable
  private static String readFamilyName(InputStream inputStream) throws IOException {
    int numTables = readInt16(inputStream);
    skip(inputStream, 6);
    int nameTableOffset = -1;
    int nameTableLength = 0;
    for (int i = 0; i < numTables; i++) {
      int tag = readInt32(inputStream);
      int checksum = readInt32(inputStream);
      int offset = readInt32(inputStream);
      int length = readInt32(inputStream);
      if (tag == NAME_TABLE_TAG) {
        nameTableOffset = offset;
        nameTableLength = length;
      }
    }
    if (nameTableOffset < 0) {
      return null;
    }
    skip(inputStream, nameTableOffset - (12 + numTables * 16));
    byte[] data = readBytes(inputStream, nameTableLength);
    return parseNameTable(data);
  }

  @Nullable
  private static String parseNameTable(byte[] data) {
    if (data.length < 6) {
      return null;
    }
    int count = readInt16(data, 2);
    int stringOffset = readInt16(data, 4);
    @Nullable byte[] familyName = null;
    @Nullable byte[] typographicFamilyName = null;
    for (int i = 0; i < count; i++) {
      int recordOffset = 6 + i * 12;
      if (recordOffset + 12 > data.length) {
        return null;
      }
      int platformId = readInt16(data, recordOffset);
      int nameId = readInt16(data, recordOffset + 6);
      int length = readInt16(data, recordOffset + 8);
      int offset = readInt16(data, recordOffset + 10);
      if (nameId != FAMILY_NAME_ID && nameId != TYPOGRAPHIC_FAMILY_NAME_ID) {
        continue;
      }
      int stringStart = stringOffset + offset;
      if (stringStart < 0 || stringStart + length > data.length) {
        continue;
      }
      byte[] value = new byte[length];
      System.arraycopy(data, stringStart, value, 0, length);
      String decoded = decodeName(platformId, value);
      if (decoded == null) {
        continue;
      }
      if (nameId == FAMILY_NAME_ID && familyName == null) {
        familyName = value;
      } else if (nameId == TYPOGRAPHIC_FAMILY_NAME_ID && typographicFamilyName == null) {
        typographicFamilyName = value;
      }
    }
    String result = typographicFamilyName == null ? null : decodeAny(typographicFamilyName);
    if (result != null && !result.isEmpty()) {
      return result;
    }
    return familyName == null ? null : decodeAny(familyName);
  }

  @Nullable
  private static String decodeName(int platformId, byte[] value) {
    if (platformId == PLATFORM_WINDOWS) {
      return new String(value, StandardCharsets.UTF_16BE);
    }
    if (platformId == PLATFORM_MACINTOSH) {
      return new String(value, StandardCharsets.US_ASCII);
    }
    return null;
  }

  @Nullable
  private static String decodeAny(byte[] value) {
    String utf16 = new String(value, StandardCharsets.UTF_16BE);
    if (isPrintable(utf16)) {
      return utf16;
    }
    String ascii = new String(value, StandardCharsets.US_ASCII);
    return isPrintable(ascii) ? ascii : null;
  }

  private static boolean isPrintable(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return false;
      }
    }
    return true;
  }

  private static int tag(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    return ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
  }

  private static int readInt32(InputStream inputStream) throws IOException {
    int b1 = inputStream.read();
    int b2 = inputStream.read();
    int b3 = inputStream.read();
    int b4 = inputStream.read();
    if ((b1 | b2 | b3 | b4) < 0) {
      throw new EOFException("Unexpected end of font data");
    }
    return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
  }

  private static int readInt16(InputStream inputStream) throws IOException {
    int b1 = inputStream.read();
    int b2 = inputStream.read();
    if ((b1 | b2) < 0) {
      throw new EOFException("Unexpected end of font data");
    }
    return (b1 << 8) | b2;
  }

  private static int readInt16(byte[] data, int offset) {
    return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
  }

  private static void skip(InputStream inputStream, long length) throws IOException {
    while (length > 0) {
      long skipped = inputStream.skip(length);
      if (skipped > 0) {
        length -= skipped;
      } else {
        if (inputStream.read() < 0) {
          throw new EOFException("Unexpected end of font data");
        }
        length--;
      }
    }
  }

  private static byte[] readBytes(InputStream inputStream, int length) throws IOException {
    byte[] data = new byte[length];
    int position = 0;
    while (position < length) {
      int read = inputStream.read(data, position, length - position);
      if (read < 0) {
        throw new EOFException("Unexpected end of font data");
      }
      position += read;
    }
    return data;
  }
}
