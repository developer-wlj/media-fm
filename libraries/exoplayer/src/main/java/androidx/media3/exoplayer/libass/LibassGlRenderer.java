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

import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * Renders {@link LibassFrame LibassFrames} with OpenGL ES 2.0. Each frame is uploaded as a list of
 * textured quads and blended over the video.
 */
@UnstableApi
final class LibassGlRenderer {

  private static final String TAG = "LibassGlRenderer";

  private static final String VERTEX_SHADER =
      "attribute vec2 aPosition;\n"
          + "uniform vec2 uScale;\n"
          + "varying vec2 vTexCoord;\n"
          + "void main() {\n"
          + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
          + "  vTexCoord = aPosition * 0.5 + 0.5;\n"
          + "}\n";

  private static final String FRAGMENT_SHADER =
      "precision mediump float;\n"
          + "uniform sampler2D uTexture;\n"
          + "uniform vec4 uColor;\n"
          + "varying vec2 vTexCoord;\n"
          + "void main() {\n"
          + "  float coverage = texture2D(uTexture, vTexCoord).r;\n"
          + "  gl_FragColor = vec4(uColor.rgb, uColor.a * coverage);\n"
          + "}\n";

  private static final float[] QUAD_VERTICES = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};

  private final FloatBuffer quadBuffer;
  private final Layer primaryLayer;
  private final Layer secondaryLayer;
  private final int[] textureHandles;

  public int program;
  public int positionAttribute;
  public int scaleUniform;
  public int colorUniform;
  public int textureUniform;
  public boolean colorsCleared;

  /** Creates an uninitialized renderer. {@link #setup} must be called before drawing. */
  public LibassGlRenderer() {
    this.quadBuffer =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
    this.quadBuffer.put(QUAD_VERTICES).position(0);
    this.primaryLayer = new Layer();
    this.secondaryLayer = new Layer();
    this.textureHandles = new int[1];
  }

  /** Compiles the shader program. Returns whether the setup succeeded. */
  public boolean setup() {
    program = GLES20.glCreateProgram();
    int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
    int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (vertexShader == 0 || fragmentShader == 0 || program == 0) {
      return false;
    }
    GLES20.glAttachShader(program, vertexShader);
    GLES20.glAttachShader(program, fragmentShader);
    GLES20.glLinkProgram(program);
    int[] linkStatus = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
    if (linkStatus[0] == 0) {
      Log.w(TAG, "Failed to link libass shader: " + GLES20.glGetProgramInfoLog(program));
      release();
      return false;
    }
    positionAttribute = GLES20.glGetAttribLocation(program, "aPosition");
    scaleUniform = GLES20.glGetUniformLocation(program, "uScale");
    colorUniform = GLES20.glGetUniformLocation(program, "uColor");
    textureUniform = GLES20.glGetUniformLocation(program, "uTexture");
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    GLES20.glEnable(GLES20.GL_BLEND);
    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    return true;
  }

  private static int compileShader(int type, String source) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] compiled = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
    if (compiled[0] == 0) {
      Log.w(TAG, "Failed to compile libass shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      return 0;
    }
    return shader;
  }

  /**
   * Updates the layers with the frames rendered for the current position.
   *
   * @return Whether the frame content changed and needs to be drawn.
   */
  public boolean updateFrames(@Nullable LibassMediaSession.OutputFrame outputFrame, int width, int height) {
    boolean changed =
        updateLayer(secondaryLayer, outputFrame == null ? null : outputFrame.secondaryFrame, width, height);
    changed |=
        updateLayer(primaryLayer, outputFrame == null ? null : outputFrame.primaryFrame, width, height);
    return changed;
  }

  private boolean updateLayer(
      Layer layer, @Nullable LibassFrame frame, int width, int height) {
    ArrayList<LibassGlImage> images = layer.images;
    if (frame == null) {
      if (!colorsCleared && images.isEmpty()) {
        layer.width = 0;
        layer.height = 0;
        return false;
      }
      clearLayer(layer);
      colorsCleared = false;
      layer.width = 0;
      layer.height = 0;
      return true;
    }
    if (!frame.changed) {
      return false;
    }
    clearLayer(layer);
    layer.width = width;
    layer.height = height;
    for (int i = 0; i < frame.images.size(); i++) {
      LibassImage image = frame.images.get(i);
      int textureId = image.textureId;
      if (textureId == 0) {
        byte[] bitmap = image.bitmap;
        if (bitmap == null) {
          throw new IllegalStateException("Libass image has neither a texture nor bitmap data");
        }
        int[] handles = new int[1];
        GLES20.glGenTextures(1, handles, 0);
        textureId = handles[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer bitmapBuffer = ByteBuffer.allocateDirect(bitmap.length);
        bitmapBuffer.put(bitmap);
        bitmapBuffer.position(0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            /* level= */ 0,
            GLES20.GL_LUMINANCE,
            image.width,
            image.height,
            /* border= */ 0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            bitmapBuffer);
        image.textureId = 0;
      }
      images.add(new LibassGlImage(textureId, image));
    }
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    return true;
  }

  /** Draws the layers of the last {@link #updateFrames} call. */
  public void drawFrames(int width, int height) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    drawLayer(primaryLayer, width, height);
    drawLayer(secondaryLayer, width, height);
  }

  private void drawLayer(Layer layer, int width, int height) {
    if (layer.width == 0 || layer.height == 0) {
      return;
    }
    for (int i = 0; i < layer.images.size(); i++) {
      LibassGlImage image = layer.images.get(i);
      float imageWidth = layer.width;
      float imageHeight = layer.height;
      float left = (image.x * 2.0f / imageWidth) - 1.0f;
      float right = ((image.x + image.width) * 2.0f / imageWidth) - 1.0f;
      float top = 1.0f - (image.y * 2.0f / imageHeight);
      float bottom = 1.0f - ((image.y + image.height) * 2.0f / imageHeight);
      FloatBuffer vertices = image.vertexBuffer;
      vertices.clear();
      vertices.put(left).put(top).put(right).put(top).put(left).put(bottom).put(right).put(bottom);
      vertices.position(0);
      GLES20.glVertexAttribPointer(
          positionAttribute, /* size= */ 2, GLES20.GL_FLOAT, /* normalized= */ false, /* stride= */ 0,
          (Buffer) vertices);
      GLES20.glUniform4f(colorUniform, image.red, image.green, image.blue, image.alpha);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, image.textureId);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    }
  }

  private static void clearLayer(Layer layer) {
    ArrayList<LibassGlImage> images = layer.images;
    if (images.isEmpty()) {
      return;
    }
    int[] handles = new int[images.size()];
    for (int i = 0; i < images.size(); i++) {
      handles[i] = images.get(i).textureId;
    }
    GLES20.glDeleteTextures(handles.length, handles, 0);
    images.clear();
  }

  private static void deleteFrameTextures(@Nullable LibassFrame frame, int[] handles) {
    if (frame == null) {
      return;
    }
    for (int i = 0; i < frame.images.size(); i++) {
      LibassImage image = frame.images.get(i);
      int textureId = image.textureId;
      if (textureId != 0) {
        handles[0] = textureId;
        GLES20.glDeleteTextures(1, handles, 0);
        image.textureId = 0;
      }
    }
  }

  /** Releases the textures of the given output frame. */
  public void releaseFrameTextures(@Nullable LibassMediaSession.OutputFrame outputFrame) {
    if (outputFrame == null) {
      return;
    }
    deleteFrameTextures(outputFrame.secondaryFrame, textureHandles);
    deleteFrameTextures(outputFrame.primaryFrame, textureHandles);
  }

  /** Releases the GL resources of the renderer. */
  public void release() {
    clearLayer(primaryLayer);
    clearLayer(secondaryLayer);
    primaryLayer.width = 0;
    primaryLayer.height = 0;
    secondaryLayer.width = 0;
    secondaryLayer.height = 0;
    if (program != 0) {
      GLES20.glDeleteProgram(program);
      program = 0;
    }
  }

  private static final class Layer {

    public final ArrayList<LibassGlImage> images = new ArrayList<>();
    public int width;
    public int height;
  }
}
