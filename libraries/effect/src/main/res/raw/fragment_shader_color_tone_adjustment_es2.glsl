#version 100
// Copyright 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

precision highp float;
uniform sampler2D uTexSampler;
uniform mat4 uColorMatrix;
uniform float uGamma;
uniform float uHue;
varying vec2 vTexSamplingCoord;

vec3 rotateHue(vec3 color, float hue) {
  float angle = radians(hue);
  float s = sin(angle);
  float c = cos(angle);
  float y = dot(color, vec3(0.299, 0.587, 0.114));
  float i = dot(color, vec3(0.596, -0.274, -0.322));
  float q = dot(color, vec3(0.211, -0.523, 0.312));
  float ii = i * c - q * s;
  float qq = i * s + q * c;
  return clamp(
      vec3(y + 0.956 * ii + 0.621 * qq,
           y - 0.272 * ii - 0.647 * qq,
           y - 1.106 * ii + 1.703 * qq),
      0.0, 1.0);
}

void main() {
  vec4 sample = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 color = clamp((uColorMatrix * vec4(sample.rgb, 1.0)).rgb, 0.0, 1.0);
  if (abs(uGamma - 1.0) > 0.0001) {
    color = pow(color, vec3(1.0 / max(uGamma, 0.0001)));
  }
  if (abs(uHue) > 0.0001) {
    color = rotateHue(color, uHue);
  }
  gl_FragColor = vec4(color, sample.a);
}
