package com.rullino.app.develop

/**
 * Shader di sviluppo: identico a quello di Camera Oscura, così le foto escono
 * come nell'anteprima dell'editor. Se lo modifichi là, copialo anche qui.
 */
object FilmShader {
    const val VERTEX = """#version 300 es
in vec2 aPos; out vec2 vUv;
void main(){ vUv=aPos*0.5+0.5; gl_Position=vec4(aPos,0.,1.); }"""

    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
in vec2 vUv; out vec4 outColor;
uniform sampler2D uImg;
uniform int uMode;          // 0 = foto, 1 = LUT 33^3, 2 = originale
uniform vec2 uRes;          // dimensione output in px
uniform float uExposure, uTemp, uTint, uGamma, uContrast, uHighlights, uShadows, uFade, uSat, uVib;
uniform float uHslH[8], uHslS[8], uHslL[8];
uniform vec4 uSplit;        // hue ombre (gradi), intensità ombre, hue luci, intensità luci
uniform float uBalance;
uniform int uMono; uniform vec3 uMix;
uniform vec3 uGrain;        // quantità effettiva (già x ISO), dimensione, ruvidità
uniform float uVignette;
uniform float uSeed;

const float HC[8] = float[8](0.,30.,60.,120.,180.,240.,270.,300.);
const vec3 LW = vec3(0.2126,0.7152,0.0722);

vec3 toLin(vec3 c){ return mix(c/12.92, pow((c+0.055)/1.055, vec3(2.4)), step(0.04045,c)); }
vec3 toSrgb(vec3 c){ return mix(c*12.92, 1.055*pow(c, vec3(1./2.4))-0.055, step(0.0031308,c)); }

vec3 rgb2hsl(vec3 c){
  float mx=max(c.r,max(c.g,c.b)), mn=min(c.r,min(c.g,c.b));
  float l=(mx+mn)*0.5, d=mx-mn, h=0., s=0.;
  if(d>1e-5){
    s = l>0.5 ? d/(2.-mx-mn) : d/(mx+mn);
    if(mx==c.r) h=(c.g-c.b)/d + (c.g<c.b?6.:0.);
    else if(mx==c.g) h=(c.b-c.r)/d+2.;
    else h=(c.r-c.g)/d+4.;
    h*=60.;
  }
  return vec3(h,s,l);
}
vec3 hue2rgb(float h){ // h in gradi, s=1 l=.5
  vec3 k = mod(vec3(0.,8.,4.) + h/30., 12.);
  return 0.5 - 0.5*clamp(min(k-3., 9.-k), -1., 1.);
}
vec3 hsl2rgb(vec3 hsl){
  vec3 p = hue2rgb(hsl.x);
  float c = (1.-abs(2.*hsl.z-1.))*hsl.y;
  return (p-0.5)*c + hsl.z;
}
float scurve(float x, float k){ return x<0.5 ? 0.5*pow(2.*x,k) : 1.-0.5*pow(2.*(1.-x),k); }

float hash(vec2 p){
  uvec2 q = uvec2(ivec2(floor(p))) * uvec2(1597334673u, 3812015801u);
  uint n = (q.x ^ q.y) * 1597334673u;
  return float(n) * (1.0/4294967295.0);
}
float vnoise(vec2 p){
  vec2 i=floor(p), f=fract(p); vec2 u=f*f*(3.-2.*f);
  float a=hash(i), b=hash(i+vec2(1,0)), c=hash(i+vec2(0,1)), d=hash(i+vec2(1,1));
  return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
}

void main(){
  vec3 c;
  if(uMode==1){
    vec2 p=floor(gl_FragCoord.xy);
    c = vec3(mod(p.x,33.), p.y, floor(p.x/33.)) / 32.;
  } else {
    c = texture(uImg, vUv).rgb;
  }
  if(uMode==2){ outColor=vec4(c,1.); return; }

  // 1. Bilanciamento del bianco + esposizione (in lineare)
  vec3 lin = toLin(c);
  vec3 wb = vec3(1.+uTemp*0.3, 1.-uTint*0.25, 1.-uTemp*0.3);
  wb /= dot(wb, LW);
  lin *= wb * exp2(uExposure);
  c = clamp(toSrgb(max(lin,0.)), 0., 1.);

  // 2. Tono
  c = pow(c, vec3(1./uGamma));
  float L = dot(c, LW);
  c += uShadows*0.3*(1.-smoothstep(0.,0.55,L)) + uHighlights*0.3*smoothstep(0.45,1.,L);
  c = clamp(c,0.,1.);
  float k = exp(uContrast*0.9);
  c = vec3(scurve(c.r,k), scurve(c.g,k), scurve(c.b,k));
  c = uFade*0.22 + c*(1.-uFade*0.22);

  // 3. Colori selettivi (8 settori, interpolazione lineare tra vicini)
  vec3 hsl = rgb2hsl(c);
  float dh=0., ds=0., dl=0.;
  for(int i=0;i<8;i++){
    float c0=HC[i], c1 = (i==7) ? 360. : HC[(i+1)%8];
    if(hsl.x>=c0 && hsl.x<c1){
      float t=(hsl.x-c0)/(c1-c0); int j=(i+1)%8;
      dh=mix(uHslH[i],uHslH[j],t); ds=mix(uHslS[i],uHslS[j],t); dl=mix(uHslL[i],uHslL[j],t);
    }
  }
  float sw = smoothstep(0.02,0.25,hsl.y);
  hsl.x = mod(hsl.x + dh*sw + 360., 360.);
  hsl.y = clamp(hsl.y*(1.+ds), 0., 1.);
  hsl.z = clamp(hsl.z + dl*0.3*sw*(1.-abs(2.*hsl.z-1.)), 0., 1.);
  c = hsl2rgb(hsl);

  // 4. Saturazione e vividezza
  L = dot(c, LW);
  c = mix(vec3(L), c, 1.+uSat);
  float s = max(c.r,max(c.g,c.b)) - min(c.r,min(c.g,c.b));
  c = mix(vec3(L), c, 1.+uVib*(1.-clamp(s,0.,1.)));
  c = clamp(c,0.,1.);

  // 5. Bianco e nero
  if(uMono==1){
    float sum = uMix.r+uMix.g+uMix.b;
    float m = dot(c, uMix) / (abs(sum)<0.01 ? 0.01 : sum);
    c = vec3(clamp(m,0.,1.));
  }

  // 6. Viraggio (split toning)
  L = dot(c, LW);
  vec3 ts = hue2rgb(uSplit.x); ts -= dot(ts,LW);
  vec3 th = hue2rgb(uSplit.z); th -= dot(th,LW);
  float ws = pow(1.-L,2.) * (1.-uBalance);
  float wh = L*L * (1.+uBalance);
  c += ts*uSplit.y*ws*0.45 + th*uSplit.w*wh*0.45;
  c = clamp(c,0.,1.);

  if(uMode==0){
    // 7. Vignettatura
    float ar = uRes.x/uRes.y;
    vec2 d = (vUv-0.5)*vec2(ar,1.);
    float r = length(d)/length(vec2(0.5*ar,0.5));
    c *= 1. - uVignette*0.85*smoothstep(0.3,1.05,r);

    // 8. Grana (dimensione relativa a lato corto 1000 px)
    float px = min(uRes.x,uRes.y)/1000.;
    vec2 gp = gl_FragCoord.xy / max(uGrain.y*px, 0.35) + uSeed;
    float n = vnoise(gp) - 0.5;
    n = mix(n, (vnoise(gp*2.7+31.7)-0.5), uGrain.z*0.5) * 2.;
    vec3 g = vec3(n);
    if(uMono==0){
      g += (vec3(vnoise(gp+11.3), vnoise(gp+47.9), vnoise(gp+83.1)) - 0.5) * 0.6;
    }
    L = dot(c, LW);
    float mid = 0.35 + 0.65*(4.*L*(1.-L));
    c += g * uGrain.x * 0.22 * mid;
  }
  outColor = vec4(clamp(c,0.,1.), 1.);
}"""
}
