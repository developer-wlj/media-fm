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

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Renders libass frames on a dedicated GL thread that owns an EGL context bound to the surface
 * texture of a {@link LibassRenderView}.
 */
@UnstableApi
final class LibassRenderThread extends HandlerThread implements Handler.Callback {

  private static final String TAG = "LibassRenderThread";

  private static final int MSG_SETUP_SURFACE = 1;
  private static final int MSG_RELEASE_SURFACE = 2;
  private static final int MSG_RENDER_FRAME = 3;
  private static final int MSG_RELEASE = 4;

  private final Object lock;
  private final SurfaceTexture surfaceTexture;
  public Handler handler;
  @Nullable public LibassMediaSession mediaSession;
  public long positionUs;
  public int width;
  public int height;

  private EGLDisplay eglDisplay;
  private EGLContext eglContext;
  private EGLSurface eglSurface;
  @Nullable private LibassGlRenderer glRenderer;
  private int surfaceWidth;
  private int surfaceHeight;
  private boolean setupFailed;
  private boolean released;

  /** Creates a render thread drawing into the given surface texture. */
  public LibassRenderThread(SurfaceTexture surfaceTexture) {
    super("ExoPlayer:LibassRenderThread");
    this.lock = new Object();
    this.surfaceTexture = surfaceTexture;
    this.eglDisplay = EGL14.EGL_NO_DISPLAY;
    this.eglContext = EGL14.EGL_NO_CONTEXT;
    this.eglSurface = EGL14.EGL_NO_SURFACE;
  }

  public void startThread() {
    start();
    handler = new Handler(getLooper(), this);
  }

  /** Sets the media session whose frames are rendered. */
  public void setMediaSession(@Nullable LibassMediaSession mediaSession) {
    synchronized (lock) {
      this.mediaSession = mediaSession;
    }
  }

  /** Requests rendering the frame at the given position. */
  public void renderFrame(long positionUs) {
    Handler handler = this.handler;
    if (handler == null) {
      return;
    }
    handler.removeMessages(MSG_RENDER_FRAME);
    handler.obtainMessage(MSG_RENDER_FRAME, Long.valueOf(positionUs)).sendToTarget();
  }

  /** Sets the pixel size of the surface. */
  public void setSurfaceSize(int width, int height) {
    Handler handler = this.handler;
    if (handler == null) {
      return;
    }
    handler.sendMessage(handler.obtainMessage(MSG_SETUP_SURFACE, width, height));
  }

  /** Releases the thread and its GL resources. */
  public void release() {
    Handler handler = this.handler;
    if (handler == null) {
      return;
    }
    handler.sendEmptyMessage(MSG_RELEASE);
  }

  @Override
  public boolean handleMessage(Message message) {
    switch (message.what) {
      case MSG_SETUP_SURFACE:
        setupSurface(message.arg1, message.arg2);
        return true;
      case MSG_RELEASE_SURFACE:
        releaseSurface();
        return true;
      case MSG_RENDER_FRAME:
        synchronized (lock) {
          positionUs = (Long) message.obj;
          renderFrameInternal();
        }
        return true;
      case MSG_RELEASE:
        releaseInternal();
        quit();
        return true;
      default:
        return false;
    }
  }

  private void setupSurface(int width, int height) {
    if (released) {
      return;
    }
    this.surfaceWidth = width;
    this.surfaceHeight = height;
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
        setupFailed = true;
        return;
      }
      int[] versions = new int[2];
      if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
        setupFailed = true;
        return;
      }
      int[] attribList =
          new int[] {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
          };
      EGLConfig[] configs = new EGLConfig[1];
      int[] numConfigs = new int[1];
      if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)
          || numConfigs[0] <= 0) {
        setupFailed = true;
        return;
      }
      int[] contextAttribList = new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
      eglContext =
          EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
      if (eglContext == EGL14.EGL_NO_CONTEXT) {
        setupFailed = true;
        return;
      }
      surfaceTexture.setDefaultBufferSize(width, height);
      eglSurface =
          EGL14.eglCreateWindowSurface(
              eglDisplay, configs[0], new Surface(surfaceTexture), new int[] {EGL14.EGL_NONE}, 0);
      if (eglSurface == EGL14.EGL_NO_SURFACE) {
        setupFailed = true;
        return;
      }
      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        setupFailed = true;
        return;
      }
      glRenderer = new LibassGlRenderer();
      if (!glRenderer.setup()) {
        setupFailed = true;
        return;
      }
    }
    GLES20.glViewport(0, 0, width, height);
    renderFrameInternal();
  }

  private void renderFrameInternal() {
    LibassGlRenderer glRenderer = this.glRenderer;
    LibassMediaSession mediaSession = this.mediaSession;
    if (setupFailed || glRenderer == null || eglSurface == EGL14.EGL_NO_SURFACE) {
      return;
    }
    if (mediaSession == null) {
      drawAndSwap(glRenderer);
      return;
    }
    LibassMediaSession.OutputFrame outputFrame = mediaSession.renderFrames(positionUs, true);
    if (outputFrame != null) {
      glRenderer.updateFrames(outputFrame, surfaceWidth, surfaceHeight);
    }
    drawAndSwap(glRenderer);
    if (outputFrame != null) {
      glRenderer.releaseFrameTextures(outputFrame);
    }
  }

  private void drawAndSwap(LibassGlRenderer glRenderer) {
    if (surfaceWidth == 0 || surfaceHeight == 0) {
      return;
    }
    glRenderer.drawFrames(surfaceWidth, surfaceHeight);
    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
      Log.w(TAG, "eglSwapBuffers failed");
    }
  }

  private void releaseSurface() {
    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      if (glRenderer != null) {
        glRenderer.release();
        glRenderer = null;
      }
      EGL14.eglMakeCurrent(
          eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (eglSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        eglSurface = EGL14.EGL_NO_SURFACE;
      }
      if (eglContext != EGL14.EGL_NO_CONTEXT) {
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        eglContext = EGL14.EGL_NO_CONTEXT;
      }
      EGL14.eglTerminate(eglDisplay);
      eglDisplay = EGL14.EGL_NO_DISPLAY;
    }
  }

  private void releaseInternal() {
    if (released) {
      return;
    }
    released = true;
    releaseSurface();
  }
}
