#extension GL_OES_EGL_image_external : require
precision mediump float;

// External texture containing video decoder output.
uniform samplerExternalOES tex_sampler_0;
varying vec2 v_texcoord;

void main() {
    vec4 gl_FragColor = texture2D(tex_sampler_0, v_texcoord);
}