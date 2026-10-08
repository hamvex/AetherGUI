const vertexSource = `#version 300 es
in vec3 aPosition;
uniform mat4 uProjection;
uniform mat4 uModel;
uniform float uTime;
uniform float uAmplitude;
uniform float uScale;
uniform float uStretch;
uniform float uMacro;
out vec3 vNormal;
out vec3 vPosition;

vec3 lobeDirection(float phase, vec3 rates) {
  vec3 direction = vec3(
    sin(phase + uTime * rates.x) + 0.34 * sin(uTime * rates.z + phase * 1.7),
    cos(phase * 1.31 - uTime * rates.y) + 0.27 * sin(uTime * rates.x - phase),
    sin(phase * 0.73 + uTime * rates.z) + 0.31 * cos(uTime * rates.y + phase * 0.4)
  );
  return normalize(direction);
}

float lobe(vec3 p, vec3 direction, float sharpness) {
  return exp((dot(p, direction) - 1.0) * sharpness);
}

float radiusAt(vec3 source) {
  vec3 p = normalize(source);
  vec3 c1 = lobeDirection(0.3, vec3(0.73, 0.47, 0.61));
  vec3 c2 = lobeDirection(2.1, vec3(-0.43, 0.69, 0.37));
  vec3 c3 = lobeDirection(4.0, vec3(0.51, -0.39, 0.77));
  vec3 c4 = lobeDirection(5.7, vec3(-0.67, 0.33, -0.49));
  float lobes =
    lobe(p, c1, 3.7) * (0.82 + 0.18 * sin(uTime * 0.83)) -
    lobe(p, normalize(c1 * 0.58 + c2 * 0.82), 5.0) * 0.52 +
    lobe(p, c2, 4.2) * (0.68 + 0.22 * cos(uTime * 0.71 + 1.2)) -
    lobe(p, normalize(c2 * 0.54 + c3 * 0.84), 5.4) * 0.46 +
    lobe(p, c3, 3.4) * (0.76 + 0.19 * sin(uTime * 0.59 + 2.4)) -
    lobe(p, normalize(c3 * 0.62 + c4 * 0.78), 4.7) * 0.55 +
    lobe(p, c4, 4.0) * (0.63 + 0.24 * cos(uTime * 0.77 + 0.5)) -
    lobe(p, normalize(c4 * 0.60 + c1 * 0.80), 5.2) * 0.43;

  vec3 warp = p + 0.22 * vec3(
    sin(p.y * 1.8 + uTime * 0.63) + cos(p.z * 1.3 - uTime * 0.41),
    sin(p.z * 1.6 - uTime * 0.52) + cos(p.x * 1.7 + uTime * 0.36),
    sin(p.x * 1.5 + uTime * 0.46) + cos(p.y * 1.4 - uTime * 0.57)
  );
  float flow =
    sin(dot(warp, vec3(1.31, 1.73, 1.09)) + uTime * 0.44) * 0.46 +
    sin(dot(warp.yzx, vec3(2.21, 1.27, 1.83)) - uTime * 0.31) * 0.31 +
    sin(dot(warp.zxy, vec3(3.37, 2.61, 2.13)) + uTime * 0.23) * 0.14;
  float perimeter = pow(max(0.0, 1.0 - abs(p.z)), 1.7);
  float angle = atan(p.y, p.x);
  float silhouetteFlow = perimeter * (
    sin(angle * 2.0 + uTime * 0.74 + sin(uTime * 0.29) * 0.55) * 0.46 +
    cos(angle * 3.0 - uTime * 0.51 + cos(uTime * 0.37) * 0.38) * 0.16
  );
  float detail = sin(dot(p, vec3(4.7, 3.9, 5.3)) + uTime * 0.38) * 0.09;
  return 1.0 + uAmplitude * (lobes * 0.94 + silhouetteFlow + flow * 0.32 + detail * 0.62);
}

vec3 deformedPosition(vec3 source) {
  vec3 p = normalize(source);
  vec3 displaced = p * radiusAt(p);

  float phaseA = sin(uTime * 0.347 + sin(uTime * 0.113) * 0.73);
  float phaseB = sin(uTime * 0.431 + 1.9 + cos(uTime * 0.157) * 0.61);
  float phaseC = cos(uTime * 0.293 + 4.1 + sin(uTime * 0.197) * 0.49);
  vec3 stretchAxis = normalize(vec3(
    sin(uTime * 0.271 + 0.7) + 0.37 * cos(uTime * 0.619),
    cos(uTime * 0.233 + 2.2) + 0.31 * sin(uTime * 0.557),
    0.38 * sin(uTime * 0.389 + 4.7)
  ));
  vec3 bendAxis = normalize(vec3(-stretchAxis.y, stretchAxis.x, 0.32 + 0.18 * phaseC));

  vec3 anisotropy = vec3(
    1.0 + uMacro * (0.175 * phaseA + 0.075 * phaseC),
    1.0 + uMacro * (0.165 * phaseB - 0.065 * phaseA),
    1.0 - uMacro * (0.075 * phaseA + 0.055 * phaseB)
  );
  anisotropy = clamp(anisotropy, vec3(0.76), vec3(1.29));
  displaced *= anisotropy;

  float along = dot(displaced, stretchAxis);
  float stretchWave = 0.54 + 0.46 * sin(uTime * 0.503 + phaseB * 1.3);
  displaced += stretchAxis * along * uMacro * uStretch * (0.11 + 0.13 * stretchWave);
  displaced -= (displaced - stretchAxis * along) * uMacro * uStretch * (0.025 + 0.035 * stretchWave);

  float pinchPhase = 0.5 + 0.5 * sin(uTime * 0.367 + 2.8 + phaseA);
  float pinchBand = exp(-along * along * 4.2) * pinchPhase;
  vec3 axial = stretchAxis * dot(displaced, stretchAxis);
  displaced = axial + (displaced - axial) * (1.0 - uMacro * 0.115 * pinchBand);

  float bend = uMacro * (0.075 * phaseB + 0.045 * phaseC);
  displaced += bendAxis * bend * (along * along - 0.28);
  displaced += uMacro * vec3(
    0.075 * sin(uTime * 0.241 + 0.4) + 0.025 * sin(uTime * 0.733),
    0.068 * cos(uTime * 0.217 + 2.6) + 0.022 * cos(uTime * 0.677),
    0.025 * sin(uTime * 0.319 + 5.1)
  );
  return displaced;
}

void main() {
  vec3 p = normalize(aPosition);
  vec3 displaced = deformedPosition(p);
  vec3 reference = abs(p.y) < 0.92 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
  vec3 tangent = normalize(cross(reference, p));
  vec3 bitangent = normalize(cross(p, tangent));
  float epsilon = 0.012;
  vec3 tangentPoint = deformedPosition(normalize(p + tangent * epsilon));
  vec3 bitangentPoint = deformedPosition(normalize(p + bitangent * epsilon));
  vec3 normal = normalize(cross(tangentPoint - displaced, bitangentPoint - displaced));
  if (dot(normal, displaced) < 0.0) normal = -normal;
  vNormal = normal;
  vPosition = displaced;
  gl_Position = uProjection * uModel * vec4(displaced * uScale, 1.0);
}`;

const fragmentSource = `#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vPosition;
uniform float uTime;
uniform float uRoughness;
uniform float uFresnel;
uniform float uRim;
uniform float uDark;
out vec4 outColor;

void main() {
  vec3 n = normalize(vNormal);
  vec3 view = normalize(vec3(0.0, 0.0, 2.8) - vPosition);
  float lightPhase = uTime * 0.31;
  vec3 key = normalize(vec3(-0.62 + sin(lightPhase) * 0.18, 0.78, 0.52 + cos(lightPhase * 0.83) * 0.16));
  vec3 rim = normalize(vec3(0.72 + cos(lightPhase * 0.71) * 0.16, 0.12, -0.68));
  vec3 oppositeRim = normalize(vec3(-0.78, -0.18 + sin(lightPhase * 0.63) * 0.18, -0.56));
  vec3 reflection = reflect(-view, n);
  float diffuse = max(dot(n, key), 0.0);
  float broadReflection = pow(max(dot(reflection, normalize(vec3(-0.35, 0.82, 0.45))), 0.0), 2.0);
  float flowingBand = pow(max(0.0, 1.0 - abs(reflection.y - (0.18 + sin(uTime * 0.38 + reflection.x * 2.4) * 0.22))), 5.0);
  float rimLight = pow(max(dot(n, rim), 0.0), 2.0) * uRim;
  float oppositeEdge = pow(max(dot(n, oppositeRim), 0.0), 2.4) * uRim;
  float fresnel = pow(1.0 - max(dot(n, view), 0.0), 3.4) * uFresnel;
  float gloss = pow(max(dot(reflect(-key, n), view), 0.0), mix(24.0, 66.0, 1.0 - uRoughness));
  float secondaryGloss = pow(max(dot(reflect(-rim, n), view), 0.0), 30.0);
  vec3 color;
  if (uDark > 0.5) {
    vec3 body = mix(vec3(0.001, 0.002, 0.003), vec3(0.012, 0.016, 0.022), diffuse * 0.22);
    vec3 silver = mix(vec3(0.72, 0.77, 0.84), vec3(0.96, 0.98, 1.0), 0.58);
    color = body + silver * (gloss * 1.28 + secondaryGloss * 0.34 + broadReflection * 0.18 + flowingBand * 0.13);
    color += vec3(0.22, 0.27, 0.34) * (diffuse * 0.055 + rimLight * 0.46 + oppositeEdge * 0.29);
    color += silver * fresnel * 0.67;
  } else {
    float curvature = clamp(0.30 + diffuse * 0.48 + broadReflection * 0.16, 0.0, 1.0);
    vec3 shadow = vec3(0.22, 0.25, 0.29);
    vec3 pearl = vec3(0.73, 0.77, 0.82);
    vec3 whiteSilver = vec3(0.98, 0.99, 1.0);
    color = mix(shadow, pearl, curvature);
    color += whiteSilver * (gloss * 0.72 + secondaryGloss * 0.24 + flowingBand * 0.08);
    color += vec3(0.30, 0.34, 0.39) * (rimLight * 0.24 + oppositeEdge * 0.20);
    color += whiteSilver * fresnel * 0.34;
  }
  outColor = vec4(color, 1.0);
}`;

const stateTargets = {
  // dev.028: connected is a CALM state (Android dev.020 CHANGE 7: liquid motion restored
  // with a calm 12-second cycle — slow breathing, no fast spin). speed 0.30 ≈ one visual
  // lobe cycle per ~12s at the base shader rates. The dev.027 value (1.20) made Connected
  // the second-fastest state of all.
  disconnected: { amplitude: 0.162, speed: 0.96, roughness: 0.26, fresnel: 0.84, rim: 1.00, stretch: 0.58, macro: 0.60 },
  connecting: { amplitude: 0.252, speed: 1.52, roughness: 0.20, fresnel: 1.00, rim: 1.18, stretch: 1.00, macro: 1.00 },
  scanning: { amplitude: 0.252, speed: 1.52, roughness: 0.20, fresnel: 1.00, rim: 1.18, stretch: 1.00, macro: 1.00 },
  connected: { amplitude: 0.216, speed: 0.30, roughness: 0.21, fresnel: 0.97, rim: 1.14, stretch: 0.84, macro: 0.82 },
  reconnecting: { amplitude: 0.274, speed: 1.68, roughness: 0.22, fresnel: 1.08, rim: 1.28, stretch: 1.10, macro: 1.12 },
  disconnecting: { amplitude: 0.102, speed: 0.60, roughness: 0.31, fresnel: 0.70, rim: 0.82, stretch: 0.24, macro: 0.30 },
  error: { amplitude: 0.058, speed: 0.31, roughness: 0.37, fresnel: 0.62, rim: 0.74, stretch: 0.12, macro: 0.18 }
};

function createSphere(subdivisions = 3) {
  const phi = (1 + Math.sqrt(5)) / 2;
  const points = [[-1, phi, 0], [1, phi, 0], [-1, -phi, 0], [1, -phi, 0], [0, -1, phi], [0, 1, phi], [0, -1, -phi], [0, 1, -phi], [phi, 0, -1], [phi, 0, 1], [-phi, 0, -1], [-phi, 0, 1]];
  const vertices = points.map(point => { const length = Math.hypot(...point); return point.map(value => value / length); });
  let faces = [[0, 11, 5], [0, 5, 1], [0, 1, 7], [0, 7, 10], [0, 10, 11], [1, 5, 9], [5, 11, 4], [11, 10, 2], [10, 7, 6], [7, 1, 8], [3, 9, 4], [3, 4, 2], [3, 2, 6], [3, 6, 8], [3, 8, 9], [4, 9, 5], [2, 4, 11], [6, 2, 10], [8, 6, 7], [9, 8, 1]];
  for (let level = 0; level < subdivisions; level++) {
    const cache = new Map();
    const midpoint = (a, b) => { const key = a < b ? `${a}:${b}` : `${b}:${a}`; if (cache.has(key)) return cache.get(key); const point = vertices[a].map((value, index) => (value + vertices[b][index]) * 0.5); const length = Math.hypot(...point); const index = vertices.push(point.map(value => value / length)) - 1; cache.set(key, index); return index; };
    faces = faces.flatMap(([a, b, c]) => { const ab = midpoint(a, b), bc = midpoint(b, c), ca = midpoint(c, a); return [[a, ab, ca], [b, bc, ab], [c, ca, bc], [ab, bc, ca]]; });
  }
  return { vertices: new Float32Array(vertices.flat()), indices: new Uint32Array(faces.flat()) };
}

function compile(gl, type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source); gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const message = gl.getShaderInfoLog(shader) || 'unknown shader error'; gl.deleteShader(shader); throw new Error(message);
  }
  return shader;
}

function link(gl) {
  const program = gl.createProgram();
  gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, vertexSource));
  gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, fragmentSource));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) { const message = gl.getProgramInfoLog(program) || 'unknown program error'; gl.deleteProgram(program); throw new Error(message); }
  return program;
}

function model() { return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]); }
function projection(aspect) { const halfHeight = 1.72, halfWidth = halfHeight * aspect, near = -4, far = 4; return new Float32Array([1 / halfWidth, 0, 0, 0, 0, 1 / halfHeight, 0, 0, 0, 0, -2 / (far - near), 0, 0, 0, -(far + near) / (far - near), 1]); }

export function createMercuryOrb(canvas, { onFallback } = {}) {
  let gl, program, vertexBuffer, indexBuffer, indexCount, uniforms, frame = 0, running = false, destroyed = false;
  const maximumFrameRate = (navigator.hardwareConcurrency || 4) >= 8 ? 60 : 30;
  let style = 'classic', state = 'disconnected', dark = true, reducedMotion = false, lastFrame = 0, lastRender = 0, elapsed = 0, frameInterval = 1000 / maximumFrameRate, slowFrames = 0, fastFrames = 0;
  // Time wrap period: the shader time uniform is reset here before float precision can
  // degrade (the position field is built from sums of trig terms; uTime beyond ~10^4
  // seconds visibly quantizes on mediump/highp mobile-class GPUs). The reset preserves
  // the phase modulo 360 (all shader time uses are sinusoidal at rational-rate multiples
  // of a full second), so the liquid motion is seamless across the wrap. Long-session
  // (multi-hour connected) correctness fix.
  const TIME_WRAP_SECS = 3600;
  const current = { ...stateTargets.disconnected };
  let target = { ...stateTargets.disconnected };
  const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  const setFallback = reason => { console.warn('[mercury-orb] Falling back to Classic:', reason); style = 'classic'; stop(); gl = null; program = null; vertexBuffer = null; indexBuffer = null; canvas.hidden = true; onFallback?.(reason); };
  const resize = () => { if (!gl) return; const rect = canvas.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio || 1, 1.5); const size = Math.min(384, Math.max(160, Math.round(Math.min(rect.width, rect.height) * dpr))); if (canvas.width !== size || canvas.height !== size) { canvas.width = size; canvas.height = size; gl.viewport(0, 0, size, size); } };
  const init = () => { try { gl = canvas.getContext('webgl2', { alpha: true, antialias: true, powerPreference: 'low-power' }); if (!gl) throw new Error('WebGL2 unavailable'); program = link(gl); const sphere = createSphere(); indexCount = sphere.indices.length; vertexBuffer = gl.createBuffer(); indexBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer); gl.bufferData(gl.ARRAY_BUFFER, sphere.vertices, gl.STATIC_DRAW); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indexBuffer); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, sphere.indices, gl.STATIC_DRAW); const position = gl.getAttribLocation(program, 'aPosition'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 3, gl.FLOAT, false, 12, 0); uniforms = Object.fromEntries(['uProjection', 'uModel', 'uTime', 'uAmplitude', 'uScale', 'uStretch', 'uMacro', 'uRoughness', 'uFresnel', 'uRim', 'uDark'].map(name => [name, gl.getUniformLocation(program, name)])); gl.useProgram(program); resize(); } catch (error) { setFallback(error.message); } };
  const render = now => { if (!running || destroyed || style !== 'living-mercury') return; frame = requestAnimationFrame(render); if (lastRender && now - lastRender < frameInterval) return; const frameStarted = performance.now(); if (!lastFrame) lastFrame = now; const delta = Math.min(0.05, Math.max(0, (now - lastFrame) / 1000)); lastFrame = now; lastRender = now; elapsed += delta * (reducedMotion ? target.speed * 0.12 : target.speed); if (elapsed >= TIME_WRAP_SECS) elapsed %= TIME_WRAP_SECS; Object.keys(current).forEach(key => { current[key] += (target[key] - current[key]) * Math.min(1, delta * 3.8); }); resize(); const aspect = canvas.width / Math.max(1, canvas.height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT); gl.enable(gl.DEPTH_TEST); gl.disable(gl.CULL_FACE); gl.useProgram(program); gl.uniformMatrix4fv(uniforms.uProjection, false, projection(aspect)); gl.uniformMatrix4fv(uniforms.uModel, false, model()); gl.uniform1f(uniforms.uTime, elapsed); gl.uniform1f(uniforms.uAmplitude, current.amplitude * (reducedMotion ? 0.30 : 1)); gl.uniform1f(uniforms.uScale, 0.94); gl.uniform1f(uniforms.uStretch, current.stretch * (reducedMotion ? 0.35 : 1)); gl.uniform1f(uniforms.uMacro, current.macro * (reducedMotion ? 0.28 : 1)); gl.uniform1f(uniforms.uRoughness, current.roughness); gl.uniform1f(uniforms.uFresnel, current.fresnel); gl.uniform1f(uniforms.uRim, current.rim); gl.uniform1f(uniforms.uDark, dark ? 1 : 0); gl.drawElements(gl.TRIANGLES, indexCount, gl.UNSIGNED_INT, 0); const frameCost = performance.now() - frameStarted; slowFrames = frameCost > 7 ? slowFrames + 1 : 0; fastFrames = frameCost < 3 ? fastFrames + 1 : 0; if (slowFrames >= 6) { frameInterval = 1000 / 30; slowFrames = 0; fastFrames = 0; } else if (fastFrames >= 180) { frameInterval = 1000 / maximumFrameRate; fastFrames = 0; } };
  // Rendering runs only while the orb is actually on screen: the Home view must be the
  // active view (PROMPT §6: pause when unnecessary — no GPU work while the user browses
  // Configurations/Settings) AND the document visible. Both guards re-check on change.
  const homeVisible = () => !!(canvas.closest('.view')?.classList.contains('active')) && !document.hidden;
  const start = () => { if (running || destroyed || style !== 'living-mercury' || !homeVisible()) return; running = true; lastFrame = 0; frame = requestAnimationFrame(render); };
  const stop = () => { running = false; if (frame) cancelAnimationFrame(frame); frame = 0; lastFrame = 0; lastRender = 0; };
  const visibility = () => { if (!homeVisible()) stop(); else if (style === 'living-mercury') start(); };
  const contextLost = event => { event.preventDefault(); stop(); setFallback('WebGL context lost'); };
  const contextRestored = () => { if (style !== 'living-mercury') return; init(); if (gl) start(); };
  const motionChange = event => { reducedMotion = event.matches; };
  reducedMotion = !!media?.matches;
  media?.addEventListener?.('change', motionChange);
  document.addEventListener('visibilitychange', visibility); canvas.addEventListener('webglcontextlost', contextLost); canvas.addEventListener('webglcontextrestored', contextRestored);
  // The view switcher toggles .active on .view elements; a MutationObserver on the orb's
  // own view class is the cheapest reliable signal (no coupling to app.js navigation).
  const viewObserver = new MutationObserver(visibility);
  viewObserver.observe(canvas.closest('.view') || canvas, { attributes: true, attributeFilter: ['class'] });
  init();
  return {
    setStyle(next) { style = next === 'living-mercury' ? 'living-mercury' : 'classic'; canvas.hidden = style !== 'living-mercury'; if (style === 'living-mercury' && !gl) init(); if (style === 'living-mercury' && gl) start(); else stop(); },
    setState(next) { state = next; target = { ...(stateTargets[next] || stateTargets.disconnected) }; },
    setTheme(theme) { dark = theme === 'dark' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches); },
    get style() { return style; }, get state() { return state; },
    destroy() { destroyed = true; stop(); viewObserver.disconnect(); document.removeEventListener('visibilitychange', visibility); canvas.removeEventListener('webglcontextlost', contextLost); canvas.removeEventListener('webglcontextrestored', contextRestored); media?.removeEventListener?.('change', motionChange); if (gl) { if (vertexBuffer) gl.deleteBuffer(vertexBuffer); if (indexBuffer) gl.deleteBuffer(indexBuffer); if (program) gl.deleteProgram(program); } }
  };
}
