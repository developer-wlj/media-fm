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
package androidx.media3.mpvplayer.nativebridge;

import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_AUDIO_FILTER;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_AUDIO_FILTER_COMMAND;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_LOAD_FILE;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_SCRIPT_BINDING;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_STOP;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.CMD_SUB_ADD;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.FILTER_OPERATION_ADD;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.FILTER_OPERATION_REMOVE;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.PROP_TIME_POS;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.STATS_GENERAL_PAGE_TOGGLE;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.VALUE_AUTO;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.VALUE_CACHED;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.VALUE_REPLACE;

import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.audio.MpvAudioFilterCommand;
import java.util.ArrayList;
import java.util.List;

public final class MpvCommandDispatcher {

  private static final String COMMAND_PREFIX_ASYNC = "async";
  private static final String LOAD_FILE_DEFAULT_INDEX = "-1";

  private final MpvClient client;

  public MpvCommandDispatcher(MpvClient client) {
    this.client = client;
  }

  /** Dispatches a raw mpv command. */
  public boolean command(String... args) {
    return client.command(args);
  }

  /** Installs startup scripts into the mpv config's lua/.startup directory. */
  public boolean setStartupScripts(java.util.List<java.io.File> scripts) {
    for (java.io.File script : scripts != null ? scripts : java.util.Collections.<java.io.File>emptyList()) {
      if (script == null || !script.exists()) {
        continue;
      }
      java.io.File startupDir = getStartupScriptsDirectory();
      if (startupDir == null) {
        return false;
      }
      if (!copyScript(script, new java.io.File(startupDir, script.getName()))) {
        return false;
      }
    }
    return true;
  }

  /** Loads a runtime script into the mpv config's lua directory. */
  public boolean loadScript(String path) {
    java.io.File source = new java.io.File(path);
    if (!source.exists()) {
      return false;
    }
    java.io.File scriptsDir = getScriptsDirectory();
    if (scriptsDir == null) {
      return false;
    }
    java.io.File target = new java.io.File(scriptsDir, source.getName());
    if (!copyScript(source, target)) {
      return false;
    }
    return command("script-message", "tv/load-script", target.getAbsolutePath());
  }

  /** Requests unloading of the script with the given client identifier. */
  public void unloadScript(long clientId) {
    command("script-message", "tv/unload-script", Long.toString(clientId));
  }

  /** Returns the value of a user-data property set by a script. */
  @Nullable
  public String getScriptData(String key) {
    return client.getPropertyString("user-data/" + key);
  }

  /** Returns the current input bindings configuration. */
  @Nullable
  public String getInputBindings() {
    return client.getPropertyString("input-bindings");
  }

  /** Invokes an mpv script binding by name. */
  public boolean runScriptBinding(String binding) {
    return command(CMD_SCRIPT_BINDING, binding);
  }

  /** Applies the video decoder mode through the hwdec option. */
  public void setVideoDecoderMode(androidx.media3.mpvplayer.MpvDecoderMode mode) {
    switch (mode) {
      case HARDWARE:
        client.setPropertyString("hwdec", "auto-safe");
        break;
      case FFMPEG:
        client.setPropertyString("hwdec", "no");
        break;
      case AUTO:
      default:
        client.setPropertyString("hwdec", "auto");
        break;
    }
  }

  @Nullable
  private java.io.File getScriptsDirectory() {
    return getMpvSubdirectory("lua");
  }

  @Nullable
  private java.io.File getStartupScriptsDirectory() {
    java.io.File scriptsDirectory = getScriptsDirectory();
    return scriptsDirectory == null ? null : new java.io.File(scriptsDirectory, ".startup");
  }

  @Nullable
  private java.io.File getMpvSubdirectory(String name) {
    String configDir = client.getPropertyString("config-directory");
    if (configDir == null || configDir.isEmpty()) {
      return null;
    }
    java.io.File directory = new java.io.File(configDir, name);
    if (!directory.exists() && !directory.mkdirs()) {
      return null;
    }
    return directory;
  }

  private static boolean copyScript(java.io.File source, java.io.File target) {
    try (java.io.InputStream in = new java.io.FileInputStream(source);
        java.io.OutputStream out = new java.io.FileOutputStream(target)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** Reads a boolean mpv property, or null when unavailable. */
  @Nullable
  public Boolean getPropertyBoolean(String property) {
    return client.getPropertyBoolean(property);
  }

  private static String getSubtitleFlags(@C.SelectionFlags int selectionFlags, boolean select) {
    StringBuilder flags = new StringBuilder(select ? VALUE_CACHED : VALUE_AUTO);
    if ((selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
      flags.append("+default");
    }
    if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
      flags.append("+forced");
    }
    return flags.toString();
  }

  public boolean loadFile(Uri uri, List<String> perFileOptions) {
    String loadUri = uri.toString();
    if (!perFileOptions.isEmpty()) {
      return client.command(
          new String[] {
            CMD_LOAD_FILE,
            loadUri,
            VALUE_REPLACE,
            LOAD_FILE_DEFAULT_INDEX,
            encodePerFileOptions(perFileOptions)
          });
    }
    return client.command(new String[] {CMD_LOAD_FILE, loadUri, VALUE_REPLACE});
  }

  static String encodePerFileOptions(List<String> options) {
    List<String> encodedOptions = new ArrayList<>(options.size());
    for (String option : options) {
      int separator = option.indexOf('=');
      if (separator <= 0) {
        throw new IllegalArgumentException("Invalid per-file option: " + option);
      }
      encodedOptions.add(
          encodeSubparameter(option.substring(0, separator))
              + "="
              + encodeSubparameter(option.substring(separator + 1)));
    }
    return TextUtils.join(",", encodedOptions);
  }

  private static String encodeSubparameter(String value) {
    return "%" + Util.getUtf8Bytes(value).length + "%" + value;
  }

  public boolean seekTo(long positionMs, MpvClient.ResultCallback callback) {
    return client.setPropertyDouble(PROP_TIME_POS, positionMs / 1000.0, callback);
  }

  public boolean addAudioFilter(String audioFilter) {
    return !TextUtils.isEmpty(audioFilter)
        && client.command(new String[] {CMD_AUDIO_FILTER, FILTER_OPERATION_ADD, audioFilter});
  }

  public boolean removeAudioFilter(String label) {
    return !TextUtils.isEmpty(label)
        && client.command(
            new String[] {CMD_AUDIO_FILTER, FILTER_OPERATION_REMOVE, "@" + label});
  }

  public boolean sendAudioFilterCommands(List<MpvAudioFilterCommand> commands) {
    for (MpvAudioFilterCommand command : commands) {
      if (!sendAudioFilterCommand(command)) {
        return false;
      }
    }
    return true;
  }

  private boolean sendAudioFilterCommand(MpvAudioFilterCommand command) {
    return client.command(
        new String[] {
          CMD_AUDIO_FILTER_COMMAND,
          command.getLabel(),
          command.getCommand(),
          command.getArgument(),
          command.getTarget()
        });
  }

  public boolean stop() {
    return client.command(new String[] {CMD_STOP});
  }

  public boolean toggleGeneralStats() {
    return client.command(new String[] {CMD_SCRIPT_BINDING, STATS_GENERAL_PAGE_TOGGLE});
  }

  public boolean addSubtitle(MediaItem.SubtitleConfiguration subtitle, boolean select) {
    return client.command(getAddSubtitleCommand(subtitle, select));
  }

  static String[] getAddSubtitleCommand(
      MediaItem.SubtitleConfiguration subtitle, boolean select) {
    String label = TextUtils.isEmpty(subtitle.label) ? "" : subtitle.label;
    String language = TextUtils.isEmpty(subtitle.language) ? "" : subtitle.language;
    return new String[] {
      COMMAND_PREFIX_ASYNC,
      CMD_SUB_ADD,
      subtitle.uri.toString(),
      getSubtitleFlags(subtitle.selectionFlags, select),
      label,
      language
    };
  }
}
