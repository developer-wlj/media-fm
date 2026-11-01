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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link TextureView} that draws the frames rendered by a {@link LibassMediaSession} on a
 * dedicated GL thread.
 */
@UnstableApi
public final class LibassRenderView extends TextureView
    implements TextureView.SurfaceTextureListener {

  private final AtomicReference<LibassRenderThread> renderThreadReference;
  private final AtomicLong requestedPositionUs;
  private final AtomicBoolean redrawRequested;
  private final AtomicReference<Viewport> viewportReference;
  private final AtomicBoolean frameSizeSet;

  @Nullable private LibassMediaSession mediaSession;
  @Nullable private volatile Integer pendingWidth;
  @Nullable private volatile Integer pendingHeight;

  /** Creates a render view. */
  public LibassRenderView(Context context) {
    super(context);
    this.renderThreadReference = new AtomicReference<>();
    this.requestedPositionUs = new AtomicLong(Long.MIN_VALUE);
    this.redrawRequested = new AtomicBoolean();
    this.viewportReference = new AtomicReference<>(new Viewport(new Rect(0, 0, 0, 0), 1.0));
    this.frameSizeSet = new AtomicBoolean();
    setSurfaceTextureListener(this);
    setOpaque(false);
  }

  /** Sets the media session whose frames are rendered, or null to render nothing. */
  public void setMediaSession(@Nullable LibassMediaSession mediaSession) {
    this.mediaSession = mediaSession;
    LibassRenderThread renderThread = renderThreadReference.get();
    if (renderThread != null) {
      renderThread.setMediaSession(mediaSession);
    }
    requestRender();
  }

  /** Sets the video size used as the libass frame size. */
  public void setFrameSize(int width, int height) {
    this.pendingWidth = width;
    this.pendingHeight = height;
    LibassRenderThread renderThread = renderThreadReference.get();
    if (renderThread != null && width > 0 && height > 0) {
      renderThread.setSurfaceSize(width, height);
    }
    requestRender();
  }

  /** Sets the video viewport and the pixel aspect ratio used for rendering. */
  public void setViewport(Rect viewport, double pixelAspectRatio) {
    viewportReference.set(new Viewport(new Rect(viewport), pixelAspectRatio));
    requestRender();
  }

  /** Requests rendering the frame at the given playback position. */
  public void requestRender(long positionUs) {
    requestedPositionUs.set(positionUs);
    requestRender();
  }

  /** Requests rendering the frame at the last requested playback position. */
  public void requestRender() {
    if (!redrawRequested.compareAndSet(false, true)) {
      return;
    }
    LibassRenderThread renderThread = renderThreadReference.get();
    if (renderThread == null) {
      redrawRequested.set(false);
      return;
    }
    Handler handler = renderThread.handler;
    if (handler == null) {
      redrawRequested.set(false);
      return;
    }
    handler.post(
        () -> {
          redrawRequested.set(false);
          LibassMediaSession mediaSession = this.mediaSession;
          long positionUs = requestedPositionUs.get();
          renderThread.positionUs = positionUs;
          renderThread.setMediaSession(mediaSession);
          renderThread.renderFrame(positionUs);
        });
  }

  /** Releases the view and its render thread. */
  public void release() {
    setSurfaceTextureListener(null);
    LibassRenderThread renderThread = renderThreadReference.getAndSet(null);
    if (renderThread != null) {
      renderThread.setMediaSession(null);
      renderThread.release();
    }
    mediaSession = null;
  }

  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    LibassRenderThread renderThread = new LibassRenderThread(surface);
    renderThreadReference.set(renderThread);
    renderThread.startThread();
    Integer pendingWidth = this.pendingWidth;
    Integer pendingHeight = this.pendingHeight;
    if (pendingWidth != null && pendingHeight != null && pendingWidth > 0 && pendingHeight > 0) {
      renderThread.setSurfaceSize(pendingWidth, pendingHeight);
    }
  }

  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    // Frame size changes are handled through {@link #setFrameSize}.
  }

  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
    release();
    return true;
  }

  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

  @SuppressLint("ViewConstructor")
  private static final class Viewport {

    public final Rect rect;
    public final double pixelAspectRatio;

    public Viewport(Rect rect, double pixelAspectRatio) {
      this.rect = rect;
      this.pixelAspectRatio = pixelAspectRatio;
    }
  }
}
