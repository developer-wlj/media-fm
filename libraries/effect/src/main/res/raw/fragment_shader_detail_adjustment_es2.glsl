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
uniform vec2 uTexelSize;
uniform float uSharpness;
uniform float uThreshold;
uniform float uShadowLift;
varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float SHADOW_START = 0.08;
const float SHADOW_END = 0.55;

void main() {
  vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 color = center.rgb;
  if (uSharpness > 0.0) {
    vec3 left =
        texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x, 0.0)).rgb;
    vec3 right =
        texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
    vec3 up =
        texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelSize.y)).rgb;
    vec3 down =
        texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;
    vec3 edge = color * 4.0 - left - right - up - down;
    float edgeStrength = max(max(abs(edge.r), abs(edge.g)), abs(edge.b));
    float mask =
        smoothstep(uThreshold, uThreshold * 2.0 + 0.0001, edgeStrength);
    color = clamp(color + edge * uSharpness * mask, 0.0, 1.0);
  }
  if (uShadowLift > 0.0) {
    float luma = dot(color, LUMA);
    float shadow = 1.0 - smoothstep(SHADOW_START, SHADOW_END, luma);
    color = clamp(color + (1.0 - color) * uShadowLift * shadow, 0.0, 1.0);
  }
  gl_FragColor = vec4(color, center.a);
}
