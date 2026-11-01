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
package androidx.media3.mpvplayer;

import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads options from the mpv configuration file, following mpv's own syntax: {@code #} comments,
 * {@code option=value} pairs, quoted and {@code %length%} encoded values, and {@code [profile]}
 * sections that can be pulled in with {@code profile} and {@code include} options.
 */
public final class MpvConfigFileInspector {

  private static final String CONFIG_FILE_NAME = "mpv.conf";
  private static final int MAX_CONFIG_FILE_BYTES = 1024 * 1024;
  private static final int MAX_RESOLVE_DEPTH = 16;

  private static final String OPTION_SUB_FONTS_DIR = "sub-fonts-dir";
  private static final String OPTION_PROFILE = "profile";
  private static final String OPTION_INCLUDE = "include";

  private final File configDirectory;

  /**
   * Creates an inspector for the given mpv configuration directory.
   *
   * @param configDirectory The directory holding {@code mpv.conf}, used to resolve {@code ~~} and
   *     {@code ~~home} relative paths and to locate the default fonts directory.
   */
  public MpvConfigFileInspector(File configDirectory) {
    this.configDirectory = configDirectory.getAbsoluteFile();
  }

  /**
   * Returns the subtitle fonts directory configured through {@code sub-fonts-dir} in the {@code
   * mpv.conf} of the configuration directory.
   *
   * <p>When the option is set to an empty value, the default {@code fonts} directory inside the
   * configuration directory is returned.
   *
   * @return The configured fonts directory, or {@code null} when the configuration file is missing,
   *     unreadable, or does not set the option.
   */
  @Nullable
  public File readSubtitleFontsDirectory() {
    String content;
    try {
      content = readConfigFile(new File(configDirectory, CONFIG_FILE_NAME));
    } catch (IOException | SecurityException e) {
      return null;
    }
    if (content == null) {
      return null;
    }
    Map<String, String> effectiveOptions = resolveEffectiveOptions(content);
    if (!effectiveOptions.containsKey(OPTION_SUB_FONTS_DIR)) {
      return null;
    }
    String directory = effectiveOptions.get(OPTION_SUB_FONTS_DIR);
    return directory.isEmpty() ? new File(configDirectory, "fonts") : resolvePath(directory);
  }

  /**
   * Returns the names of the options that remain effective in the given configuration content,
   * after applying {@code profile} and {@code include} options. Options written in their negated
   * form, e.g. {@code no-ytdl}, are reported with the negated name as written.
   *
   * @param content The configuration content to inspect.
   * @return The names of the effective options.
   */
  public Set<String> getEffectiveOptionNames(CharSequence content) {
    return Collections.unmodifiableSet(
        new LinkedHashSet<>(resolveEffectiveOptions(content.toString()).keySet()));
  }

  @Nullable
  private static String readConfigFile(File file) throws IOException {
    if (!file.isFile() || file.length() > MAX_CONFIG_FILE_BYTES) {
      return null;
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (FileInputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > MAX_CONFIG_FILE_BYTES) {
          return null;
        }
        output.write(buffer, 0, count);
      }
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }

  private Map<String, String> resolveEffectiveOptions(String content) {
    Map<String, List<Option>> profilesByName = new HashMap<>();
    List<Option> options = parse(content, profilesByName);
    Map<String, String> effectiveOptions = new HashMap<>();
    resolveOptions(
        options, profilesByName, effectiveOptions, new HashSet<>(), new HashSet<>(), 0);
    return effectiveOptions;
  }

  /**
   * Parses configuration content into the options of the unnamed section, collecting the options of
   * every {@code [profile]} section encountered along the way.
   */
  private static List<Option> parse(String content, Map<String, List<Option>> profilesByName) {
    List<Option> options = new ArrayList<>();
    String string = content.toString();
    if (!string.isEmpty() && string.charAt(0) == '\uFEFF') {
      string = string.substring(1);
    }
    List<Option> currentOptions = options;
    for (String line : string.split("[\\r\\n]+")) {
      String sectionName = getSectionName(line);
      if (sectionName == null) {
        addOption(line, currentOptions);
      } else if (sectionName.isEmpty() || "default".equals(sectionName)) {
        currentOptions = options;
      } else {
        List<Option> profileOptions = profilesByName.get(sectionName);
        if (profileOptions == null) {
          profileOptions = new ArrayList<>();
          profilesByName.put(sectionName, profileOptions);
        }
        currentOptions = profileOptions;
      }
    }
    return options;
  }

  /** Returns the name of the {@code [section]} the line opens, or {@code null} for other lines. */
  @Nullable
  private static String getSectionName(String line) {
    String trimmedLine = line.trim();
    int sectionEnd;
    if (trimmedLine.startsWith("[")
        && (sectionEnd = trimmedLine.indexOf(']')) >= 0
        && !isValidOptionLine(trimmedLine.substring(sectionEnd + 1))) {
      return trimmedLine.substring(1, sectionEnd);
    }
    return null;
  }

  /**
   * Parses a single line into an option and adds it to the given list. Lines that hold no option,
   * or a value that cannot be decoded, are skipped.
   */
  private static void addOption(String line, List<Option> out) {
    String trimmedLine = line.trim();
    String name = "";
    if (!trimmedLine.isEmpty() && !trimmedLine.startsWith("#")) {
      String nameAndValue = trimmedLine.startsWith("--") ? trimmedLine.substring(2) : trimmedLine;
      int nameLength = 0;
      while (nameLength < nameAndValue.length()) {
        char c = nameAndValue.charAt(nameLength);
        if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
          break;
        }
        nameLength++;
      }
      String remainder = nameAndValue.substring(nameLength).trim();
      if (remainder.startsWith("=") || !isValidOptionLine(remainder)) {
        name = nameAndValue.substring(0, nameLength);
      }
    }
    if (name.isEmpty()) {
      return;
    }
    int equalsIndex = line.indexOf('=');
    boolean hasSeparateValue =
        equalsIndex >= 0
            || (!OPTION_SUB_FONTS_DIR.equals(name)
                && !OPTION_INCLUDE.equals(name)
                && !OPTION_PROFILE.equals(name));
    String value = "";
    if (hasSeparateValue && equalsIndex >= 0) {
      value = parseValue(line.substring(equalsIndex + 1).trim());
    }
    if (value != null) {
      out.add(new Option(name, value));
    }
  }

  /** Parses the raw text right of the {@code =} sign into a value, or {@code null} if invalid. */
  @Nullable
  private static String parseValue(String rawValue) {
    if (rawValue.isEmpty()) {
      return "";
    }
    char first = rawValue.charAt(0);
    if (first == '\'' || first == '\"') {
      int valueEnd = rawValue.indexOf(first, 1);
      if (valueEnd < 0 || isValidOptionLine(rawValue.substring(valueEnd + 1))) {
        return null;
      }
      return rawValue.substring(1, valueEnd);
    }
    if (first == '%') {
      int lengthEnd = rawValue.indexOf('%', 1);
      if (lengthEnd < 0) {
        return null;
      }
      int byteLength;
      try {
        byteLength = Integer.parseInt(rawValue.substring(1, lengthEnd));
      } catch (NumberFormatException e) {
        return null;
      }
      String remainder = rawValue.substring(lengthEnd + 1);
      int charIndex = 0;
      int length = 0;
      while (charIndex < remainder.length() && length < byteLength) {
        int codePoint = remainder.codePointAt(charIndex);
        length += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
        charIndex += Character.charCount(codePoint);
      }
      if (length != byteLength || isValidOptionLine(remainder.substring(charIndex))) {
        return null;
      }
      return remainder.substring(0, charIndex);
    }
    int commentIndex = rawValue.indexOf('#');
    if (commentIndex >= 0) {
      rawValue = rawValue.substring(0, commentIndex);
    }
    return rawValue.trim();
  }

  /** Returns whether the given text holds content, i.e. is neither empty nor a comment. */
  private static boolean isValidOptionLine(String text) {
    String trimmedText = text.trim();
    return !trimmedText.isEmpty() && !trimmedText.startsWith("#");
  }

  /**
   * Applies the given options to the effective options, recursing into referenced {@code profile}
   * sections and {@code include}d files.
   */
  private void resolveOptions(
      List<Option> options,
      Map<String, List<Option>> profilesByName,
      Map<String, String> effectiveOptions,
      Set<String> visitedProfiles,
      Set<File> visitedIncludes,
      int depth) {
    if (depth >= MAX_RESOLVE_DEPTH) {
      return;
    }
    // Iterate a snapshot: included files can extend the profiles being resolved.
    for (Option option : new ArrayList<>(options)) {
      if (OPTION_PROFILE.equals(option.name)) {
        for (String profileName : option.value.split(",")) {
          String trimmedProfileName = profileName.trim();
          if (visitedProfiles.add(trimmedProfileName)) {
            List<Option> profileOptions = profilesByName.get(trimmedProfileName);
            if (profileOptions != null) {
              resolveOptions(
                  profileOptions,
                  profilesByName,
                  effectiveOptions,
                  visitedProfiles,
                  visitedIncludes,
                  depth + 1);
            }
            visitedProfiles.remove(trimmedProfileName);
          }
        }
      } else if (OPTION_INCLUDE.equals(option.name)) {
        File includedFile = resolvePath(option.value);
        if (includedFile != null) {
          applyIncludedFile(
              includedFile,
              profilesByName,
              effectiveOptions,
              visitedProfiles,
              visitedIncludes,
              depth + 1);
        }
      } else {
        effectiveOptions.put(option.name, option.value);
      }
    }
  }

  private void applyIncludedFile(
      File file,
      Map<String, List<Option>> profilesByName,
      Map<String, String> effectiveOptions,
      Set<String> visitedProfiles,
      Set<File> visitedIncludes,
      int depth) {
    try {
      File canonicalFile = file.getCanonicalFile();
      if (!visitedIncludes.add(canonicalFile)) {
        return;
      }
      try {
        String content = readConfigFile(canonicalFile);
        if (content != null) {
          List<Option> includedOptions = parse(content, profilesByName);
          resolveOptions(
              includedOptions,
              profilesByName,
              effectiveOptions,
              visitedProfiles,
              visitedIncludes,
              depth);
        }
      } finally {
        visitedIncludes.remove(canonicalFile);
      }
    } catch (IOException | SecurityException ignored) {
      // Included files that cannot be read are skipped.
    }
  }

  /** Resolves a path written in mpv syntax against the configuration directory. */
  @Nullable
  private File resolvePath(String path) {
    if (path.isEmpty()) {
      return null;
    }
    if (path.startsWith("~~/")) {
      return new File(configDirectory, path.substring(3));
    }
    if (path.startsWith("~~home/")) {
      return new File(configDirectory, path.substring(7));
    }
    if (path.startsWith("~/")) {
      String homeDirectory = System.getenv("HOME");
      if (TextUtils.isEmpty(homeDirectory)) {
        homeDirectory = System.getenv("USERPROFILE");
      }
      if (TextUtils.isEmpty(homeDirectory)) {
        return null;
      }
      return new File(homeDirectory, path.substring(2));
    }
    if (path.startsWith("~")) {
      return null;
    }
    return new File(path).getAbsoluteFile();
  }

  private static final class Option {

    private final String name;
    private final String value;

    private Option(String name, String value) {
      this.name = name;
      this.value = value;
    }
  }
}
