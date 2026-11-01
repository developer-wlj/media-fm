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
package androidx.media3.effect;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;

/**
 * Applies the color related parts of a {@link VideoAdjustment} (saturation, contrast, brightness,
 * red/blue gain, gamma and hue rotation) to each frame.
 *
 * <p>This effect does not support HDR input; using it with HDR frames throws a {@link
 * VideoFrameProcessingException}.
 */
@UnstableApi
public final class ColorToneAdjustEffect implements GlEffect {

  private volatile VideoAdjustment adjustment;

  /** Creates an effect with the identity {@link VideoAdjustment#DEFAULT adjustment}. */
  public ColorToneAdjustEffect() {
    this.adjustment = VideoAdjustment.DEFAULT;
  }

  /** Sets the adjustment to apply, replacing any previous configuration. */
  public void setAdjustment(VideoAdjustment adjustment) {
    this.adjustment = adjustment;
  }

  @Override
  public boolean isNoOp(int inputWidth, int inputHeight) {
    VideoAdjustment current = adjustment;
    return current.isColorNoOp() && current.isToneNoOp();
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new ColorToneShaderProgram(context, useHdr);
  }

  private final class ColorToneShaderProgram extends BaseGlShaderProgram {

    private final GlProgram glProgram;

    public ColorToneShaderProgram(Context context, boolean useHdr)
        throws VideoFrameProcessingException {
      super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
      if (useHdr) {
        throw new VideoFrameProcessingException("Video adjustment does not support HDR");
      }
      try {
        glProgram =
            new GlProgram(
                context,
                /* vertexShaderResId= */ R.raw.vertex_shader_video_adjustment_es2,
                /* fragmentShaderResId= */ R.raw.fragment_shader_color_tone_adjustment_es2);
      } catch (IOException | GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      float[] identityMatrix = GlUtil.create4x4IdentityMatrix();
      glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix);
      glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix);
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
      return new Size(inputWidth, inputHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
        throws VideoFrameProcessingException {
      VideoAdjustment current = adjustment;
      try {
        glProgram.use();
        glProgram.setFloatsUniform("uColorMatrix", buildColorMatrix(current));
        glProgram.setFloatUniform("uGamma", current.getGamma());
        glProgram.setFloatUniform("uHue", current.getHue());
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
        glProgram.bindAttributesAndUniforms();
        // The four-vertex triangle strip forms a quad.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e, presentationTimeUs);
      }
    }
  }

  /**
   * Builds the 4x4 color matrix combining saturation, contrast, brightness and the red/blue
   * channel gains. The matrix is applied in column-major order, as expected by GLES.
   */
  /* package */ static float[] buildColorMatrix(VideoAdjustment adjustment) {
    float saturation = adjustment.getSaturation();
    float contrast = adjustment.getContrast();
    float redGain = adjustment.getRedGain();
    float blueGain = adjustment.getBlueGain();
    float brightness = adjustment.getBrightness();
    float lumaWeight = 1.0f - saturation;
    // Brightness shifts the output around the mid-point preserved by contrast.
    float offset = ((1.0f - contrast) * 0.5f) + brightness;
    float redLuma = 0.2126f * lumaWeight;
    float greenLuma = 0.7152f * lumaWeight;
    float blueLuma = 0.0722f * lumaWeight;
    return new float[] {
      (redLuma + saturation) * contrast * redGain,
      greenLuma * contrast,
      redLuma * contrast * blueGain,
      0.0f,
      greenLuma * contrast * redGain,
      (greenLuma + saturation) * contrast,
      greenLuma * contrast * blueGain,
      0.0f,
      blueLuma * contrast * redGain,
      blueLuma * contrast,
      (blueLuma + saturation) * contrast * blueGain,
      0.0f,
      redGain * offset,
      offset,
      blueGain * offset,
      1.0f
    };
  }
}
