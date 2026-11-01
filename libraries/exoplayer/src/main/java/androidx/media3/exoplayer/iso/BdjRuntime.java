/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.iso;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Prepares the BD-J runtime used for Blu-ray disc menu playback.
 *
 * <p>The runtime is provided by the BD-J module package and contains a J2SE image together with the
 * libbluray jars. Preparation extracts the image, installs the libbluray classpath and exports the
 * environment expected by the disc navigation engine.
 */
public final class BdjRuntime {

  private static final String TAG = "BdjRuntime";

  private static final String RUNTIME_PACKAGE = "com.fongmi.runtime.bdj";
  private static final String RUNTIME_DIRECTORY = "bdj";
  private static final String METADATA_ASSET = "assets/runtime.json";
  private static final String ARCHIVE_ASSET = "assets/bdj/runtime.zip";
  private static final String JARS_PROPERTIES_ASSET = "bdj/jars.properties";
  private static final String J2SE_JAR = "libbluray-j2se-1.4.1.jar";
  private static final String AWT_JAR = "libbluray-awt-j2se-1.4.1.jar";
  private static final String COMPATIBILITY_ID = "libbluray-1.4.1-openjdk8-zero-v1";
  private static final String STALE_DIRECTORY_PATTERN =
      "runtime-(arm64-v8a|armeabi-v7a)-[1-9][0-9]*-[0-9a-f]{64}"
          + "|1\\.4\\.1-(arm64-v8a|armeabi-v7a)-[0-9a-f]{16}";

  private static final long MAX_METADATA_BYTES = 65536;
  private static final long MAX_ARCHIVE_BYTES = 134217728;
  private static final int COPY_BUFFER_BYTES = 32768;
  private static final int FILE_PERMISSION_OWNER_READ = 256;

  private static final int ELF_CLASS_32 = 1;
  private static final int ELF_CLASS_64 = 2;
  private static final int ELF_MACHINE_ARM = 40;
  private static final int ELF_MACHINE_AARCH64 = 183;

  /** Thrown when the BD-J module package is not installed. */
  public static final class MissingException extends IOException {

    public MissingException(String message) {
      super(message);
    }
  }

  /** The installation status of the BD-J runtime. */
  public static final class Status {

    /** Whether a BD-J runtime is installed. */
    public final boolean installed;

    /** Whether the prepared runtime changed and the app has to be restarted before use. */
    public final boolean restartRequired;

    public Status(boolean installed, boolean restartRequired) {
      this.installed = installed;
      this.restartRequired = restartRequired;
    }
  }

  private static final class RuntimeInfo {

    public final PackageInfo packageInfo;
    public final String abi;
    public final String sha256;
    public final long archiveSize;

    public RuntimeInfo(PackageInfo packageInfo, String abi, String sha256, long archiveSize) {
      this.packageInfo = packageInfo;
      this.abi = abi;
      this.sha256 = sha256;
      this.archiveSize = archiveSize;
    }
  }

  private static final class JarAsset {

    public final String name;
    public final String sha256;
    public final long size;

    public JarAsset(String name, Properties properties) throws IOException {
      this.name = name;
      String sha256 = properties.getProperty(name + ".sha256", "");
      long size;
      try {
        size = Long.parseLong(properties.getProperty(name + ".size", ""));
      } catch (NumberFormatException e) {
        throw new IOException("Invalid packaged BD-J jar size", e);
      }
      if (!sha256.matches("[0-9a-f]{64}") || size <= 0) {
        throw new IOException("Invalid packaged BD-J jar checksum");
      }
      this.sha256 = sha256;
      this.size = size;
    }
  }

  private BdjRuntime() {}

  /**
   * Returns the installation status of the BD-J runtime.
   *
   * @param context Any context.
   * @return The current status.
   */
  public static Status getStatus(Context context) {
    String abi = getAbi();
    if (abi == null) {
      return new Status(/* installed= */ false, /* restartRequired= */ false);
    }
    try {
      Context appContext = context.getApplicationContext();
      RuntimeInfo info = getRuntimeInfo(appContext, abi);
      String javaHome = Os.getenv("JAVA_HOME");
      boolean restartRequired =
          javaHome != null
              && !javaHome.equals(getRuntimeImageDirectory(appContext, info).getAbsolutePath());
      return new Status(/* installed= */ true, restartRequired);
    } catch (IOException e) {
      Log.w(TAG, "Could not resolve BD-J runtime status", e);
      return new Status(/* installed= */ !(e instanceof MissingException), false);
    }
  }

  /**
   * Prepares the BD-J runtime for use by the disc navigation engine.
   *
   * @param context Any context.
   * @param signal The signal to cancel the preparation.
   * @throws IOException If the runtime is not installed ({@link MissingException}), incompatible
   *     with this app, or cannot be extracted.
   * @throws InterruptedIOException If the preparation is cancelled.
   */
  public static void prepare(Context context, CancellationSignal signal) throws IOException {
    checkNotCancelled(signal);
    String abi = getAbi();
    if (abi == null) {
      throw new IOException("BD-J has no runtime for this ABI");
    }
    Context appContext = context.getApplicationContext();
    RuntimeInfo info = getRuntimeInfo(appContext, abi);
    File imageDirectory = getRuntimeImageDirectory(appContext, info);
    String javaHome = Os.getenv("JAVA_HOME");
    if (javaHome != null && !javaHome.equals(imageDirectory.getAbsolutePath())) {
      throw new IOException("BD-J runtime changed; restart the app before opening a disc");
    }
    File runtimeDirectory = imageDirectory.getParentFile();
    File baseDirectory = runtimeDirectory.getParentFile();
    installRuntime(appContext, info, imageDirectory, signal);
    File classpathDirectory = installClasspath(appContext, baseDirectory, signal);
    checkNotCancelled(signal);
    if (javaHome == null) {
      removeStaleRuntimes(baseDirectory, runtimeDirectory, signal);
    }
    checkNotCancelled(signal);
    setEnvironment(appContext, imageDirectory, classpathDirectory);
  }

  @Nullable
  private static String getAbi() {
    String preferred = Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
    for (String abi : Build.SUPPORTED_ABIS) {
      if (abi.equals(preferred)) {
        return abi;
      }
    }
    return null;
  }

  private static RuntimeInfo getRuntimeInfo(Context context, String abi) throws IOException {
    PackageManager packageManager = context.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo packageInfo;
    try {
      packageInfo = packageManager.getPackageInfo(RUNTIME_PACKAGE, flags);
    } catch (PackageManager.NameNotFoundException e) {
      throw new MissingException("BD-J runtime is not installed");
    }
    if (packageInfo.applicationInfo == null
        || packageInfo.applicationInfo.sourceDir == null
        || packageInfo.applicationInfo.sourceDir.isEmpty()
        || !RUNTIME_PACKAGE.equals(packageInfo.packageName)) {
      throw new IOException("Invalid BD-J runtime package");
    }
    long versionCode =
        Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
    if (versionCode <= 0
        || packageInfo.versionName == null
        || packageInfo.versionName.isEmpty()
        || (packageInfo.splitNames != null && packageInfo.splitNames.length > 0)) {
      throw new IOException("Invalid BD-J runtime package");
    }
    verifyRuntimeSigner(context, packageManager, packageInfo, flags);
    return readRuntimeMetadata(packageInfo, versionCode, abi);
  }

  private static void verifyRuntimeSigner(
      Context context, PackageManager packageManager, PackageInfo packageInfo, int flags)
      throws IOException {
    try {
      PackageInfo appPackageInfo = packageManager.getPackageInfo(context.getPackageName(), flags);
      Signature[] appSignatures =
          Build.VERSION.SDK_INT >= 28
              ? getSigningSignatures(appPackageInfo.signingInfo)
              : appPackageInfo.signatures;
      Signature[] runtimeSignatures =
          Build.VERSION.SDK_INT >= 28
              ? getSigningSignatures(packageInfo.signingInfo)
              : packageInfo.signatures;
      if (appSignatures == null
          || runtimeSignatures == null
          || runtimeSignatures.length == 0
          || runtimeSignatures.length != appSignatures.length
          || !Arrays.asList(runtimeSignatures).containsAll(Arrays.asList(appSignatures))) {
        throw new IOException("Invalid BD-J runtime signer");
      }
    } catch (PackageManager.NameNotFoundException e) {
      throw new IOException("Could not verify BD-J runtime signer", e);
    }
  }

  @Nullable
  private static Signature[] getSigningSignatures(@Nullable SigningInfo signingInfo) {
    return signingInfo == null ? null : signingInfo.getApkContentsSigners();
  }

  private static RuntimeInfo readRuntimeMetadata(
      PackageInfo packageInfo, long versionCode, String abi) throws IOException {
    ZipFile zipFile;
    try {
      zipFile = new ZipFile(packageInfo.applicationInfo.sourceDir);
    } catch (IOException e) {
      throw new IOException("Invalid BD-J runtime package", e);
    }
    try {
      ZipEntry entry = zipFile.getEntry(METADATA_ASSET);
      if (entry == null || entry.getSize() <= 0 || entry.getSize() > MAX_METADATA_BYTES) {
        throw new IOException("Invalid BD-J runtime metadata");
      }
      byte[] data = new byte[(int) entry.getSize()];
      DataInputStream input = new DataInputStream(zipFile.getInputStream(entry));
      try {
        input.readFully(data);
      } finally {
        input.close();
      }
      return parseRuntimeMetadata(new String(data, StandardCharsets.UTF_8), packageInfo,
          versionCode, abi);
    } finally {
      zipFile.close();
    }
  }

  private static RuntimeInfo parseRuntimeMetadata(
      String metadata, PackageInfo packageInfo, long versionCode, String abi) throws IOException {
    String sha256;
    long archiveSize;
    try {
      JSONObject json = new JSONObject(metadata);
      sha256 = json.getString("sha256");
      archiveSize = json.getLong("size");
      if (!"bdj".equals(json.getString("module"))
          || !RUNTIME_PACKAGE.equals(json.getString("packageName"))
          || versionCode != json.getLong("versionCode")
          || !packageInfo.versionName.equals(json.getString("versionName"))
          || !COMPATIBILITY_ID.equals(json.getString("compatibilityId"))
          || !abi.equals(json.getString("abi"))
          || !ARCHIVE_ASSET.equals(json.getString("archive"))
          || json.has("library")
          || !sha256.matches("[0-9a-f]{64}")
          || archiveSize <= 0
          || archiveSize > MAX_ARCHIVE_BYTES
          || json.getJSONArray("files").length() != 1) {
        throw new IOException("Incompatible BD-J runtime metadata");
      }
      JSONObject file = json.getJSONArray("files").getJSONObject(0);
      if (!ARCHIVE_ASSET.equals(file.getString("path"))
          || archiveSize != file.getLong("size")
          || !sha256.equals(file.getString("sha256"))) {
        throw new IOException("Invalid BD-J runtime archive metadata");
      }
      ZipFile zipFile = new ZipFile(packageInfo.applicationInfo.sourceDir);
      try {
        ZipEntry entry = zipFile.getEntry(ARCHIVE_ASSET);
        if (entry == null || entry.getSize() != archiveSize) {
          throw new IOException("Invalid BD-J runtime archive metadata");
        }
      } finally {
        zipFile.close();
      }
    } catch (JSONException e) {
      throw new IOException("Invalid BD-J runtime metadata", e);
    }
    return new RuntimeInfo(packageInfo, abi, sha256, archiveSize);
  }

  private static File getRuntimeImageDirectory(Context context, RuntimeInfo info) {
    long versionCode =
        Build.VERSION.SDK_INT >= 28
            ? info.packageInfo.getLongVersionCode()
            : info.packageInfo.versionCode;
    StringBuilder name = new StringBuilder("runtime-").append(info.abi).append("-");
    name.append(versionCode).append("-").append(info.sha256);
    return new File(new File(context.getFilesDir(), RUNTIME_DIRECTORY), name + "/j2re-image");
  }

  private static void installRuntime(
      Context context, RuntimeInfo info, File imageDirectory, CancellationSignal signal)
      throws IOException {
    checkNotCancelled(signal);
    File runtimeDirectory = imageDirectory.getParentFile();
    if (runtimeDirectory.exists()) {
      verifyRuntimeAbi(imageDirectory, info.abi);
      return;
    }
    File baseDirectory = runtimeDirectory.getParentFile();
    if (!baseDirectory.isDirectory() && !baseDirectory.mkdirs()) {
      throw new IOException("Could not create BD-J runtime directory");
    }
    File archiveFile = File.createTempFile("archive-", ".zip", baseDirectory);
    File stagingDirectory = new File(baseDirectory, "staging-" + UUID.randomUUID());
    ZipFile zipFile;
    try {
      zipFile = new ZipFile(info.packageInfo.applicationInfo.sourceDir);
    } catch (IOException e) {
      archiveFile.delete();
      deleteRecursivelyQuiet(stagingDirectory);
      throw new IOException("Invalid BD-J runtime package", e);
    }
    try {
      ZipEntry entry = zipFile.getEntry(ARCHIVE_ASSET);
      if (entry == null || entry.getSize() != info.archiveSize) {
        throw new IOException("Invalid BD-J runtime archive size");
      }
      InputStream input = zipFile.getInputStream(entry);
      try {
        copy(input, info.archiveSize, archiveFile, signal);
      } finally {
        input.close();
      }
      extractArchive(archiveFile, stagingDirectory, signal);
      verifyRuntimeAbi(new File(stagingDirectory, "j2re-image"), info.abi);
      checkNotCancelled(signal);
      if (!stagingDirectory.renameTo(runtimeDirectory)) {
        throw new IOException("Could not install BD-J runtime");
      }
      archiveFile.delete();
      deleteRecursivelyQuiet(stagingDirectory);
    } catch (IOException e) {
      archiveFile.delete();
      deleteRecursivelyQuiet(stagingDirectory);
      throw e;
    } finally {
      zipFile.close();
    }
  }

  private static void verifyRuntimeAbi(File imageDirectory, String abi) throws IOException {
    File jvmLibrary =
        new File(
            imageDirectory,
            "lib/" + (abi.equals("arm64-v8a") ? "aarch64" : "arm") + "/server/libjvm.so");
    if (!new File(imageDirectory, "lib/rt.jar").isFile() || !jvmLibrary.isFile()) {
      throw new IOException("BD-J runtime archive is incomplete");
    }
    byte[] header = new byte[20];
    DataInputStream input = new DataInputStream(new FileInputStream(jvmLibrary));
    try {
      input.readFully(header);
    } finally {
      input.close();
    }
    int expectedClass = abi.equals("arm64-v8a") ? ELF_CLASS_64 : ELF_CLASS_32;
    int expectedMachine = abi.equals("arm64-v8a") ? ELF_MACHINE_AARCH64 : ELF_MACHINE_ARM;
    if (header[0] == 127
        && header[1] == 'E'
        && header[2] == 'L'
        && header[3] == 'F'
        && header[4] == expectedClass
        && header[5] == 1
        && (header[18] & 0xFF) == expectedMachine
        && header[19] == 0) {
      return;
    }
    throw new IOException("Invalid BD-J runtime ELF ABI");
  }

  private static File installClasspath(Context context, File baseDirectory,
      CancellationSignal signal) throws IOException {
    checkNotCancelled(signal);
    Properties properties = new Properties();
    InputStream input = context.getAssets().open(JARS_PROPERTIES_ASSET);
    try {
      properties.load(input);
    } finally {
      input.close();
    }
    JarAsset j2seJar = new JarAsset(J2SE_JAR, properties);
    JarAsset awtJar = new JarAsset(AWT_JAR, properties);
    String descriptor =
        J2SE_JAR + "\n" + j2seJar.sha256 + "\n" + AWT_JAR + "\n" + awtJar.sha256;
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable", e);
    }
    StringBuilder hash = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hash.append(String.format(Locale.US, "%02x", b & 0xFF));
    }
    File classpathDirectory = new File(baseDirectory, "classpath-" + hash);
    if (classpathDirectory.exists()) {
      return classpathDirectory;
    }
    File stagingDirectory = new File(baseDirectory, "classpath-" + UUID.randomUUID());
    if (!stagingDirectory.mkdirs()) {
      throw new IOException("Could not create BD-J classpath directory");
    }
    try {
      extractJar(context, j2seJar, stagingDirectory, signal);
      extractJar(context, awtJar, stagingDirectory, signal);
      checkNotCancelled(signal);
      if (!stagingDirectory.renameTo(classpathDirectory)) {
        throw new IOException("Could not install BD-J classpath");
      }
      return classpathDirectory;
    } catch (IOException e) {
      deleteRecursivelyQuiet(stagingDirectory);
      throw e;
    }
  }

  private static void extractJar(
      Context context, JarAsset jar, File targetDirectory, CancellationSignal signal)
      throws IOException {
    checkNotCancelled(signal);
    InputStream input = context.getAssets().open(RUNTIME_DIRECTORY + "/" + jar.name);
    try {
      copy(input, jar.size, new File(targetDirectory, jar.name), signal);
    } finally {
      input.close();
    }
  }

  private static void copy(
      InputStream input, long expectedLength, File target, CancellationSignal signal)
      throws IOException {
    FileOutputStream output = new FileOutputStream(target);
    try {
      byte[] buffer = new byte[COPY_BUFFER_BYTES];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        checkNotCancelled(signal);
        total += read;
        if (total > expectedLength) {
          throw new IOException("BD-J runtime archive is larger than expected");
        }
        output.write(buffer, 0, read);
      }
      if (total != expectedLength) {
        throw new IOException("BD-J runtime archive is incomplete");
      }
    } finally {
      output.close();
    }
  }

  private static void extractArchive(File archive, File targetDirectory,
      CancellationSignal signal) throws IOException {
    checkNotCancelled(signal);
    String canonicalTarget = targetDirectory.getCanonicalPath() + File.separator;
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    ZipInputStream input =
        new ZipInputStream(new BufferedInputStream(new FileInputStream(archive),
            COPY_BUFFER_BYTES));
    try {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        checkNotCancelled(signal);
        File file = new File(targetDirectory, entry.getName());
        if (!file.getCanonicalPath().startsWith(canonicalTarget)) {
          throw new IOException("Unsafe BD-J runtime archive path");
        }
        if (entry.isDirectory()) {
          if (!file.isDirectory() && !file.mkdirs()) {
            throw new IOException("Could not create BD-J runtime directory");
          }
        } else {
          File parent = file.getParentFile();
          if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create BD-J runtime directory");
          }
          BufferedOutputStream output =
              new BufferedOutputStream(new FileOutputStream(file), COPY_BUFFER_BYTES);
          try {
            if (entry.getName().endsWith(".so")) {
              try {
                Os.chmod(file.getPath(), FILE_PERMISSION_OWNER_READ);
              } catch (ErrnoException e) {
                throw new IOException("Could not set BD-J runtime file permissions", e);
              }
            }
            int read;
            while ((read = input.read(buffer)) != -1) {
              checkNotCancelled(signal);
              output.write(buffer, 0, read);
            }
          } finally {
            output.close();
          }
          if ((entry.getName().endsWith(".so") || entry.getName().startsWith("j2re-image/bin/"))
              && !file.setExecutable(true, true)) {
            throw new IOException("Could not set BD-J runtime file permissions");
          }
        }
        input.closeEntry();
      }
    } finally {
      input.close();
    }
  }

  private static void removeStaleRuntimes(
      File baseDirectory, File currentRuntimeDirectory, CancellationSignal signal)
      throws IOException {
    File[] children = baseDirectory.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      if (child.equals(currentRuntimeDirectory)
          || !child.getName().matches(STALE_DIRECTORY_PATTERN)) {
        continue;
      }
      deleteRecursively(child, signal);
    }
  }

  private static void deleteRecursively(File file, CancellationSignal signal) throws IOException {
    checkNotCancelled(signal);
    if (isSafeDirectory(file)) {
      File[] children = file.listFiles();
      if (children == null) {
        throw new IOException("Could not read BD-J runtime directory");
      }
      for (File child : children) {
        deleteRecursively(child, signal);
      }
    }
    checkNotCancelled(signal);
    if (!file.delete()) {
      throw new IOException("Could not remove BD-J runtime file: " + file);
    }
  }

  private static void deleteRecursivelyQuiet(File file) {
    try {
      if (isSafeDirectory(file)) {
        File[] children = file.listFiles();
        if (children != null) {
          for (File child : children) {
            deleteRecursivelyQuiet(child);
          }
        }
      }
    } catch (IOException e) {
      // Ignore, best effort cleanup.
    }
    file.delete();
  }

  private static boolean isSafeDirectory(File file) throws IOException {
    if (!file.isDirectory()) {
      return false;
    }
    try {
      return !OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
    } catch (ErrnoException e) {
      throw new IOException("Could not inspect BD-J runtime file: " + file, e);
    }
  }

  private static void setEnvironment(
      Context context, File imageDirectory, File classpathDirectory) throws IOException {
    try {
      Os.setenv("JAVA_HOME", imageDirectory.getAbsolutePath(), true);
      Os.setenv("JDK_HOME", imageDirectory.getAbsolutePath(), true);
      Os.setenv("LIBBLURAY_CP", classpathDirectory.getAbsolutePath() + File.separator, true);
      Os.setenv("BDJ_JVM_DISABLE_JIT", "1", true);
      Os.setenv("_JAVA_OPTIONS", "-Djava.io.tmpdir=" + context.getCacheDir(), true);
    } catch (ErrnoException e) {
      throw new IOException("Could not configure BD-J runtime", e);
    }
  }

  private static void checkNotCancelled(CancellationSignal signal) throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted() || signal.isCanceled()) {
      throw new InterruptedIOException("BD-J runtime preparation cancelled");
    }
  }
}
