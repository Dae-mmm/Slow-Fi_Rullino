import Foundation
import Metal

/// Shader di sviluppo: la stessa matematica di Camera Oscura e dell'app Android, tradotta in Metal.
/// I parametri arrivano come un array piatto di float (vedi `uniforms`).
enum FilmShaderSource {
    static let metal = """
#include <metal_stdlib>
using namespace metal;

constant float HC[8] = {0., 30., 60., 120., 180., 240., 270., 300.};
constant float3 LW = float3(0.2126, 0.7152, 0.0722);

static float gmod(float x, float y) { return x - y * floor(x / y); }
static float3 gmod3(float3 x, float y) { return x - y * floor(x / y); }

static float3 toLin(float3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(0.04045, c)); }
static float3 toSrgb(float3 c) { return mix(c * 12.92, 1.055 * pow(c, float3(1. / 2.4)) - 0.055, step(0.0031308, c)); }

static float3 rgb2hsl(float3 c) {
  float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
  float l = (mx + mn) * 0.5, d = mx - mn, h = 0., s = 0.;
  if (d > 1e-5) {
    s = l > 0.5 ? d / (2. - mx - mn) : d / (mx + mn);
    if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6. : 0.);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.;
    else h = (c.r - c.g) / d + 4.;
    h *= 60.;
  }
  return float3(h, s, l);
}
static float3 hue2rgb(float h) {
  float3 k = gmod3(float3(0., 8., 4.) + h / 30., 12.);
  return 0.5 - 0.5 * clamp(min(k - 3., 9. - k), -1., 1.);
}
static float3 hsl2rgb(float3 hsl) {
  float3 p = hue2rgb(hsl.x);
  float c = (1. - abs(2. * hsl.z - 1.)) * hsl.y;
  return (p - 0.5) * c + hsl.z;
}
static float scurve(float x, float k) { return x < 0.5 ? 0.5 * pow(2. * x, k) : 1. - 0.5 * pow(2. * (1. - x), k); }

static float hash(float2 p) {
  uint2 q = uint2(int2(floor(p))) * uint2(1597334673u, 3812015801u);
  uint n = (q.x ^ q.y) * 1597334673u;
  return float(n) * (1.0 / 4294967295.0);
}
static float vnoise(float2 p) {
  float2 i = floor(p), f = fract(p); float2 u = f * f * (3. - 2. * f);
  float a = hash(i), b = hash(i + float2(1, 0)), c = hash(i + float2(0, 1)), d = hash(i + float2(1, 1));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

kernel void film(texture2d<float, access::read> src [[texture(0)]],
                 texture2d<float, access::write> dst [[texture(1)]],
                 constant float *u [[buffer(0)]],
                 uint2 gid [[thread_position_in_grid]]) {
  if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
  float2 res = float2(u[0], u[1]);
  float2 frag = float2(gid) + 0.5;
  float2 uv = frag / res;
  float3 c = src.read(gid).rgb;

  // 0. Esposizione e sviluppo (come nello shader Android): negativa recuperata in stampa
  //    con ombre vuote e luci compresse, diapositiva senza recupero.
  bool slide = u[52] > 0.5;
  float push = u[51];
  float eff = u[50] + push * 0.85;
  float under = slide ? 0. : max(-eff, 0.);
  float over = slide ? 0. : max(eff, 0.);
  float3 lin = toLin(c);
  if (slide) {
    lin *= exp2(u[50] * 0.9 + push * 0.6);
  } else {
    lin *= exp2(eff * 0.3);
    lin = max(lin - 0.004 * under * under, 0.);
    lin = lin / (1. + lin * 0.12 * over);
  }

  // 1. Bilanciamento del bianco + esposizione (in lineare)
  float3 wb = float3(1. + u[3] * 0.3, 1. - u[4] * 0.25, 1. - u[3] * 0.3);
  wb /= dot(wb, LW);
  lin *= wb * exp2(u[2]);
  c = clamp(toSrgb(max(lin, 0.)), 0., 1.);

  // 2. Tono
  c = pow(c, float3(1. / u[5]));
  float L = dot(c, LW);
  c += u[8] * 0.3 * (1. - smoothstep(0., 0.55, L)) + u[7] * 0.3 * smoothstep(0.45, 1., L);
  c = clamp(c, 0., 1.);
  float k = exp(u[6] * 0.9 + push * 0.18 + (slide ? 0. : -0.05 * over));
  c = float3(scurve(c.r, k), scurve(c.g, k), scurve(c.b, k));
  c = u[9] * 0.22 + c * (1. - u[9] * 0.22);

  // 3. Colori selettivi
  float3 hsl = rgb2hsl(c);
  float dh = 0., ds = 0., dl = 0.;
  for (int i = 0; i < 8; i++) {
    int j = (i + 1) % 8;
    float c0 = HC[i], c1 = (i == 7) ? 360. : HC[j];
    if (hsl.x >= c0 && hsl.x < c1) {
      float t = (hsl.x - c0) / (c1 - c0);
      dh = mix(u[12 + i], u[12 + j], t); ds = mix(u[20 + i], u[20 + j], t); dl = mix(u[28 + i], u[28 + j], t);
    }
  }
  float sw = smoothstep(0.02, 0.25, hsl.y);
  hsl.x = gmod(hsl.x + dh * sw + 360., 360.);
  hsl.y = clamp(hsl.y * (1. + ds), 0., 1.);
  hsl.z = clamp(hsl.z + dl * 0.3 * sw * (1. - abs(2. * hsl.z - 1.)), 0., 1.);
  c = hsl2rgb(hsl);

  // 4. Saturazione e vividezza
  L = dot(c, LW);
  c = mix(float3(L), c, 1. + u[10] - 0.07 * under + 0.04 * push);
  float s = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
  c = mix(float3(L), c, 1. + u[11] * (1. - clamp(s, 0., 1.)));
  c = clamp(c, 0., 1.);

  // 5. Bianco e nero
  bool monoOn = u[41] > 0.5;
  if (monoOn) {
    float3 mixv = float3(u[42], u[43], u[44]);
    float sum = mixv.r + mixv.g + mixv.b;
    float m = dot(c, mixv) / (abs(sum) < 0.01 ? 0.01 : sum);
    c = float3(clamp(m, 0., 1.));
  }

  // 6. Viraggio
  L = dot(c, LW);
  float3 ts = hue2rgb(u[36]); ts -= dot(ts, LW);
  float3 th = hue2rgb(u[38]); th -= dot(th, LW);
  float ws = pow(1. - L, 2.) * (1. - u[40]);
  float wh = L * L * (1. + u[40]);
  c += ts * u[37] * ws * 0.45 + th * u[39] * wh * 0.45;
  c = clamp(c, 0., 1.);

  // 7. Vignettatura
  float ar = res.x / res.y;
  float2 dv = (uv - 0.5) * float2(ar, 1.);
  float r = length(dv) / length(float2(0.5 * ar, 0.5));
  c *= 1. - u[48] * 0.85 * smoothstep(0.3, 1.05, r);

  // 8. Grana (dimensione relativa a lato corto 1000 px)
  float px = min(res.x, res.y) / 1000.;
  float2 gp = frag / max(u[46] * px, 0.35) + u[49];
  float n = vnoise(gp) - 0.5;
  n = mix(n, (vnoise(gp * 2.7 + 31.7) - 0.5), u[47] * 0.5) * 2.;
  float3 g = float3(n);
  if (!monoOn) {
    g += (float3(vnoise(gp + 11.3), vnoise(gp + 47.9), vnoise(gp + 83.1)) - 0.5) * 0.6;
  }
  L = dot(c, LW);
  float mid = 0.35 + 0.65 * (4. * L * (1. - L));
  float gm = (1. + 0.4 * under - min(0.08 * over, 0.3)) * (1. + 0.35 * max(push, 0.) - 0.15 * max(-push, 0.));
  c += g * u[45] * gm * 0.22 * mid;
  dst.write(float4(clamp(c, 0., 1.), 1.), gid);
}
"""
}

/// Rendering GPU di un fotogramma: pixel RGBA8 sRGB in ingresso e in uscita.
final class FilmRenderer {
    static let shared = FilmRenderer()

    private let device: MTLDevice?
    private let queue: MTLCommandQueue?
    private let pipeline: MTLComputePipelineState?

    private init() {
        device = MTLCreateSystemDefaultDevice()
        queue = device?.makeCommandQueue()
        if let device, let lib = try? device.makeLibrary(source: FilmShaderSource.metal, options: nil),
           let fn = lib.makeFunction(name: "film") {
            pipeline = try? device.makeComputePipelineState(function: fn)
        } else {
            pipeline = nil
        }
    }

    /// Stesse conversioni di setUniforms() in Camera Oscura.
    static func uniforms(_ p: FilmParams, iso: Int, w: Int, h: Int, seed: Float,
                         exposureError: Float = 0, push: Int = 0, slide: Bool = false) -> [Float] {
        var u = [Float](repeating: 0, count: 53)
        u[50] = exposureError; u[51] = Float(push); u[52] = slide ? 1 : 0
        u[0] = Float(w); u[1] = Float(h)
        u[2] = p.exposure; u[3] = p.temperature / 100; u[4] = p.tint / 100
        u[5] = min(max(p.gamma, 0.1), 5); u[6] = p.contrast / 100; u[7] = p.highlights / 100
        u[8] = p.shadows / 100; u[9] = p.fade / 100; u[10] = p.saturation / 100; u[11] = p.vibrance / 100
        for (i, k) in hueKeys.enumerated() {
            let a = p.hsl[k] ?? HslAdj()
            u[12 + i] = a.hue; u[20 + i] = a.sat / 100; u[28 + i] = a.lum / 100
        }
        u[36] = p.split.shadowHue; u[37] = p.split.shadowAmount / 100
        u[38] = p.split.highlightHue; u[39] = p.split.highlightAmount / 100; u[40] = p.split.balance / 100
        u[41] = p.mono.enabled ? 1 : 0
        u[42] = p.mono.red / 100; u[43] = p.mono.green / 100; u[44] = p.mono.blue / 100
        let isoFactor = (Float(max(iso, 1)) / 400).squareRoot()
        u[45] = p.grain.amount / 100 * isoFactor; u[46] = p.grain.size; u[47] = p.grain.roughness / 100
        u[48] = p.vignette / 100; u[49] = seed
        return u
    }

    /// Sviluppa i pixel in place. Restituisce false se la GPU non è disponibile.
    func render(pixels: UnsafeMutableRawPointer, width w: Int, height h: Int, uniforms: [Float]) -> Bool {
        guard let device, let queue, let pipeline else { return false }
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: w, height: h, mipmapped: false)
        desc.usage = [.shaderRead]
        guard let src = device.makeTexture(descriptor: desc) else { return false }
        desc.usage = [.shaderWrite]
        guard let dst = device.makeTexture(descriptor: desc) else { return false }
        let region = MTLRegionMake2D(0, 0, w, h)
        src.replace(region: region, mipmapLevel: 0, withBytes: pixels, bytesPerRow: w * 4)
        var u = uniforms
        guard let buf = device.makeBuffer(bytes: &u, length: u.count * MemoryLayout<Float>.stride),
              let cmd = queue.makeCommandBuffer(),
              let enc = cmd.makeComputeCommandEncoder() else { return false }
        enc.setComputePipelineState(pipeline)
        enc.setTexture(src, index: 0)
        enc.setTexture(dst, index: 1)
        enc.setBuffer(buf, offset: 0, index: 0)
        let tg = MTLSize(width: 16, height: 16, depth: 1)
        let groups = MTLSize(width: (w + 15) / 16, height: (h + 15) / 16, depth: 1)
        enc.dispatchThreadgroups(groups, threadsPerThreadgroup: tg)
        enc.endEncoding()
        cmd.commit()
        cmd.waitUntilCompleted()
        dst.getBytes(pixels, bytesPerRow: w * 4, from: region, mipmapLevel: 0)
        return true
    }
}
