#ifdef GL_ES
precision highp float;
#endif
		
uniform float iGlobalTime;
uniform sampler2D iChannel0;
uniform vec3 iResolution;
uniform int iNumSpots;
//uniform vec3 iSpotPositions[100];
//uniform vec3 iSpotColors[100];



//////
// Utility stuff
//////
#define PI 3.14159

mat3 rotx(float a) { mat3 rot; rot[0] = vec3(1.0, 0.0, 0.0); rot[1] = vec3(0.0, cos(a), -sin(a)); rot[2] = vec3(0.0, sin(a), cos(a)); return rot; }
mat3 roty(float a) { mat3 rot; rot[0] = vec3(cos(a), 0.0, sin(a)); rot[1] = vec3(0.0, 1.0, 0.0); rot[2] = vec3(-sin(a), 0.0, cos(a)); return rot; }
mat3 rotz(float a) { mat3 rot; rot[0] = vec3(cos(a), -sin(a), 0.0); rot[1] = vec3(sin(a), cos(a), 0.0); rot[2] = vec3(0.0, 0.0, 1.0); return rot; }


// noise from iq's hell shader
float noise( in vec3 x )
{
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

float sdCappedCylinder( vec3 p, vec2 h )
{
  vec2 d = abs(vec2(length(p.xz),p.y)) - h;
  return min(max(d.x,d.y),0.0) + length(max(d,0.0));
}

///////////////////////////////

const int MAX_SPOTS = 2;

vec3 SPOT_POS[MAX_SPOTS];
vec4 SPOT_COL[MAX_SPOTS];
mat3 SPOT_ROTATION[MAX_SPOTS];
bool spot_hit[MAX_SPOTS];

vec3 spotpos = vec3(0.5, -0.25, -0.0);
const float NOTHING = -0.1;

const float SMOKE_CONE_1 = 1.0;
const float SMOKE_CONE_2 = 2.0;

const float LIGHT_BASE_W = 0.2;
const float CONE_W = 0.22;

vec2 maplight(vec3 orp)
{
    float t = iGlobalTime * 0.025;
    float minm = 10000.0;
    float mm = 10000.0;
    float hit_ids = 0.0;
    
    for (int i = 0; i < MAX_SPOTS; ++i)
    {
	    vec3 rp = orp;
	    vec3 _rp = rp;
        rp += SPOT_POS[i];
        rp *= SPOT_ROTATION[i];
        
        float m = sdCappedCylinder(rp, vec2(CONE_W, 1.0));

        float l = -LIGHT_BASE_W + length(rp) * 0.2;
        m -= l;
        float d = dot(rp, vec3(0.0, -1.0, 0.0));
        
        if( m < 0.0 && d >= 0.0)
        {
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
            hit_ids += float(i + 1);

        }
        minm = min(abs(m), minm);
    }
    
    if(hit_ids > 0.0)
    {
        return vec2(mm, hit_ids);
    }
    
    return vec2(minm, NOTHING);

}


const int MAX_STEPS = 250;
const float MIN_STEP = 0.0052;
const float FAR = 0.5;

const float LIGHT_POW = 2.5;
const float LIGHT_INTENS = 0.15;
const float FLOOR_Y = -0.25;

void colorize(in vec4 fgc, in vec3 pos, in vec4 spotcol, inout vec4 color)
{
    float flf = inversesqrt(length(pos));
    flf = pow(flf, LIGHT_POW) * LIGHT_INTENS;
    color += fgc * flf * spotcol;
    
}

bool trace(in vec3 ro, in vec3 rd, inout vec4 color)
{
    color = vec4(0.0);
    vec3 rp = ro;
    float h = 0.0;
    float sg = (sin(iGlobalTime) + 1.0) * 0.25;
    float sg2 = (sin(iGlobalTime * 0.5) + 1.0) * 0.25;
    
    vec4 spcol1 = SPOT_COL[0] + vec4(0.0, 0.0, sg, 0.0);
    vec4 spcol2 = SPOT_COL[1] + vec4(0.0, sg2, 0.0, 0.0);
    
    for (int i = 0; i < MAX_STEPS; ++i)
    {
        rp += rd * max(MIN_STEP, h * 0.5);
        vec2 hp = maplight(rp);
        h = hp.x;
        
        if(rp.z > FAR)
        {
            return false;
        }
        
        if(h < 0.0)
        {
            vec4 fgc = vec4(abs(h * 0.05));
            
            if(hp.y == SMOKE_CONE_1)
            {
                colorize(fgc, (-SPOT_POS[0] - rp), spcol1, color);
            }
            
            else if(hp.y == SMOKE_CONE_2)
            {
                colorize(fgc, (-SPOT_POS[1] - rp), spcol2 , color);
            }
			
            else if(hp.y == SMOKE_CONE_2 + SMOKE_CONE_1)
            {
                colorize(fgc, (-SPOT_POS[0] - rp), spcol1, color);
                colorize(fgc, (-SPOT_POS[1] - rp), spcol2, color);
            }    
            
            
            if(rp.y < FLOOR_Y && rp.y > FLOOR_Y - 0.0017)
            {
                vec4 spcol = spcol1;
                if(hp.y == SMOKE_CONE_2)
                {
                    spcol = spcol2;
                }else if(hp.y == SMOKE_CONE_2 + SMOKE_CONE_1)
                {
                    spcol += spcol2;                    
                }
                color += vec4(0.1, 0.1, 0.1, 0.0) * spcol;
                return true;
            }         
            
            if(rp.y < FLOOR_Y)
            {
                color = vec4(0.0);
                return true;
            }            
        }
    }

    
    return false;
}


void main(void)
{
    vec2 uv = gl_FragCoord.xy / iResolution.xy;
    float aspect = iResolution.x / iResolution.y;
    
    SPOT_POS[0] = spotpos;
    SPOT_POS[1] = vec3(spotpos.x * -1.0, spotpos.y, spotpos.z);
 
    SPOT_COL[0] = vec4(0.6, 0.7, 1.0, 0.0);
    SPOT_COL[1] = vec4(1.0, 0.5, 0.7, 0.0);

    float rotSpeed = 1.0;
    float xrot = -1.0 + cos(iGlobalTime * rotSpeed - 0.75) * 0.25;
    float yrot = 0.5 + sin(iGlobalTime * rotSpeed) * 0.35;

    SPOT_ROTATION[0] = rotx(xrot);
    SPOT_ROTATION[0] *= rotz(-yrot);
    
    SPOT_ROTATION[1] = rotx(xrot);
    SPOT_ROTATION[1] *= rotz(yrot);
    
    vec3 rd = (vec3(uv - vec2(0.5), 1.0));
    rd.y /= aspect;
    rd = normalize(rd);
    gl_FragColor = vec4(0.0);
    trace(vec3(0.0, 0.0, -1.1), rd, gl_FragColor);
}
