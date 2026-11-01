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
package androidx.media3.common.util;

import android.net.Uri;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parser for local ClearKey license data in JSON Web Key format.
 *
 * <p>The parsed keys are returned as an unmodifiable map of hexadecimal key ids to hexadecimal
 * 128-bit keys, both in lowercase hexadecimal encoding.
 */
@UnstableApi
public final class LocalClearKeyLicense {

  private LocalClearKeyLicense() {}

  /**
   * Parses a ClearKey license in JSON Web Key format.
   *
   * @param key The license data, e.g. the string {@code "{\"keys\":[{\"kty\":\"oct\",
   *     \"kid\":\"...\",\"k\":\"...\"}]}"}.
   * @return An unmodifiable map of hexadecimal key ids to hexadecimal keys, or an empty map when
   *     the license data does not contain a valid set of keys.
   */
  public static Map<String, String> parse(String key) {
    String trimmed = key.trim();
    if (!trimmed.startsWith("{")) {
      return Collections.emptyMap();
    }
    try {
      JSONArray keys = new JSONObject(trimmed).getJSONArray("keys");
      LinkedHashMap<String, String> result = new LinkedHashMap<>();
      for (int i = 0; i < keys.length(); i++) {
        JSONObject entry = keys.getJSONObject(i);
        if (!"oct".equals(entry.getString("kty"))) {
          return Collections.emptyMap();
        }
        byte[] keyId = Base64.decode(entry.getString("kid"), Base64.URL_SAFE);
        byte[] keyValue = Base64.decode(entry.getString("k"), Base64.URL_SAFE);
        if (keyId.length != 16 || keyValue.length != 16) {
          return Collections.emptyMap();
        }
        String keyHex = Util.toHexString(keyValue);
        String previous = result.put(Util.toHexString(keyId), keyHex);
        if (previous != null && !previous.equals(keyHex)) {
          return Collections.emptyMap();
        }
      }
      return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    } catch (IllegalArgumentException | JSONException e) {
      return Collections.emptyMap();
    }
  }

  /**
   * Parses the local ClearKey license of a DRM configuration.
   *
   * @param configuration The DRM configuration to read the license from, or {@code null}.
   * @return An unmodifiable map of hexadecimal key ids to hexadecimal keys, or an empty map when
   *     the configuration does not describe a local ClearKey license.
   */
  public static Map<String, String> parse(@Nullable MediaItem.DrmConfiguration configuration) {
    if (configuration == null) {
      return Collections.emptyMap();
    }
    Uri licenseUri = configuration.licenseUri;
    if (!C.CLEARKEY_UUID.equals(configuration.scheme)
        || licenseUri == null
        || configuration.getKeySetId() != null) {
      return Collections.emptyMap();
    }
    return parse(licenseUri.toString());
  }
}
