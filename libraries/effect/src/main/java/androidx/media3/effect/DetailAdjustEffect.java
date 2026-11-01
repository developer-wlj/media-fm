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
 * Applies the detail related parts of a {@link VideoAdjustment} (edge-aware sharpening and shadow
 * lifting) to each frame.
 *
 * <p>This effect does not support HDR input; using it with HDR frames throws a {@link
 * VideoFrameProcessingException}.
 */
@UnstableApi
public final class DetailAdjustEffect implements GlEffect {

  private volatile VideoAdjustment adjustment;

  /** Creates an effect with the identity {@link VideoAdjustment#DEFAULT adjustment}. */
  public DetailAdjustEffect() {
    this.adjustment = VideoAdjustment.DEFAULT;
  }

  /** Sets the adjustment to apply, replacing any previous configuration. */
  public void setAdjustment(VideoAdjustment adjustment) {
    this.adjustment = adjustment;
  }

  @Override
  public boolean isNoOp(int inputWidth, int inputHeight) {
    return adjustment.isDetailNoOp();
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new DetailShaderProgram(context, useHdr);
  }

  private final class DetailShaderProgram extends BaseGlShaderProgram {

    private final GlProgram glProgram;

    public DetailShaderProgram(Context context, boolean useHdr)
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
                /* fragmentShaderResId= */ R.raw.fragment_shader_detail_adjustment_es2);
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
      glProgram.setFloatsUniform("uTexelSize", new float[] {1.0f / inputWidth, 1.0f / inputHeight});
      return new Size(inputWidth, inputHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
        throws VideoFrameProcessingException {
      VideoAdjustment current = adjustment;
      try {
        glProgram.use();
        glProgram.setFloatUniform("uSharpness", current.getSharpness());
        glProgram.setFloatUniform("uThreshold", current.getThreshold());
        glProgram.setFloatUniform("uShadowLift", current.getShadowLift());
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
        glProgram.bindAttributesAndUniforms();
        // The four-vertex triangle strip forms a quad.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e, presentationTimeUs);
      }
    }
  }
}
