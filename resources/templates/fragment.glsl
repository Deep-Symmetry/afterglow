#ifdef GL_ES
precision highp float;
#endif
		
const int MAX_SPOTS = {{max-lights}};
const float SCALE = {{scale}};

uniform float iGlobalTime;
uniform sampler2D iChannel0;
uniform vec3 iResolution;
uniform int iNumSpots;
uniform vec3 iSpotPosition[MAX_SPOTS];
uniform vec4 iSpotColor[MAX_SPOTS];
// uniform mat3 iSpotRotation[MAX_SPOTS];


//////
// Utility stuff
//////
#define PI 3.14159265

mat3 rotx(float a) {
  mat3 rot;
  rot[0] = vec3(1.0, 0.0, 0.0);
  rot[1] = vec3(0.0, cos(a), -sin(a));
  rot[2] = vec3(0.0, sin(a), cos(a));
  return rot;
}

mat3 roty(float a) {
  mat3 rot;
  rot[0] = vec3(cos(a), 0.0, sin(a));
  rot[1] = vec3(0.0, 1.0, 0.0);
  rot[2] = vec3(-sin(a), 0.0, cos(a));
  return rot;
}

mat3 rotz(float a) {
  mat3 rot;
  rot[0] = vec3(cos(a), -sin(a), 0.0);
  rot[1] = vec3(sin(a), cos(a), 0.0);
  rot[2] = vec3(0.0, 0.0, 1.0);
  return rot;
}

// noise from iq's hell shader
float noise(in vec3 x) {
  vec3 p = floor(x);
  vec3 f = fract(x);
  f = f*f*(3.0-2.0*f);
  
  vec2 uv = (p.xy+vec2(37.0,17.0)*p.z) + f.xy;
  vec2 rg = texture2D( iChannel0, (uv+ 0.5)/256.0, -100.0 ).yx;
  return mix( rg.x, rg.y, f.z ) - 0.5;
}

///////
// Distance function from http://www.iquilezles.org/www/articles/distfunctions/distfunctions.htm
//////

float sdCappedCylinder(vec3 p, vec2 h) {
  vec2 d = abs(vec2(length(p.xz),p.y)) - h;
  return min(max(d.x,d.y),0.0) + length(max(d,0.0));
}

///////////////////////////////

const float NOTHING = -0.1;

const float LIGHT_BASE_W = 0.2;
const float CONE_W = 0.22;

vec2 maplight(vec3 orp, out bool spot_hit[MAX_SPOTS]) {
  float t = iGlobalTime * 0.025;
  float minm = 10000.0;
  float mm = 10000.0;
  float num_hit = 0.0;
  
  for (int i = 0; i < MAX_SPOTS; ++i) {
    spot_hit[i] = false;
    if (i < iNumSpots) {
      vec3 rp = orp;
      vec3 _rp = rp;
      rp += iSpotPosition[i];
      // rp *= SPOT_ROTATION[i];
      
      float m = sdCappedCylinder(rp, vec2(CONE_W, 1.0));
      
      float l = -LIGHT_BASE_W + length(rp) * 0.2;
      m -= l;
      float d = dot(rp, vec3(0.0, -1.0, 0.0));
      
      if( m < 0.0 && d >= 0.0) {
        vec3 uv = _rp + vec3(t, 0.0, 0.0);
        float n = noise(uv * 10.0) - 0.5;
        
        uv = _rp + vec3(t * 1.2, 0.0, 0.0);
        n += noise(uv * 22.50) * 0.5;
        
        uv = _rp + vec3(t * 2.0, 0.0, 0.0);
        n += noise(uv * 52.50) * 0.5;
        
        uv = _rp + vec3(t * 2.8, 0.0, 0.0);
        n += noise(uv * 152.50) * 0.25;
        
        mm = min(n, m);
        mm = min(mm, -0.2);
        spot_hit[i] = true;
        num_hit += 1.0; 
      }
      minm = min(abs(m), minm);
    }
  }
  
  if(num_hit > 0.0) {
    return vec2(mm, num_hit);
  }
  
  return vec2(minm, NOTHING);
}


const int MAX_STEPS = 250;
const float MIN_STEP = 0.0052;
const float FAR = 0.5;

const float LIGHT_POW = 2.5;
const float LIGHT_INTENS = 0.15;
const float FLOOR_Y = -0.25;

void colorize(in vec4 fgc, in vec3 pos, in vec4 spotcol, inout vec4 color) {
  float flf = inversesqrt(length(pos));
  flf = pow(flf, LIGHT_POW) * LIGHT_INTENS;
  color += fgc * flf * spotcol;
}

bool trace(in vec3 ro, in vec3 rd, out vec4 color) {
  color = vec4(0.0);
  vec3 rp = ro;
  float h = 0.0;
  float sg = (sin(iGlobalTime) + 1.0) * 0.25;
  float sg2 = (sin(iGlobalTime * 0.5) + 1.0) * 0.25;
  bool spot_hit[MAX_SPOTS];
  
  for (int i = 0; i < MAX_STEPS; ++i) {
    rp += rd * max(MIN_STEP, h * 0.5);
    vec2 hp = maplight(rp, spot_hit);
    h = hp.x;
    
    if(rp.z > FAR) {
      return false;
    }
    
    if(h < 0.0) {
      vec4 fgc = vec4(abs(h * 0.05));
      
      if (hp.y > NOTHING) {
        for (int i = 0; i < MAX_SPOTS; ++i) {
          if (spot_hit[i]) {
            colorize(fgc, (-iSpotPosition[i] - rp), iSpotColor[i], color);
          }
        }
      }
            
      if(rp.y < FLOOR_Y && rp.y > FLOOR_Y - 0.0017) {
        vec4 floorColor = vec4(0.0);
        for (int i = 0; i < MAX_SPOTS; ++i) {
          if (spot_hit[i]) {
            floorColor += iSpotColor[i];
          }
          color += vec4(0.1, 0.1, 0.1, 0.0) * floorColor;
          return true;
        }
      }
      
      if(rp.y < FLOOR_Y) {
        color = vec4(0.0);
        return true;
      }            
    }
  }
  return false;
}


void main(void) {
  vec2 uv = gl_FragCoord.xy / iResolution.xy;
  float aspect = iResolution.x / iResolution.y;
  
  vec3 rd = (vec3(uv - vec2(0.5), 1.0));
  rd.y /= aspect;
  rd = normalize(rd);
  trace(vec3(0.0, 0.0, -1.1), rd, gl_FragColor);
}
