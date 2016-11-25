package com.malmstein.fenster.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.malmstein.fenster.R;
import com.malmstein.fenster.controller.FensterPlayerController;
import com.malmstein.fenster.play.FensterVideoStateListener;

import java.io.IOException;
import java.util.Map;

/**
 * Created by skonb on 2016/09/12.
 */

public class FensterDualVideoView extends TextureView {
    protected static final int N = 2;

    public interface Renderer {
        void onPause();

        boolean isStarted();

        void setVideoSize(int index, int width, int height);

        void setOutputSize(int width, int height);

        void startRenderingToOutput(SurfaceTexture outputSurfaceTexture, Runnable callback);

        SurfaceTexture[] getInputTextures();

    }

    protected MediaPlayer[] mediaPlayers = new MediaPlayer[2];

    public FensterDualVideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FensterDualVideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        for (int i = 0; i < N; ++i) {
            videoSizeCalculators[i] = new VideoSizeCalculator();
        }
        initVideoView();
    }


    public static final String TAG = "DualTextureVideoView";
    public static final int VIDEO_BEGINNING = 0;

    public enum ScaleType {
        SCALE_TO_FIT, CROP
    }

    // all possible internal states
    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;
    private static final int MILLIS_IN_SEC = 1000;

    // collaborators / delegates / composites .. discuss
    private final VideoSizeCalculator[] videoSizeCalculators = new VideoSizeCalculator[N];
    // mCurrentState is a VideoView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the VideoView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private int[] currentStates = new int[]{
            STATE_IDLE, STATE_IDLE
    };
    private int[] targetStates = new int[]{
            STATE_IDLE, STATE_IDLE
    };

    private FensterVideoView.ScaleType[] scaleTypes = new FensterVideoView.ScaleType[N];

    private Uri[] uris = new Uri[N];

    private AssetFileDescriptor[] assetFileDescriptors = new AssetFileDescriptor[N];
    private Map<String, String>[] headersList = new Map[N];
    private SurfaceTexture mSurfaceTexture;
    private FensterPlayerController fensterPlayerController;
    private MediaPlayer.OnCompletionListener[] mOnCompletionListeners = new MediaPlayer.OnCompletionListener[N];
    private MediaPlayer.OnPreparedListener[] mOnPreparedListeners = new MediaPlayer.OnPreparedListener[N];
    private MediaPlayer.OnErrorListener[] mOnErrorListeners = new MediaPlayer.OnErrorListener[N];
    private MediaPlayer.OnInfoListener[] mOnInfoListeners = new MediaPlayer.OnInfoListener[N];
    private int[] audioSessions = new int[N];
    private int[] seekWhenPrepareds = new int[N];  // recording the seek position while preparing
    private int[] currentBufferPercentages = new int[N];
    private boolean[] canPause = new boolean[N];
    private boolean[] canSeekBack = new boolean[N];
    private boolean[] canSeekForward = new boolean[N];
    private FensterVideoStateListener onPlayStateListener;

    private AlertDialog errorDialog;

    private Renderer mRenderer;
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    private boolean mLooping;

    @Override
    public void onInitializeAccessibilityEvent(final AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(FensterVideoView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(final AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(FensterVideoView.class.getName());
    }

    public int resolveAdjustedSize(final int desiredSize, final int measureSpec) {
        return getDefaultSize(desiredSize, measureSpec);
    }

    private void initVideoView() {
        for (int i = 0; i < N; ++i) {
            videoSizeCalculators[i].setVideoSize(0, 0);
        }

        setSurfaceTextureListener(mSTListener);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        currentStates = new int[]{STATE_IDLE, STATE_IDLE};
        targetStates = new int[]{STATE_IDLE, STATE_IDLE};
        for (int i = 0; i < N; ++i) {
            setOnInfoListener(i, onInfoToPlayStateListener);
        }
    }

    private void disableFileDescriptor(int index) {
        assetFileDescriptors[index] = null;
    }

    public void setVideo(int index, final String path) {
        disableFileDescriptor(index);
        setVideo(index, Uri.parse(path), VIDEO_BEGINNING);
    }

    public void setVideo(int index, final String url, final int seekInSeconds) {
        disableFileDescriptor(index);
        setVideo(index, Uri.parse(url), seekInSeconds);
    }

    public void setVideo(int index, final Uri uri, final int seekInSeconds) {
        disableFileDescriptor(index);
        setVideoURI(index, uri, null, seekInSeconds);
    }

    public void setVideo(int index, final AssetFileDescriptor assetFileDescriptor) {
        assetFileDescriptors[index] = assetFileDescriptor;
        setVideoURI(index, null, null, VIDEO_BEGINNING);
    }

    public void setVideo(int index, final AssetFileDescriptor assetFileDescriptor, final int seekInSeconds) {
        assetFileDescriptors[index] = assetFileDescriptor;
        setVideoURI(index, null, null, seekInSeconds);
    }

    /**
     * Set the scale type of the video, needs to be set after setVideo() has been called
     *
     * @param scaleType
     */
    private void setScaleType(FensterVideoView.ScaleType scaleType) {
//        switch (scaleType) {
//            case SCALE_TO_FIT:
//                mediaPlayers[index].setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
//                break;
//            case CROP:
//                mediaPlayers[index].setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
//                break;
//        }
    }

    private void setVideoURI(int index, final Uri uri, final Map<String, String> headers, final int seekInSeconds) {
        Log.d(TAG, "start playing: " + uri);
        uris[index] = uri;
        headersList[index] = headers;
        seekWhenPrepareds[index] = seekInSeconds * 1000;
        openVideo(index);
        requestLayout();
        invalidate();
    }

    public void stopPlayback(int index) {
        if (mediaPlayers[index] != null) {
            try {
                mediaPlayers[index].stop();
                mediaPlayers[index].release();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } finally {

            }
            mediaPlayers[index] = null;
            if (mediaPlayers[0] == null && mediaPlayers[1] == null) {
                setKeepScreenOn(false);
            }
        }
        currentStates[index] = STATE_IDLE;
        targetStates[index] = STATE_IDLE;
    }

    private void openVideoImpl(int index) {
        try {
            mediaPlayers[index] = new MediaPlayer();

            if (audioSessions[index] != 0) {
                mediaPlayers[index].setAudioSessionId(audioSessions[index]);
            } else {
                audioSessions[index] = mediaPlayers[index].getAudioSessionId();
            }
            mediaPlayers[index].setOnPreparedListener(mPreparedListener);
            mediaPlayers[index].setOnVideoSizeChangedListener(mSizeChangedListener);
            mediaPlayers[index].setOnCompletionListener(mCompletionListener);
            mediaPlayers[index].setOnErrorListener(mErrorListener);
            mediaPlayers[index].setOnInfoListener(mInfoListener);
            mediaPlayers[index].setOnBufferingUpdateListener(mBufferingUpdateListener);
            currentBufferPercentages[index] = 0;

            setDataSource(index);
            setScaleType(scaleTypes[index]);

            mediaPlayers[index].setSurface(new Surface(mRenderer == null ? mSurfaceTexture : mRenderer.getInputTextures()[index]));
            mediaPlayers[index].setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayers[index].setScreenOnWhilePlaying(true);
            mediaPlayers[index].prepareAsync();
            mediaPlayers[index].setLooping(mLooping);

            // we don't set the target state here either, but preserve the target state that was there before.
            currentStates[index] = STATE_PREPARING;
        } catch (final IOException | IllegalArgumentException ex) {
            notifyUnableToOpenContent(index, ex);
        }
    }

    private void openVideo(final int index) {
        if (uris[index] == null) {
            return;
        }
        if (notReadyForPlaybackJustYetWillTryAgainLater()) {
            return;
        }
        tellTheMusicPlaybackServiceToPause();

        // we shouldn't clear the target state, because somebody might have called start() previously
        release(index, false);
        if (mRenderer != null) {
            mRenderer.setOutputSize(mSurfaceWidth, mSurfaceHeight);
            mRenderer.startRenderingToOutput(mSurfaceTexture, new Runnable() {
                @Override
                public void run() {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            openVideoImpl(index);
                        }
                    });
                }
            });
        } else {
            openVideoImpl(index);
        }
    }

    private void setDataSource(int index) throws IOException {
        if (assetFileDescriptors[index] != null) {
            mediaPlayers[index].setDataSource(
                    assetFileDescriptors[index].getFileDescriptor(),
                    assetFileDescriptors[index].getStartOffset(),
                    assetFileDescriptors[index].getLength()
            );
        } else {
            mediaPlayers[index].setDataSource(getContext(), uris[index], headersList[index]);
        }
    }

    private boolean notReadyForPlaybackJustYetWillTryAgainLater() {
        return mSurfaceTexture == null;
    }

    private void tellTheMusicPlaybackServiceToPause() {
        // these constants need to be published somewhere in the framework.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        getContext().sendBroadcast(i);
    }

    private void notifyUnableToOpenContent(int index, final Exception ex) {
        Log.w("Unable to open content:" + uris[index], ex);
        currentStates[index] = STATE_ERROR;
        targetStates[index] = STATE_ERROR;
        mErrorListener.onError(mediaPlayers[index], MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
    }

    private MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener = new MediaPlayer.OnVideoSizeChangedListener() {
        @Override
        public void onVideoSizeChanged(final MediaPlayer mp, final int width, final int height) {
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                videoSizeCalculators[index].setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                if (mRenderer != null) {
                    mRenderer.setVideoSize(index, width, height);
                }
                if (videoSizeCalculators[index].hasASizeYet()) {
                    requestLayout();
                }
            }
        }
    };

    private MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(final MediaPlayer mp) {
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                currentStates[index] = STATE_PREPARED;

                canPause[index] = true;
                canSeekBack[index] = true;
                canSeekForward[index] = true;

                if (mOnPreparedListeners[index] != null) {
                    mOnPreparedListeners[index].onPrepared(mediaPlayers[index]);
                }
                if (fensterPlayerController != null) {
                    fensterPlayerController.setEnabled(true);
                }
                videoSizeCalculators[index].setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                if (mRenderer != null) {
                    mRenderer.setVideoSize(index, mp.getVideoWidth(), mp.getVideoHeight());
                }

                int seekToPosition = seekWhenPrepareds[index];  // mSeekWhenPrepared may be changed after seekTo() call
                if (seekToPosition != 0) {
                    seekTo(index, seekToPosition);
                }

                if (targetStates[index] == STATE_PLAYING) {
                    start(index);
                    showMediaController();
                } else if (pausedAt(index, seekToPosition)) {
                    showStickyMediaController();
                }
            }
        }
    };

    private boolean pausedAt(int index, final int seekToPosition) {
        return !isPlaying(index) && (seekToPosition != 0 || getCurrentPosition(index) > 0);
    }

    private void showStickyMediaController() {
        if (fensterPlayerController != null) {
            fensterPlayerController.show(0);
        }
    }

    private MediaPlayer.OnCompletionListener mCompletionListener = new MediaPlayer.OnCompletionListener() {

        @Override
        public void onCompletion(final MediaPlayer mp) {
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                setKeepScreenOn(false);
                currentStates[index] = STATE_PLAYBACK_COMPLETED;
                targetStates[index] = STATE_PLAYBACK_COMPLETED;
                hideMediaController();
                if (mOnCompletionListeners[index] != null) {
                    mOnCompletionListeners[index].onCompletion(mediaPlayers[index]);
                }
            }
        }
    };

    private MediaPlayer.OnInfoListener mInfoListener = new MediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(final MediaPlayer mp, final int arg1, final int arg2) {
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                if (mOnInfoListeners[index] != null) {
                    mOnInfoListeners[index].onInfo(mp, arg1, arg2);
                }
            }
            return true;
        }
    };

    private MediaPlayer.OnErrorListener mErrorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(final MediaPlayer mp, final int frameworkError, final int implError) {
            Log.d(TAG, "Error: " + frameworkError + "," + implError);
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                if (currentStates[index] == STATE_ERROR) {
                    return true;
                }
                currentStates[index] = STATE_ERROR;
                targetStates[index] = STATE_ERROR;
                hideMediaController();

                if (allowPlayStateToHandle(index, frameworkError)) {
                    return true;
                }

                if (allowErrorListenerToHandle(index, frameworkError, implError)) {
                    return true;
                }
            }

            handleError(frameworkError);

            return true;
        }
    };

    private void hideMediaController() {
        if (fensterPlayerController != null) {
            fensterPlayerController.hide();
        }
    }

    private void showMediaController() {
        if (fensterPlayerController != null) {
            fensterPlayerController.show();
        }
    }

    private boolean allowPlayStateToHandle(int index, final int frameworkError) {
        if (frameworkError == MediaPlayer.MEDIA_ERROR_UNKNOWN || frameworkError == MediaPlayer.MEDIA_ERROR_IO) {
            Log.e(TAG, "TextureVideoView error. File or network related operation errors.");
            if (hasPlayStateListener()) {
                return onPlayStateListener.onStopWithExternalError(mediaPlayers[index].getCurrentPosition() / MILLIS_IN_SEC);
            }
        }
        return false;
    }

    private boolean allowErrorListenerToHandle(int index, final int frameworkError, final int implError) {

        if (mOnErrorListeners[index] != null) {
            return mOnErrorListeners[index].onError(mediaPlayers[index], frameworkError, implError);
        }

        return false;
    }

    private void handleError(final int frameworkError) {
        if (getWindowToken() != null) {
            if (errorDialog != null && errorDialog.isShowing()) {
                Log.d(TAG, "Dismissing last error dialog for a new one");
                errorDialog.dismiss();
            }
            getErrorMessage(frameworkError);
        }
    }

    private static AlertDialog createErrorDialog(final Context context, final MediaPlayer.OnCompletionListener completionListener, final MediaPlayer mediaPlayer, final int errorMessage) {
        return new AlertDialog.Builder(context)
                .setMessage(errorMessage)
                .setPositiveButton(
                        android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, final int whichButton) {
                                    /* If we get here, there is no onError listener, so
                                     * at least inform them that the video is over.
                                     */
                                if (completionListener != null) {
                                    completionListener.onCompletion(mediaPlayer);
                                }
                            }
                        }
                )
                .setCancelable(false)
                .create();
    }

    private static int getErrorMessage(final int frameworkError) {
        int messageId = R.string.fen__play_error_message;

        if (frameworkError == MediaPlayer.MEDIA_ERROR_IO) {
            Log.e(TAG, "TextureVideoView error. File or network related operation errors.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            Log.e(TAG, "TextureVideoView error. Bitstream is not conforming to the related coding standard or file spec.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            Log.e(TAG, "TextureVideoView error. Media server died. In this case, the application must release the MediaPlayer object and instantiate a new one.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            Log.e(TAG, "TextureVideoView error. Some operation takes too long to complete, usually more than 3-5 seconds.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            Log.e(TAG, "TextureVideoView error. Unspecified media player error.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            Log.e(TAG, "TextureVideoView error. Bitstream is conforming to the related coding standard or file spec, but the media framework does not support the feature.");
        } else if (frameworkError == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
            Log.e(TAG, "TextureVideoView error. The video is streamed and its container is not valid for progressive playback i.e the video's index (e.g moov atom) is not at the start of the file.");
            messageId = R.string.fen__play_progressive_error_message;
        }
        return messageId;
    }

    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener = new MediaPlayer.OnBufferingUpdateListener() {
        @Override
        public void onBufferingUpdate(final MediaPlayer mp, final int percent) {
            int index = -1;
            for (int i = 0; i < N; ++i) {
                if (mediaPlayers[i] == mp) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                currentBufferPercentages[index] = percent;
            }
        }
    };

    /**
     * Register a callback to be invoked when the media file
     * is loaded and ready to go.
     *
     * @param l The callback that will be run
     */
    public void setOnPreparedListener(int index, final MediaPlayer.OnPreparedListener l) {
        mOnPreparedListeners[index] = l;
    }

    /**
     * Register a callback to be invoked when the end of a media file
     * has been reached during playback.
     *
     * @param l The callback that will be run
     */
    public void setOnCompletionListener(int index, final MediaPlayer.OnCompletionListener l) {
        mOnCompletionListeners[index] = l;
    }

    /**
     * Register a callback to be invoked when an error occurs
     * during playback or setup.  If no listener is specified,
     * or if the listener returned false, VideoView will inform
     * the user of any errors.
     *
     * @param l The callback that will be run
     */
    public void setOnErrorListener(int index, final MediaPlayer.OnErrorListener l) {
        mOnErrorListeners[index] = l;
    }

    /**
     * Register a callback to be invoked when an informational event
     * occurs during playback or setup.
     *
     * @param l The callback that will be run
     */
    private void setOnInfoListener(int index, final MediaPlayer.OnInfoListener l) {
        mOnInfoListeners[index] = l;
    }

    private SurfaceTextureListener mSTListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {

            mSurfaceTexture = surface;
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            for (int i = 0; i < N; ++i) {
                openVideo(i);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
            mSurfaceTexture = surface;
            if (mRenderer != null) {
                mRenderer.setOutputSize(width, height);
            }
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            for (int i = 0; i < N; ++i) {
                boolean isValidState = (targetStates[i] == STATE_PLAYING);
//                boolean hasValidSize = videoSizeCalculators[i].currentSizeIs(width, height);
                if (mediaPlayers[i] != null && isValidState) {
                    if (seekWhenPrepareds[i] != 0) {
                        seekTo(i, seekWhenPrepareds[i]);
                    }
                    start(i);
                }
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
            mSurfaceTexture = null;

            hideMediaController();
            for (int i = 0; i < N; ++i) {
                release(i, true);
            }
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
            if (mSurfaceTexture != surface) {
                mSurfaceTexture = surface;
            }
        }
    };

    /*
     * release the media player in any state
     */
    private void release(int index, final boolean clearTargetState) {
        if (mediaPlayers[index] != null) {
            mediaPlayers[index].release();
            mediaPlayers[index] = null;
            currentStates[index] = STATE_IDLE;
            if (clearTargetState) {
                targetStates[index] = STATE_IDLE;
            }
        }
        if (mRenderer != null) {
            if (mRenderer.isStarted()) {
                mRenderer.onPause();
                mRenderer = null;
            }
        }
    }

    public void start(int index) {
        if (isInPlaybackState(index)) {
            mediaPlayers[index].start();
            currentStates[index] = STATE_PLAYING;
        }
        targetStates[index] = STATE_PLAYING;
    }

    public void start() {
        for (int i = 0; i < N; ++i) {
            start(i);
        }
    }

    public void pause(int index) {
        if (isInPlaybackState(index)) {
            if (mediaPlayers[index].isPlaying()) {
                mediaPlayers[index].pause();
                currentStates[index] = STATE_PAUSED;
            }
        }
        targetStates[index] = STATE_PAUSED;
    }

    public void pause() {
        for (int i = 0; i < N; ++i) {
            pause(i);
        }
    }

    public void suspend() {
        for (int i = 0; i < N; ++i) {
            release(i, false);
        }
    }

    public void resume(int index) {
        openVideo(index);
    }

    public int getDuration(int index) {
        if (isInPlaybackState(index)) {
            return mediaPlayers[index].getDuration();
        }

        return -1;
    }

    /**
     * @return current position in milliseconds
     */
    public int getCurrentPosition(int index) {
        if (isInPlaybackState(index)) {
            return mediaPlayers[index].getCurrentPosition();
        }
        return 0;
    }

    public int getCurrentPositionInSeconds(int index) {
        return getCurrentPosition(index) / MILLIS_IN_SEC;
    }

    public void seekTo(int index, final int millis) {
        if (isInPlaybackState(index)) {
            try {
                mediaPlayers[index].seekTo(millis);
            } catch (IllegalStateException e) {
                e.printStackTrace();
                seekWhenPrepareds[index] = millis;
            }
        } else {
            seekWhenPrepareds[index] = millis;
        }
    }

    public void seekToSeconds(int index, final int seconds) {
        seekTo(index, seconds * MILLIS_IN_SEC);
        mediaPlayers[index].setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(final MediaPlayer mp) {
                Log.i(TAG, "seek completed");
            }
        });
    }

    public boolean isPlaying(int index) {
        return isInPlaybackState(index) && mediaPlayers[index].isPlaying();
    }

    public int getBufferPercentage(int index) {
        if (mediaPlayers[index] != null) {
            return currentBufferPercentages[index];
        }
        return 0;
    }

    private boolean isInPlaybackState(int index) {
        return (mediaPlayers[index] != null &&
                currentStates[index] != STATE_ERROR &&
                currentStates[index] != STATE_IDLE &&
                currentStates[index] != STATE_PREPARING);
    }


    private final MediaPlayer.OnInfoListener onInfoToPlayStateListener = new MediaPlayer.OnInfoListener() {

        @Override
        public boolean onInfo(final MediaPlayer mp, final int what, final int extra) {
            if (noPlayStateListener()) {
                return false;
            }

            if (MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START == what) {
                onPlayStateListener.onFirstVideoFrameRendered();
                onPlayStateListener.onPlay();
            }
            if (MediaPlayer.MEDIA_INFO_BUFFERING_START == what) {
                onPlayStateListener.onBuffer();
            }
            if (MediaPlayer.MEDIA_INFO_BUFFERING_END == what) {
                onPlayStateListener.onPlay();
            }

            return false;
        }
    };

    private boolean noPlayStateListener() {
        return !hasPlayStateListener();
    }

    private boolean hasPlayStateListener() {
        return onPlayStateListener != null;
    }

    public void setOnPlayStateListener(final FensterVideoStateListener onPlayStateListener) {
        this.onPlayStateListener = onPlayStateListener;
    }

    public Renderer getRenderer() {
        return mRenderer;
    }

    public void setRenderer(Renderer renderer) {
        this.mRenderer = renderer;
    }

    public boolean canPause(int index) {
        return canPause[index];
    }

    public boolean canSeekBack(int index) {
        return canSeekBack[index];
    }

    public boolean canSeekForward(int index) {
        return canSeekForward[index];
    }
}
