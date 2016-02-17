package com.malmstein.fenster.renderer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Created by skonb on 14/10/30.
 */
public class MoviePlayerTextureRenderer extends VideoTextureRenderer {

    public interface Listener {
        public void onFinishRecording(MoviePlayerTextureRenderer renderer);

        public void onGLInitialized(MoviePlayerTextureRenderer renderer);
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

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
                    "uniform samplerExternalOES b_texture;" +
                    "varying vec2 v_TexCoordinate;" +
                    "void main () {" +
                    "    vec4 color = texture2D(b_texture, v_TexCoordinate);" +
                    "    gl_FragColor = color;" +
                    "}";

    private static final String fragmentShaderCode2D =
            "precision mediump float;\n" +
                    "varying vec2 v_TexCoordinate;\n" +
                    "uniform sampler2D s_Texture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(s_Texture, v_TexCoordinate);\n" +
                    "}\n";


    private static float squareSize = 1.0f;
    private static float squareCoords[] = {
            -squareSize, squareSize, 0f,   // top left
            -squareSize, -squareSize, 0f,   // bottom left
            squareSize, -squareSize, 0f,   // bottom right
            squareSize, squareSize, 0f}; // top right

    private static short drawOrder[] = {0, 1, 2, 0, 2, 3};

    private Context ctx;

    // Texture to be shown in backgrund
    private FloatBuffer textureBuffer;
    private float textureCoords[] = {0.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f};
    private int[] textures = new int[1];

    private int vertexShaderHandle;
    private int fragmentShaderHandle;
    private int shaderProgram;
    private int shaderProgram2D;

    private FloatBuffer vertexBuffer;
    private ShortBuffer drawListBuffer;

    private SurfaceTexture canvasTexture;
    private float[] videoTextureTransform = new float[16];
    private float[] projectionMatrix = new float[16];
    private float[] modelViewMatrix = new float[16];
    private boolean frameAvailable = false;
    private Surface canvasSurface;

    private int videoWidth;
    private int videoHeight;
    private boolean adjustViewport = false;

    private int[] frameBuffers = new int[1];
    private int[] renderBuffers = new int[1];
    private int[] offScreenTextures = new int[1];
    private boolean frameBufferPrepared;

    protected boolean recording;

    protected Surface encoderInputSurface;
    protected EGLSurface encoderInputWindowSurface;

    protected String previousOutputPath;
    protected Listener mListener;


    public MoviePlayerTextureRenderer(Context context, SurfaceTexture texture, int width, int height, OnVideoTextureAvailableListener onVideoTextureAvailableListener) {
        super(context, texture, width, height,  onVideoTextureAvailableListener);
    }

    public void clearOverlay() {
        canvasSurface.unlockCanvasAndPost(canvasSurface.lockCanvas(new Rect()));
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
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int textureParamHandle = GLES20.glGetUniformLocation(shaderProgram, "b_texture");
        int textureCoordinateHandle = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinate");
        int positionHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition");
        int textureTransformHandle = GLES20.glGetUniformLocation(shaderProgram, "textureTransform");
        int projectionHandler = GLES20.glGetUniformLocation(shaderProgram, "projection");
        int modelViewHandler = GLES20.glGetUniformLocation(shaderProgram, "modelView");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 4 * 3, vertexBuffer);
        GLES20.glUniformMatrix4fv(projectionHandler, 1, false, projectionMatrix, 0);
        GLES20.glUniformMatrix4fv(modelViewHandler, 1, false, modelViewMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        GLES20.glUniform1i(textureParamHandle, 1);

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 4, GLES20.GL_FLOAT, false, 0, textureBuffer);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(textureTransformHandle, 1, false, identity, 0);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private void blitToScreen() {
//        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
//        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        // Draw texture
        GLES20.glUseProgram(shaderProgram2D);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        GLES20.glUniform1i(textureParamHandle, 1);

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 4, GLES20.GL_FLOAT, false, 0, textureBuffer);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(textureTranformHandle, 1, false, videoTextureTransform, 0);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle);
    }

    @Override
    protected boolean draw() {
        boolean res = super.draw();
//        if (true)return super.draw();
        if (frameAvailable) {
            canvasTexture.updateTexImage();
            canvasTexture.getTransformMatrix(videoTextureTransform);
//            Matrix.translateM(videoTextureTransform, 0, 0, 0, 0.5f);
            frameAvailable = false;
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("Texture generate");

        canvasTexture = new SurfaceTexture(textures[0]);
        canvasTexture.setOnFrameAvailableListener(this);
        canvasSurface = new Surface(canvasTexture);

    }

    @Override
    protected void initGLComponents() {
        super.initGLComponents();
        setupVertexBuffer();
        setupTexture(ctx);
        loadShaders();
        clearOverlay();
        Matrix.orthoM(projectionMatrix, 0, -1, 1, -1, 1, -1, 1);
        Matrix.setIdentityM(modelViewMatrix, 0);
        Matrix.translateM(modelViewMatrix, 0, 0, 0, -.5f);
        if (mListener != null) {
            mListener.onGLInitialized(this);
        }
    }

    @Override
    protected void deinitGLComponents() {
        super.deinitGLComponents();
        GLES20.glDeleteTextures(1, textures, 0);
        GLES20.glDeleteProgram(shaderProgram);
        canvasTexture.release();
        canvasTexture.setOnFrameAvailableListener(null);
    }

    @TargetApi(15)
    @Override
    public void setVideoSize(int width, int height) {
        super.setVideoSize(width, height);
        this.videoWidth = width;
        this.videoHeight = height;
        canvasTexture.setDefaultBufferSize(width, height);
        adjustViewport = true;
    }

    @Override
    public void setSize(int surfaceWidth, int surfaceHeight, int videoWidth, int videoHeight) {
        super.setSize(surfaceWidth, surfaceHeight, videoWidth, videoHeight);
        this.width = surfaceWidth;
        this.height = surfaceHeight;
        setVideoSize(videoWidth, videoHeight);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != canvasTexture) {
            super.onFrameAvailable(surfaceTexture);
        } else {
            synchronized (this) {
                frameAvailable = true;
            }
        }
    }


    public Surface getCanvasSurface() {
        return canvasSurface;
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

    public void startRecordingWithOrientationHint(int degrees) {
    }

    enum ARTaget {
        _9_16,
        _3_4,
        _3_5,
    }

    public void generateRecordingVideoSizeForSourceVideoSize(int sourceWidth, int sourceHeight) {
        float sourceAR = (float) sourceWidth / sourceHeight;
        ARTaget arTaget = ARTaget._3_4;
        boolean isPortrait = sourceAR < 1;
        final int _9_16_base16Landscape = 320;
        final int _9_16_base9Landscape = 180;
        final int _3_4_base4Landscape = 320;
        final int _3_4_base3Landscape = 240;
        final int _3_5_base5Landscape = 320;
        final int _3_5_base3Landscape = 192;
        final int _9_16_base16Portrait = 560;
        final int _9_16_base9Portrait = 320;
        final int _3_4_base4Portrait = 426;
        final int _3_4_base3Portrait = 320;
        final int _3_5_base5Portrait = 532;
        final int _3_5_base3Portrait = 320;


        final float threshold1 = 1.44f;//= 4.0 / 3.0 <--- 1.444444.. ----> 5.0 / 3.0
        final float threshold2 = 1.72f; //= 5.0 /3.0 <--- 1.72...    ----> 16.0 / 9.0
        final float centerThreshold = 1.55f; //= 4.0 / 3.0 <--- 1.555... ----> 16.0 / 9.0
        double centerThresholdDiff = isPortrait ? sourceAR - 1.0 / centerThreshold : sourceAR -
                centerThreshold;
        double threshold1diff = isPortrait ? sourceAR - 1.0 / threshold1 : sourceAR - threshold1;
        double threshold2diff = isPortrait ? sourceAR - 1.0 / threshold2 : sourceAR - threshold2;
        if (isPortrait) {
            if (centerThresholdDiff > 0) {
                if (threshold1diff > 0) {
                    arTaget = ARTaget._3_4;
                } else {
                    arTaget = ARTaget._3_5;
                }
            } else {
                if (threshold2diff > 0) {
                    arTaget = ARTaget._3_5;
                } else {
                    arTaget = ARTaget._9_16;
                }
            }
        } else {
            if (centerThresholdDiff < 0) {
                if (threshold1diff < 0) {
                    arTaget = ARTaget._3_4;
                } else {
                    arTaget = ARTaget._3_5;
                }
            } else {
                if (threshold2diff < 0) {
                    arTaget = ARTaget._3_5;
                } else {
                    arTaget = ARTaget._9_16;
                }
            }
        }
        if (arTaget == ARTaget._9_16) {
            // 9 : 16
            if (isPortrait) {
                int i = (int) Math.ceil((float) sourceWidth / _9_16_base9Portrait);
                int j = (int) Math.ceil((float) sourceHeight / _9_16_base16Portrait);
                int k = Math.max(i, j);
                recordingVideoWidth = _9_16_base9Portrait * k;
                recordingVideoHeight = _9_16_base16Portrait * k;
            }
            // 16 : 9
            else {
                int i = (int) Math.ceil((float) sourceWidth / _9_16_base16Landscape);
                int j = (int) Math.ceil((float) sourceHeight / _9_16_base9Landscape);
                int k = Math.max(i, j);
                recordingVideoWidth = _9_16_base16Landscape * k;
                recordingVideoHeight = _9_16_base9Landscape * k;
            }
        } else if (arTaget == ARTaget._3_4) {
            // 3 : 4
            if (isPortrait) {
                int i = (int) Math.ceil((float) sourceWidth / _3_4_base3Portrait);
                int j = (int) Math.ceil((float) sourceHeight / _3_4_base4Portrait);
                int k = Math.max(i, j);
                recordingVideoWidth = _3_4_base3Portrait * k;
                recordingVideoHeight = _3_4_base4Portrait * k;
            }
            // 4 : 3
            else {
                int i = (int) Math.ceil((float) sourceWidth / _3_4_base4Landscape);
                int j = (int) Math.ceil((float) sourceHeight / _3_4_base3Landscape);
                int k = Math.max(i, j);
                recordingVideoWidth = _3_4_base4Landscape * k;
                recordingVideoHeight = _3_4_base3Landscape * k;
            }
        } else if (arTaget == ARTaget._3_5) {
            // 3 : 5
            if (isPortrait) {
                int i = (int) Math.ceil((float) sourceWidth / _3_5_base3Portrait);
                int j = (int) Math.ceil((float) sourceHeight / _3_5_base5Portrait);
                int k = Math.max(i, j);
                recordingVideoWidth = _3_5_base3Portrait * k;
                recordingVideoHeight = _3_5_base5Portrait * k;
            }
            // 5 : 3
            else {
                int i = (int) Math.ceil((float) sourceWidth / _3_5_base5Landscape);
                int j = (int) Math.ceil((float) sourceHeight / _3_5_base3Landscape);
                int k = Math.max(i, j);
                recordingVideoWidth = _3_5_base5Landscape * k;
                recordingVideoHeight = _3_5_base3Landscape * k;
            }
        }
        Log.i("MoviePlayerTextureRende", "sourceVideoSize(" + sourceWidth + ", " +
                "" + sourceHeight + ")");
        if (isPortrait) {
//            switch (arTaget) {
//                case _3_4:
//                    if (recordingVideoWidth > 960) {
//                        recordingVideoWidth = 960;
//                        recordingVideoHeight = 1280;
//                    }
//                    break;
//                case _3_5:
//                    if (recordingVideoWidth> 768) {
//                        recordingVideoWidth = 768;
//                        recordingVideoHeight = 1280;
//                    }
//                    break;
//                case _9_16:
//                    if (recordingVideoWidth > 720) {
//                        recordingVideoWidth = 720;
//                        recordingVideoHeight = 1280;
//                    }
//                    break;
//            }

        } else {
            if (recordingVideoWidth > 1280) {
                recordingVideoWidth = 1280;
                switch (arTaget) {
                    case _3_4:
                        recordingVideoHeight = 960;
                        break;
                    case _3_5:
                        recordingVideoHeight = 768;
                        break;
                    case _9_16:
                        recordingVideoHeight = 720;
                        break;
                }
            }
        }
        Log.i("MoviePlayerTextureRende", "recordingVideoSize(" + recordingVideoWidth + ", " +
                "" + recordingVideoHeight + ")");

    }

    @Override
    public void run() {
        initGL();
        initGLComponents();
        Log.d(LOG_TAG, "OpenGL init OK.");

        while (running) {
            long loopStart = System.currentTimeMillis();
            pingFps();

            if (draw()) {
                egl.eglSwapBuffers(eglDisplay, eglSurface);
            }
            if (recording && encoderInputWindowSurface != null) {
                blitToEncoderInput = true;
                egl.eglMakeCurrent(eglDisplay, encoderInputWindowSurface, encoderInputWindowSurface,
                        eglContext);
                draw();
                egl.eglSwapBuffers(eglDisplay, encoderInputWindowSurface);
                egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                blitToEncoderInput = false;
            }

            long waitDelta = 16 - (System.currentTimeMillis() - loopStart);    // Targeting 60
            // fps, no need for faster
            if (waitDelta > 0) {
                try {
                    Thread.sleep(waitDelta);
                } catch (InterruptedException e) {
                    continue;
                }
            }
        }

        deinitGLComponents();
        deinitGL();
    }


    @Override
    protected int[] getConfig() {
        return new int[]{
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL_RECORDABLE_ANDROID, 1,
                EGL10.EGL_NONE
        };
    }

    public String getPreviousOutputPath() {
        return previousOutputPath;
    }

    @Override
    protected void deinitGL() {
        if (encoderInputWindowSurface != null) {
            egl.eglDestroySurface(eglDisplay, encoderInputWindowSurface);
        }
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
        }
        super.deinitGL();
    }

    public Listener getListener() {
        return mListener;
    }

    public void setListener(Listener mListener) {
        this.mListener = mListener;
    }

}
