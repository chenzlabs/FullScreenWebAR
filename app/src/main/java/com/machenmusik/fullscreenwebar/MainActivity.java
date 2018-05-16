package com.machenmusik.fullscreenwebar;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.ar.core.*;
/*
import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
*/
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.machenmusik.fullscreenwebar.rendering.BackgroundRenderer;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.FloatBuffer;
import java.util.Collection;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView mSurfaceView;

    private Config mDefaultConfig;
    private Session mSession;
    private BackgroundRenderer mBackgroundRenderer = new BackgroundRenderer();

    private WebView mWebView;
    private WebARonARCoreInterface mInterface;
    
    private float mNear;
    private float mFar;

    private Map<Integer, Long> planeTimestamps;

    private Runnable mRunnable;

    // Set to true ensures requestInstall() triggers installation if necessary.
    private boolean mUserRequestedInstall = true;

    private boolean mARDataWasUsed = true;
    private Frame mDrawThisFrame = null;

    private void ensureSession() {
        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this);
            // NOTE: This does not block!
        }
        else
        try {
            if (mSession == null) {
                switch (ArCoreApk.getInstance().requestInstall(this, mUserRequestedInstall)) {
                    case INSTALLED:
                        mSession = new Session(this);
                        // Success.
                        break;
                    case INSTALL_REQUESTED:
                        // Ensures next invocation of requestInstall() will either return
                        // INSTALLED or throw an exception.
                        mUserRequestedInstall = false;
                        return;
                }
            }
        } catch (UnavailableDeviceNotCompatibleException e) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "Device Not Compatible", Toast.LENGTH_LONG).show();
            return;
        } catch (UnavailableArcoreNotInstalledException e) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "ARCore Not Installed", Toast.LENGTH_LONG).show();
            return;
        } catch (UnavailableApkTooOldException e) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "APK Too Old", Toast.LENGTH_LONG).show();
            return;
        } catch (UnavailableSdkTooOldException e) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "SDK Too Old", Toast.LENGTH_LONG).show();
            return;
        } catch (UnavailableUserDeclinedInstallationException e) {
            // Display an appropriate message to the user and return gracefully.
            return;
        } //catch (...){  // current catch statements
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            // this can happen during initial challenge... finish();
        } else {
            ensureSession();
            if (mSession != null) {
                onceWeHavePermission();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        mSurfaceView = (GLSurfaceView)findViewById(R.id.surfaceview);

        mNear = 0.1f;
        mFar = 10000f;

        planeTimestamps = null;

        mRunnable = new Runnable(){
            @Override
            public void run(){
                mWebView.evaluateJavascript(
                        // Only set data if getVRDisplays has been called.
                        "javascript:"
                                + "if(window.getVRDisplaysPromise){"
                                + "window.WebARonARCoreSetData(JSON.parse(window.WebARonARCore.getData()));"
                                + "window.WebARonARCore.postMessage('arDataWasUsed:');"
                                + "}",
                        null);
            }
        };

        // Set up renderer.
        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        mSurfaceView.setRenderer(this);
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        mWebView = (WebView)findViewById(R.id.activity_main_webview);

        mWebView.setBackgroundColor(0x00000000);
        mWebView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        mInterface = new WebARonARCoreInterface(this);
        mWebView.addJavascriptInterface(mInterface, "WebARonARCore");

        // Force links and redirects to open in the WebView instead of in a browser
        mWebView.setWebViewClient(new WebViewClient(){
            @Override
            public void onPageFinished(WebView view, String url) {
                injectJS(); // FIXME: the injection may not finish before scene is loaded!
                super.onPageFinished(view, url);
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient(){
        });

        WebView.setWebContentsDebuggingEnabled(true);

        // Enable Javascript
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);

        if (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setAllowFileAccessFromFileURLs(true);

        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setGeolocationEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Try to ensure we have a session, which may trigger camera permission check.
        ensureSession();
        if (mSession != null) {
            onceWeHavePermission();
        }
    }

    protected void onceWeHavePermission() {
        // Create default config, check is supported, create session from that config.
        mDefaultConfig = new Config(mSession); // changed with 1.0... createDefaultConfig();
        mDefaultConfig.setLightEstimationMode(Config.LightEstimationMode.AMBIENT_INTENSITY); // changed with 1.0... setLightingMode(Config.LightingMode.AMBIENT_INTENSITY);
        mDefaultConfig.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
        mDefaultConfig.setUpdateMode(Config.UpdateMode.BLOCKING); //LATEST_CAMERA_IMAGE);
/* isSupported now deprecated...
        if (!mSession.isSupported(mDefaultConfig)) {
            Toast.makeText(this, "This device does not support AR", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
*/
        // (1.0) Use the config for this session.
        mSession.configure(mDefaultConfig);

        mWebView.loadUrl("https://clara.glitch.me/");
    }

    @Override
    public void onBackPressed() {
        if(mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            // Try to ensure we have a session, which may trigger camera permission check.
            ensureSession();
            // Note that order matters - see the note in onPause(), the reverse applies here.
            if (mSession != null) {
                mSession.resume(); // this changed with 1.0... mDefaultConfig);
            }
            mSurfaceView.onResume();
        } catch (CameraNotAvailableException e) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "Camera Not Available", Toast.LENGTH_LONG).show();
            return;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call mSession.update() and get a SessionPausedException.
        mSurfaceView.onPause();
        if (mSession != null) {
            mSession.pause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Create the texture and pass it to ARCore session to be filled during update().
        mBackgroundRenderer.createOnGlThread(/*context=*/this);
        mSession.setCameraTextureName(mBackgroundRenderer.getTextureId());
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        mSession.setDisplayGeometry(rotation, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!mARDataWasUsed) { return; }

        if (mDrawThisFrame != null) {
            // Clear screen to notify driver it should not load any pixels from previous frame.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // Draw background.
            mBackgroundRenderer.draw(mDrawThisFrame);

            mDrawThisFrame = null;
        }

        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = mSession.update(); // FIXME: we should only draw once AR data sent

            updatePlaneTimestampsForARCoreSessionFrame(mSession, frame);
            mInterface.jsonData = jsonDataFromARCoreSessionFrame(mSession, frame, mNear, mFar);
            mInterface.pointcloudData = pointCloudDataFromARCoreSessionFrame(mSession, frame);

            // Set the data.
            this.runOnUiThread(mRunnable);
            mDrawThisFrame = frame;
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void updatePlaneTimestampsForARCoreSessionFrame(Session session, Frame frame) {
        long frameTimestamp = frame.getTimestamp(); // name changed in 1.0... getTimestampNs();

        if (planeTimestamps == null) {
            planeTimestamps = new HashMap<Integer, Long>();
            for (Plane plane : session.getAllTrackables(Plane.class)) { // name changed in 1.0... getAllPlanes()) {
                planeTimestamps.put(plane.hashCode(), frameTimestamp);
            }
        } else {
            for (Plane plane : frame.getUpdatedTrackables(Plane.class)) { // name changed in 1.0... getUpdatedPlanes()) {
                planeTimestamps.put(plane.hashCode(), frameTimestamp);
            }
        }
    }

    private String pointCloudDataFromARCoreSessionFrame(Session session, Frame frame) {
        StringBuffer pcjs = new StringBuffer();
        PointCloud pointcloud = frame.acquirePointCloud(); // name changed in 1.0... getPointCloud();
        if (pointcloud != null) {
            FloatBuffer points = pointcloud.getPoints();
            if (points != null) {
                for (int i = 0; i < points.remaining(); i += 4) {
                    float x = points.get(i);
                    float y = points.get(i + 1);
                    float z = points.get(i + 2);
                    //float p = vertices[i+3];
                    if (pcjs.length() == 0) {
                        pcjs.append(String.format("%f,%f,%f", x, y, z));
                    } else {
                        pcjs.append(String.format(",%f,%f,%f", x, y, z));
                    }
                }
            }
        }
// no longer in 1.0... Pose pose = frame.getPointCloudPose();
        String rtn = "";
//        if (pose != null) {
            rtn =  String.format(
/* changed in 1.0
                "{\"position\":[%f,%f,%f]" +
                ",\"orientation\":[%f,%f,%f,%f]" +
                ",\"timestamp\":%d" +
*/
                "{\"timestamp\":%d" +
                ",\"points\":[%s]}",
/* changed in 1.0
                pose.tx(), pose.ty(), pose.tz(),
                pose.qx(), pose.qy(), pose.qz(), pose.qw(),
*/
                pointcloud.getTimestamp(), // changed in 1.0... getTimestampNs(),
                pcjs);
//        }
        if (pointcloud != null) { pointcloud.release(); }
        return rtn;
    }

    private String jsonDataFromARCoreSessionFrame(Session session, Frame frame, float fNear, float fFar) {
        String anchorsString = "";
        float[] m = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
        for (Plane plane : session.getAllTrackables(Plane.class)) { // changed in 1.0... getAllPlanes()) {
            int id = plane.hashCode();
            Pose pose = plane.getCenterPose();
            pose.toMatrix(m, 0);
            StringBuffer verticesJSON = new StringBuffer();
            float[] vertices = plane.getPolygon() /* changed in 1.0... getPlanePolygon() */.array();
            for (int i=0; i<vertices.length; i+=2) {
                float x = vertices[i];
                float y = 0;
                float z = vertices[i+1];
                if (verticesJSON.length() == 0) {
                    verticesJSON.append(String.format("%f,%f,%f", x, y, z));
                } else {
                    verticesJSON.append(String.format(",%f,%f,%f", x, y, z));
                }
            }
            anchorsString += String.format(
                    "%s{\"modelMatrix\":[%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f]" +
                            ",\"identifier\":%d" +
                            ",\"alignment\":%d" +
                            ",\"timestamp\":%d" +
                            ",\"vertices\":[%s]" +
                            ",\"extent\":[%f,%f]}",
                    anchorsString.length() > 0 ? "," : "",
                    m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                    m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15],
                    id,
                    plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING ? 0 : -1,
                    planeTimestamps.get(id),
                    verticesJSON,
                    plane.getExtentX(),
                    plane.getExtentZ());
        }

        Pose pose = frame.getCamera()./*getDisplayOrientedPose()*/getPose(); // for 1.0, this is from camera (?)
        float[] viewM = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
        float[] projM = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
        frame.getCamera().getViewMatrix(viewM, 0); // for 1.0, this is from camera
        frame.getCamera().getProjectionMatrix(projM, 0, fNear, fFar); // for 1.0, this is from camera, not session
        String rtn = String.format(
            "{\"position\":[%f,%f,%f]" +
            ",\"orientation\":[%f,%f,%f,%f]" +
            ",\"viewMatrix\":[%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f]" +
            ",\"projectionMatrix\":[%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f]" +
            ",\"lightEstimate\":{\"ambientIntensity\":%f}" +
            ",\"anchors\":[%s]}",
                pose.tx(), pose.ty(), pose.tz(),
                pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                viewM[0], viewM[1], viewM[2], viewM[3], viewM[4], viewM[5], viewM[6], viewM[7],
                viewM[8], viewM[9], viewM[10], viewM[11], viewM[12], viewM[13], viewM[14], viewM[15],
                projM[0], projM[1], projM[2], projM[3], projM[4], projM[5], projM[6], projM[7],
                projM[8], projM[9], projM[10], projM[11], projM[12], projM[13], projM[14], projM[15],
                frame.getLightEstimate().getPixelIntensity(),
                anchorsString);
        return rtn;
    }

    private void injectJS() {
        try {
            InputStream inputStream = this.getApplicationContext().getResources().openRawResource(R.raw.webaronarcore); //getAssets().open("webaronarcore.jss");
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();

            String encoded = Base64.encodeToString(buffer, Base64.NO_WRAP);
            mWebView.evaluateJavascript("javascript:(function() {" +
                    "var parent = document.getElementsByTagName('head').item(0);" +
                    "var script = document.createElement('script');" +
                    "script.type = 'text/javascript';" +
                    // don't forget to use decodeURIComponent after base64 decoding
                    "script.innerHTML = decodeURIComponent(escape(window.atob('" + encoded + "')));" +
                    "parent.appendChild(script)" +
                    "})()", null /* new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String s) {
                    //Log.d(TAG, s);
                }
            } */ );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
    WebARonARCore interface
    */
    public class WebARonARCoreInterface {
        Context mContext;
        public String jsonData;
        public String pointcloudData;

        WebARonARCoreInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public String getData() {
            return jsonData;
        }

        @JavascriptInterface
        public String getPointCloudData() {
            return pointcloudData;
        }

        @JavascriptInterface
        public void postMessage(String msg) {
            // TODO: handle these properly
            if (msg.startsWith("showCameraFeed:")) {
                Log.d(TAG, msg);
            } else
            if (msg.startsWith("hideCameraFeed:")) {
                Log.d(TAG, msg);
            } else
            if (msg.startsWith("setDepthNear:")) {
                Log.d(TAG, msg);
            } else
            if (msg.startsWith("setDepthFar:")) {
                Log.d(TAG, msg);
            } else
            if (msg.startsWith("log:")) {
                Log.d(TAG, msg);
            } else
            if (msg.startsWith("arDataWasUsed:")) {
                //Log.d(TAG, msg);
                mARDataWasUsed = true;
            }
        }
    }
}
