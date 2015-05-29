// switch on high precision floats
#ifdef GL_ES
precision highp float;
#endif

void main()
{
  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mvPosition;
}

