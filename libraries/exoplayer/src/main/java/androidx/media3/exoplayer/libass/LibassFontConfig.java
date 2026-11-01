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

import android.util.Xml;
import androidx.media3.common.util.UnstableApi;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.xmlpull.v1.XmlSerializer;

/** Writes the fontconfig configuration used by the libass subtitle renderer. */
@UnstableApi
public final class LibassFontConfig {

  private LibassFontConfig() {}

  /**
   * Writes a fontconfig configuration file.
   *
   * @param output The file the configuration is written to.
   * @param cacheDirectory The directory fontconfig may use to store its caches.
   * @param fontDirectories The additional directories fonts are loaded from.
   * @throws IOException If the configuration could not be written.
   */
  public static void write(File output, File cacheDirectory, List<File> fontDirectories)
      throws IOException {
    byte[] data = toBytes(cacheDirectory, fontDirectories);
    File parent = output.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
      throw new IOException("Unable to create directory " + parent);
    }
    OutputStream outputStream = new FileOutputStream(output);
    try {
      outputStream.write(data);
    } finally {
      try {
        outputStream.close();
      } catch (IOException e) {
        // Ignored.
      }
    }
  }

  private static byte[] toBytes(File cacheDirectory, List<File> fontDirectories) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    XmlSerializer serializer = Xml.newSerializer();
    serializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
    serializer.startDocument(StandardCharsets.UTF_8.name(), Boolean.TRUE);
    serializer.startTag(null, "fontconfig");
    writeTextElement(serializer, "dir", "/system/fonts/");
    writeTextElement(serializer, "dir", "/product/fonts/");
    for (int i = 0; i < fontDirectories.size(); i++) {
      File fontDirectory = fontDirectories.get(i);
      if (fontDirectory != null && fontDirectory.isDirectory()) {
        writeTextElement(serializer, "dir", fontDirectory.getAbsolutePath());
      }
    }
    writeTextElement(serializer, "cachedir", cacheDirectory.getAbsolutePath());
    writeAlias(serializer, "serif", "Noto Serif");
    writeAlias(serializer, "sans-serif", "Roboto", "Noto Sans");
    writeAlias(serializer, "monospace", "Droid Sans Mono");
    serializer.endTag(null, "fontconfig");
    serializer.endDocument();
    serializer.flush();
    return outputStream.toByteArray();
  }

  private static void writeTextElement(XmlSerializer serializer, String name, String text)
      throws IOException {
    serializer.startTag(null, name);
    serializer.text(text);
    serializer.endTag(null, name);
  }

  private static void writeAlias(XmlSerializer serializer, String family, String... preferred)
      throws IOException {
    serializer.startTag(null, "alias");
    writeTextElement(serializer, "family", family);
    serializer.startTag(null, "prefer");
    for (String preferredFamily : preferred) {
      writeTextElement(serializer, "family", preferredFamily);
    }
    serializer.endTag(null, "prefer");
    serializer.endTag(null, "alias");
  }
}
