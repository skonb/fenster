package com.malmstein.fenster.renderer;


import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class VideoTextureRenderer extends TextureSurfaceRenderer implements SurfaceTexture.OnFrameAvailableListener {
    protected static final String TAG = "VideoTextureRenderer";
    private static final String vertexShaderCode =
            "attribute vec4 vPosition;" +
                    "attribute vec4 vTexCoordinate;" +
                    "uniform mat4 modelView;" +
                    "uniform mat4 projection;" +
                    "uniform mat4 textureTransform;" +
                    "varying vec2 v_TexCoordinate;" +
                    "void main() {" +
                    "   v_TexCoordinate = (textureTransform * vTexCoordinate).xy;" +
//                    "   mat4 mvp = Projection * ModelView;" +
//                    "   vPosition.z = -0.5;" +
                    "   gl_Position = projection * modelView * vPosition ;" +
                    "}";

    private static final String fragmentShaderCode =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;" +
                    "uniform samplerExternalOES a_texture;" +
                    "varying vec2 v_TexCoordinate;" +
                    "void main () {" +
                    "    vec4 color = texture2D(a_texture, v_TexCoordinate);" +
                    "    gl_FragColor = color;" +
                    "}";

    private static final String fragmentShaderCode2D =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoordinate;\n" +
                    "uniform sampler2D s_Texture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(s_Texture, v_TexCoordinate);\n" +
                    "}\n";

    public interface OnRenderFrameListener {
        void onPrepareDraw();

        void onPreRender();
    }

    private OnRenderFrameListener onRenderFrameListener;

    public interface OnVideoTextureAvailableListener {
        void onVideoTextureAvailable(VideoTextureRenderer videoTextureRenderer, SurfaceTexture surfaceTexture);
    }

    protected OnVideoTextureAvailableListener onVideoTextureAvailableListener;

    private static float squareSize = 1.0f;
    private static float squareCoords[] = {
            -squareSize, -squareSize, 0f,   // top left
            squareSize, -squareSize, 0f,   // bottom left
            -squareSize, squareSize, 0f,   // bottom right
            squareSize, squareSize, 0f}; // top right

    private static short drawOrder[] = {0, 1, 2, 0, 2, 3};

    private Context ctx;

    // Texture to be shown in backgrund
    private FloatBuffer textureBuffer;
    private float textureCoords[] = {0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f};
    private int[] textures = new int[1];

    private int vertexShaderHandle;
    private int fragmentShaderHandle;
    private int shaderProgram;
    private int shaderProgram2D;
    private FloatBuffer vertexBuffer;
    private ShortBuffer drawListBuffer;

    private SurfaceTexture videoTexture;
    private float[] videoTextureTransform;
    private boolean frameAvailable = false;
    private float[] projectionMatrix = new float[16];
    private float[] modelViewMatrix = new float[16];
    private int videoWidth;
    private int videoHeight;
    private boolean adjustViewport = false;
    protected boolean blitToEncoderInput;

    private int[] frameBuffers = new int[1];
    private int[] renderBuffers = new int[1];
    private int[] recordingFramebuffers = new int[1];
    private int[] recordingRenderbuffers = new int[1];
    private int[] offScreenTextures = new int[1];
    protected int[] recordingTextures = new int[1];
    private boolean frameBufferPrepared;
    private boolean recordingFramebufferPrepared;

    protected int recordingVideoWidth;
    protected int recordingVideoHeight;

    int adjustedOffsetXForRecordingInput, adjustedOffsetYForRecordingInput, adjustedViewportWidthForRecordingInput,
            adjustedViewportHeightForRecordingInput;

    public VideoTextureRenderer(Context context, SurfaceTexture texture, int width, int height, OnVideoTextureAvailableListener onVideoTextureAvailableListener) {
        super(texture, width, height);
        this.ctx = context;
        this.onVideoTextureAvailableListener = onVideoTextureAvailableListener;
        videoTextureTransform = new float[16];
    }

    protected int getShaderProgram() {
        return shaderProgram;
    }

    private void loadShaders() {
        vertexShaderHandle = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShaderHandle, vertexShaderCode);
        GLES20.glCompileShader(vertexShaderHandle);
        checkGlError("Vertex shader compile");

        fragmentShaderHandle = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShaderHandle, fragmentShaderCode);
        GLES20.glCompileShader(fragmentShaderHandle);
        checkGlError("Pixel shader compile");

        shaderProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(shaderProgram, vertexShaderHandle);
        GLES20.glAttachShader(shaderProgram, fragmentShaderHandle);
        GLES20.glLinkProgram(shaderProgram);
        checkGlError("Shader program compile");

        int[] status = new int[1];
        GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(shaderProgram);
            Log.e("SurfaceTest", "Error while linking program:\n" + error);
        }

        int fragmentShaderHandle2D = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShaderHandle2D, fragmentShaderCode2D);
        GLES20.glCompileShader(fragmentShaderHandle2D);

        shaderProgram2D = GLES20.glCreateProgram();
        GLES20.glAttachShader(shaderProgram2D, vertexShaderHandle);
        GLES20.glAttachShader(shaderProgram2D, fragmentShaderHandle2D);
        GLES20.glLinkProgram(shaderProgram2D);

    }


    private void setupVertexBuffer() {
        // Draw list buffer
        ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder.length * 2);
        dlb.order(ByteOrder.nativeOrder());
        drawListBuffer = dlb.asShortBuffer();
        drawListBuffer.put(drawOrder);
        drawListBuffer.position(0);

        // Initialize the texture holder
        ByteBuffer bb = ByteBuffer.allocateDirect(squareCoords.length * 4);
        bb.order(ByteOrder.nativeOrder());

        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(squareCoords);
        vertexBuffer.position(0);
    }


    private void setupTexture(Context context) {
        ByteBuffer texturebb = ByteBuffer.allocateDirect(textureCoords.length * 4);
        texturebb.order(ByteOrder.nativeOrder());

        textureBuffer = texturebb.asFloatBuffer();
        textureBuffer.put(textureCoords);
        textureBuffer.position(0);

        // Generate the actual texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("Texture generate");

        videoTexture = new SurfaceTexture(textures[0]);
        videoTexture.setOnFrameAvailableListener(this);
        if (onVideoTextureAvailableListener != null) {
            onVideoTextureAvailableListener.onVideoTextureAvailable(this, videoTexture);
        }
    }

    private void drawToFrameBuffer() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        // Draw texture
        GLES20.glUseProgram(shaderProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int textureParamHandle = GLES20.glGetUniformLocation(shaderProgram, "a_texture");
        int textureCoordinateHandle = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinate");
        int positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition");
        int textureTranformHandle = GLES20.glGetUniformLocation(shaderProgram, "textureTransform");
        int projectionHandler = GLES20.glGetUniformLocation(shaderProgram, "projection");
        int modelViewHandler = GLES20.glGetUniformLocation(shaderProgram, "modelView");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 4 * 3, vertexBuffer);
        GLES20.glUniformMatrix4fv(projectionHandler, 1, false, projectionMatrix, 0);
        GLES20.glUniformMatrix4fv(modelViewHandler, 1, false, modelViewMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        GLES20.glUniform1i(textureParamHandle, 0);

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 4, GLES20.GL_FLOAT, false, 0, textureBuffer);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(textureTranformHandle, 1, false, identity, 0);

        if (onRenderFrameListener != null) {
            onRenderFrameListener.onPreRender();
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
//        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private void blitToScreen() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        // Draw texture
        GLES20.glUseProgram(shaderProgram2D);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int textureParamHandle = GLES20.glGetUniformLocation(shaderProgram2D, "s_Texture");
        int textureCoordinateHandle = GLES20.glGetAttribLocation(shaderProgram2D, "vTexCoordinate");
        int positionHandle = GLES20.glGetAttribLocation(shaderProgram2D, "vPosition");
        int textureTranformHandle = GLES20.glGetUniformLocation(shaderProgram2D, "textureTransform");
        int projectionHandler = GLES20.glGetUniformLocation(shaderProgram2D, "projection");
        int modelViewHandler = GLES20.glGetUniformLocation(shaderProgram2D, "modelView");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 4 * 3, vertexBuffer);
        GLES20.glUniformMatrix4fv(projectionHandler, 1, false, projectionMatrix, 0);
        GLES20.glUniformMatrix4fv(modelViewHandler, 1, false, modelViewMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        GLES20.glUniform1i(textureParamHandle, 0);

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 4, GLES20.GL_FLOAT, false, 0, textureBuffer);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(textureTranformHandle, 1, false, videoTextureTransform, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
//        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle);
    }

    void prepareDraw() {
        if (onRenderFrameListener != null) {
            onRenderFrameListener.onPrepareDraw();
        }
    }

    @Override
    protected boolean draw() {
        synchronized (this) {
            if (frameAvailable) {
                videoTexture.updateTexImage();
                videoTexture.getTransformMatrix(videoTextureTransform);
                frameAvailable = false;
            } /*else {
                return false;
            }*/

        }
        if (!frameBufferPrepared) {
            prepareFramebuffer(videoWidth, videoHeight);
        }
        if (!frameBufferPrepared) {
            return false;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0]);
        GLES20.glViewport(0, 0, videoWidth, videoHeight);
        drawToFrameBuffer();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (blitToEncoderInput) {
            adjustViewportForRecordingInput();
        } else {
            GLES20.glViewport(0, 0, width, height);
        }
        blitToScreen();

        return true;
    }

    protected void resetAdjustedViewportRect() {
        float videoAR = (float) videoWidth / videoHeight;
        float screenAR = (float) recordingVideoWidth / recordingVideoHeight;
        if (videoAR > screenAR) {
            adjustedOffsetXForRecordingInput = 0;
            adjustedViewportWidthForRecordingInput = recordingVideoWidth;
            adjustedViewportHeightForRecordingInput = (int) (recordingVideoWidth / videoAR);
            adjustedOffsetYForRecordingInput = (recordingVideoHeight - adjustedViewportHeightForRecordingInput) / 2;
        } else {
            adjustedOffsetYForRecordingInput = 0;
            adjustedViewportHeightForRecordingInput = recordingVideoHeight;
            adjustedViewportWidthForRecordingInput = (int) (recordingVideoHeight * videoAR);
            adjustedOffsetXForRecordingInput = (recordingVideoWidth - adjustedViewportWidthForRecordingInput) / 2;
        }
    }

    protected void adjustViewportForRecordingInput() {
        GLES20.glViewport(adjustedOffsetXForRecordingInput, adjustedOffsetYForRecordingInput, adjustedViewportWidthForRecordingInput, adjustedViewportHeightForRecordingInput);
    }


    @Override
    protected void initGLComponents() {
        setupVertexBuffer();
        setupTexture(ctx);
        loadShaders();
        Matrix.orthoM(projectionMatrix, 0, -1, 1, -1, 1, -1, 1);
        Matrix.setIdentityM(modelViewMatrix, 0);
    }

    @Override
    protected void deinitGLComponents() {
        GLES20.glDeleteTextures(1, textures, 0);
        GLES20.glDeleteProgram(shaderProgram);
        GLES20.glDeleteProgram(shaderProgram2D);
        releaseFramebuffer();
        videoTexture.release();
        videoTexture.setOnFrameAvailableListener(null);
    }

    public void setVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
        adjustViewport = true;


    }

    public void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e("SurfaceTest", op + ": glError " + GLUtils.getEGLErrorString(error));
        }
    }

    public SurfaceTexture getVideoTexture() {
        return videoTexture;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            frameAvailable = true;
        }
    }

    public void setSize(int surfaceWidth, int surfaceHeight, int videoWidth, int videoHeight) {
        this.width = surfaceWidth;
        this.height = surfaceHeight;
        setVideoSize(videoWidth, videoHeight);
    }

    public void releaseFramebuffer() {
        if (offScreenTextures[0] > 0) {
            GLES20.glDeleteTextures(1, offScreenTextures, 0);
            offScreenTextures[0] = -1;
        }
        if (renderBuffers[0] > 0) {
            GLES20.glDeleteRenderbuffers(1, renderBuffers, 0);
            renderBuffers[0] = -1;

        }
        if (frameBuffers[0] > 0) {
            GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
            frameBuffers[0] = -1;
        }
        frameBufferPrepared = false;
    }

    private void prepareFramebuffer(int width, int height) {
        if (width == 0 || height == 0) return;
        // Create a texture object and bind it.  This will be the color buffer.
        GLES20.glGenTextures(1, offScreenTextures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[0]);
        checkGlError("genTexture");
        // Create texture storage.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        checkGlError("texImage");

        // Set parameters.  We're probably using non-power-of-two dimensions, so
        // some values may not be available for use.
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        // Create framebuffer object and bind it.
        GLES20.glGenFramebuffers(1, frameBuffers, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0]);
        checkGlError("genFramebuffer");

        // Create a depth buffer and bind it.
        GLES20.glGenRenderbuffers(1, renderBuffers, 0);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffers[0]);
        checkGlError("genRenderbuffer");

        // Allocate storage for the depth buffer.
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                width, height);
        checkGlError("renderbufferStorage");

        // Attach the depth buffer and the texture (color buffer) to the framebuffer object.
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, renderBuffers[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, offScreenTextures[0], 0);

        // See if GLES is happy with all this.
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete, status=" + status);
        }

        // Switch back to the default framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        frameBufferPrepared = true;
    }

    public OnVideoTextureAvailableListener getOnVideoTextureAvailableListener() {
        return onVideoTextureAvailableListener;
    }

    public void setOnVideoTextureAvailableListener(OnVideoTextureAvailableListener onVideoTextureAvailableListener) {
        this.onVideoTextureAvailableListener = onVideoTextureAvailableListener;
    }

    public OnRenderFrameListener getOnRenderFrameListener() {
        return onRenderFrameListener;
    }

    public void setOnRenderFrameListener(OnRenderFrameListener onRenderFrameListener) {
        this.onRenderFrameListener = onRenderFrameListener;
    }
}
