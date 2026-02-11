package com.oney.WebRTCModule;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.TextureView;
import android.view.ViewTreeObserver;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class TextureViewRenderer extends TextureView implements VideoSink, TextureView.SurfaceTextureListener {
    private static final String TAG = "TextureViewRenderer";

    private final EglRenderer eglRenderer;
    private RendererCommon.RendererEvents rendererEvents;
    private RendererCommon.ScalingType scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT;
    private boolean mirror = false;
    private EglBase.Context sharedContext;
    private boolean isReleased = false;

    public TextureViewRenderer(Context context) {
        super(context);
        Log.d(TAG, "TextureViewRenderer constructor");
        eglRenderer = new EglRenderer(TAG);
        setSurfaceTextureListener(this);
        setOpaque(false);
    }

    public void init(EglBase.Context sharedContext, RendererCommon.RendererEvents rendererEvents) {
        Log.d(TAG, "init called");
        this.sharedContext = sharedContext;
        this.rendererEvents = rendererEvents;

        ThreadUtils.checkIsOnMainThread();
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, new GlRectDrawer());

        // Apply stored mirror value now that we're initialized
        eglRenderer.setMirror(mirror);
        eglRenderer.setLayoutAspectRatio((float) getWidth() / getHeight());

        Log.d(TAG, "EglRenderer initialized, isAvailable=" + isAvailable() +
                   " width=" + getWidth() + " height=" + getHeight());

        if (isAvailable()) {
            SurfaceTexture surfaceTexture = getSurfaceTexture();
            if (surfaceTexture != null) {
                Log.d(TAG, "Surface already available, creating EGL surface");
                eglRenderer.createEglSurface(surfaceTexture);
                if (rendererEvents != null) {
                    rendererEvents.onFirstFrameRendered();
                }
            }
        }
    }

    public void release() {
        Log.d(TAG, "release called");
        isReleased = true;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        eglRenderer.releaseEglSurface(() -> {
            eglRenderer.release();
            latch.countDown();
        });
        ThreadUtils.awaitUninterruptibly(latch);
    }

    public void setMirror(boolean mirror) {
        Log.d(TAG, "setMirror: " + mirror);
        this.mirror = mirror;
        eglRenderer.setMirror(mirror);
    }

    public void setScalingType(RendererCommon.ScalingType scalingType) {
        Log.d(TAG, "setScalingType: " + scalingType);
        this.scalingType = scalingType;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        eglRenderer.onFrame(frame);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: " + width + "x" + height);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Not used
    }

    public void clearImage() {
        eglRenderer.clearImage();
    }

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(int width, int height);
    }

    private OnSurfaceReadyListener onSurfaceReadyListener;
    private boolean surfaceAvailable = false;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener listener) {
        Log.d(TAG, "setOnSurfaceReadyListener called, surfaceAvailable=" + surfaceAvailable +
                   " dimensions=" + surfaceWidth + "x" + surfaceHeight);
        this.onSurfaceReadyListener = listener;

        // If surface is already available, call listener immediately on main thread
        if (surfaceAvailable && listener != null && surfaceWidth > 0 && surfaceHeight > 0) {
            Log.d(TAG, "Surface already available when listener set: " + surfaceWidth + "x" + surfaceHeight);
            post(() -> listener.onSurfaceReady(surfaceWidth, surfaceHeight));
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable: " + width + "x" + height + " listener=" + (onSurfaceReadyListener != null));
        ThreadUtils.checkIsOnMainThread();

        surfaceAvailable = true;
        surfaceWidth = width;
        surfaceHeight = height;

        if (!isReleased && sharedContext != null) {
            eglRenderer.createEglSurface(surface);
            if (rendererEvents != null) {
                rendererEvents.onFirstFrameRendered();
            }
        }

        // Notify listener that surface is ready
        if (onSurfaceReadyListener != null) {
            Log.d(TAG, "Calling onSurfaceReadyListener");
            onSurfaceReadyListener.onSurfaceReady(width, height);
        } else {
            Log.w(TAG, "onSurfaceReadyListener is null when surface became available");
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "onSurfaceTextureDestroyed");
        ThreadUtils.checkIsOnMainThread();

        surfaceAvailable = false;
        surfaceWidth = 0;
        surfaceHeight = 0;

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        eglRenderer.releaseEglSurface(() -> latch.countDown());
        ThreadUtils.awaitUninterruptibly(latch);
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.d(TAG, "onAttachedToWindow - re-registering listener, isAvailable=" + isAvailable());

        // Re-register the listener to ensure it's properly attached
        setSurfaceTextureListener(this);

        // Use ViewTreeObserver.OnPreDrawListener which fires after measurement
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                int width = getWidth();
                int height = getHeight();

                Log.d(TAG, "onPreDraw: dimensions=" + width + "x" + height +
                           " isAvailable=" + isAvailable() + " surfaceAvailable=" + surfaceAvailable);

                if (width > 0 && height > 0 && isAvailable() && !surfaceAvailable) {
                    SurfaceTexture surface = getSurfaceTexture();
                    if (surface != null) {
                        Log.d(TAG, "Surface became available via onPreDraw, triggering callback");
                        onSurfaceTextureAvailable(surface, width, height);

                        // Remove listener after first successful callback
                        getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                }

                return true; // Return true to proceed with drawing
            }
        });

        // Also check immediately if surface is already available
        if (isAvailable()) {
            SurfaceTexture surface = getSurfaceTexture();
            if (surface != null) {
                int width = getWidth();
                int height = getHeight();
                if (width > 0 && height > 0) {
                    Log.d(TAG, "Surface already available in onAttachedToWindow: " + width + "x" + height);
                    onSurfaceTextureAvailable(surface, width, height);
                }
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int width = right - left;
        int height = bottom - top;

        Log.d(TAG, "onLayout: changed=" + changed + " dimensions=" + width + "x" + height +
                   " isAvailable=" + isAvailable() + " surfaceAvailable=" + surfaceAvailable);

        // If layout has dimensions and surface is now available but we haven't notified yet
        if (width > 0 && height > 0 && isAvailable() && !surfaceAvailable) {
            SurfaceTexture surface = getSurfaceTexture();
            if (surface != null) {
                Log.d(TAG, "Surface became available during layout, manually triggering callback");
                onSurfaceTextureAvailable(surface, width, height);
            }
        }
    }

}
