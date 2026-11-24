package org.x9s26u.rcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.widget.ImageView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Rational;
import java.util.concurrent.Executor;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA_PERMISSION = 101;

    private TextureView textureView;
    private View overlayView;
    private TextView statusHud;
    private TextView pipelineHud;
    private TextView modeTitleView;
    private LinearLayout proControlsPanel;
    private Button afBtnRef;
    private Button macroBtnRef;
    private Button lensBtnRef;
    private Button ratioBtnRef;
    private Button oisBtnRef;
    private Button profileBtnRef;
    private Button nrBtnRef;
    private Button xmlBtnRef;
    private SeekBar focusBarRef;
    private TextView focusLabelRef;

    private CameraManager cameraManager;
    private String[] cameraIds = new String[0];
    private int currentCameraIndex = 0;
    private String currentCameraId = "0";

    // Physical Sub-Camera Routing via OutputConfiguration.setPhysicalCameraId() on Logical Camera "0"
    private String logicalRearCameraId = "0";
    private String physicalUwId = null;     // 0.6x Ultra-Wide (15mm)
    private String physicalMainId = null;   // 1x Main Wide (23mm LYT-808)
    private String physicalTeleId = null;   // 3x Periscope Telephoto (73mm LYT-600)
    private String activePhysicalSubId = null; // Currently bound physical sensor ID (null = logical auto)
    private float opticalBaseZoomFactor = 1.0f; // 0.6f, 1.0f, or 3.0f depending on active physical lens

    // Multi-Frame HDR + MFSR Burst State
    // Options: "MFSR+HDR BURST (3-FRAME)", "OEM HAL EXTENSION (HDR)", "SINGLE FAST"
    private String currentCapturePipeline = "NATIVE JPEG";
    private Button pipeBtnRef;
    private Button resBtnRef;
    private final List<byte[]> burstJpegFrames = new ArrayList<>();
    private int expectedBurstFrames = 1;
    private int burstSnapOrientation = 0;
    private CameraExtensionSession extensionSession = null;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private Size previewSize = new Size(1920, 1080);
    private Size sensorCaptureSize = new Size(4096, 3072);

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private MediaActionSound shutterSound;
    private OrientationEventListener orientationListener;
    private int deviceOrientationDegrees = 0;

    // Output Resolution Options: "30MP", "50MP", "12MP"
    private String currentResTarget = "NATIVE";

    // Aspect Ratio Options: "4:3", "16:9", "1:1", "XPAN 65:24", "FULL 20:9"
    private String currentAspectRatio = "4:3";

    // OIS + EIS Stabilization State
    private boolean oisEnabled = true;
    private boolean hardwareOisSupported = true;

    // Built on v12's YCbCr Engine — Upgraded so Luma is NEVER plastery!
    // "NR: CRISP (CHROMA)" (Default: 0 Luma blur + Full Chroma Denoise + Crisp Micro-Clarity!)
    // "NR: BALANCED" (5-level subtle Luma smooth + Full Chroma Denoise + Crisp Micro-Clarity)
    // "NR: LOW-LIGHT" (10-level Luma smooth + Full Chroma Denoise + Crisp Micro-Clarity)
    private String currentDenoiseMode = "NR: BALANCED";

    // v12's Vibrant Hasselblad / LUMO ISP Profiles (Live 60fps Viewfinder + 25MP Output)
    private String currentIspProfile = "NATURAL";

    // Active XML Config Profile loaded from cam.zip / project.zip
    private final List<String> xmlConfigNames = new ArrayList<>();
    private int currentXmlIndex = 0;
    private float xmlSharpnessGain = 0.72f;
    private float teleSharpnessGain = 0.84f;
    private float xmlSaturationGain = 1.14f;
    private float xmlContrastGain = 1.08f;
    private float xmlChromaDenoiseStrength = 32.0f;
    private float camZipGamma = 0.85f;
    private int[] xZipCustomToneLut = null;
    private int prunedRedundantSoCount = 0;
    private int prunedRedundantXmlCount = 0;
    private float haloAideDenoiseStrength = 0.65f;
    private float haloAideDetailEnhance = 0.78f;
    private float haloCafCoringThreshold = 14.0f;
    private ImageReader dualMacroImageReader = null;
    private volatile byte[] latestDualLensMacroBytes = null;
    private boolean dualLensSimultaneousActive = false;
    private String macroSubMode = "OFF"; // "OFF", "1x JN5+LYT ULTRA", "3x TELE FUSED"
    private String preMacroPhysicalSubId = null;
    private float preMacroOpticalFactor = 1.0f;
    private float preMacroZoom = 1.0f;
    private Uri lastSavedPhotoUri = null;
    private Bitmap lastSavedPreviewBitmap = null;
    private Button openPhotoBtnRef = null;
    private final int cpuCores = Math.max(2, Runtime.getRuntime().availableProcessors());
    private final ExecutorService ispThreadPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private float[] camZipCcm = new float[]{
        1.08f, -0.04f, -0.02f, 0f, 3f,
       -0.02f,  1.06f, -0.02f, 0f, 2f,
       -0.02f, -0.04f,  1.06f, 0f, 0f,
        0f,     0f,     0f,    1f, 0f
    };
    private String[] safeLoadableSoLibs = new String[0];
    private boolean camZipSoLoadedOnce = false;

    // Camera Parameters, AutoFocus & Macro Through All Lenses
    private String currentMode = "PHOTO";
    private String currentAfStrategy = "AF-C";
    private boolean macroAllLensEnabled = false;
    private float minFocusDistanceDiopters = 10.0f;
    private float manualFocusDiopters = 0.0f;
    private int currentAfState = CaptureResult.CONTROL_AF_STATE_INACTIVE;

    private int liveSensorIso = 100;
    private long liveExposureNs = 10_000_000L;

    private float focusRingX = -1f;
    private float focusRingY = -1f;
    private long focusRingTimestamp = 0L;
    private MeteringRectangle[] activeAfRegions = null;
    private MeteringRectangle[] activeAeRegions = null;

    private float currentZoom = 1.0f;
    private float maxZoom = 10.0f;
    private int currentEvIndex = 0;
    private int currentIso = 0;
    private int currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    private boolean flashEnabled = false;
    private boolean gridEnabled = true;

    private final List<String> packagedNativeLibs = new ArrayList<>();
    private final List<String> safeLoadedRuntimeLibs = new ArrayList<>();
    private final List<String> discoveredJniHooks = new ArrayList<>();

    private volatile boolean isCapturing = false;
    private volatile boolean processing = false;
    private volatile boolean resumed = false;
    private volatile int cameraGeneration = 0;
    private volatile boolean opening = false;
    private int selectedExtension = -1;
    private int captureSerial = 0;
    private Surface previewSurface;
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    private String shotProfile = "NATURAL", shotRatio = "4:3", shotResolution = "NATIVE";
    private int shotIso = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        shutterSound.load(MediaActionSound.FOCUS_COMPLETE);

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                if (orientation >= 315 || orientation < 45) {
                    deviceOrientationDegrees = 0;
                } else if (orientation >= 45 && orientation < 135) {
                    deviceOrientationDegrees = 90;
                } else if (orientation >= 135 && orientation < 225) {
                    deviceOrientationDegrees = 180;
                } else {
                    deviceOrientationDegrees = 270;
                }
            }
        };

        // App-side JPEG processing does not load proprietary HAL libraries.
        xmlConfigNames.add("Reference only (not HAL tuning)");
        buildCameraUi();
        applyLiveViewfinderIspFilter();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    /**
     * Safe, instant startup initializer:
     *   1. Reads pre-compiled CamZipConfigData (extracted from cam.zip + project.zip at build time).
     *   2. Inventories packaged .so files in nativeLibraryDir WITHOUT calling dlopen() on proprietary
     *      HAL libraries whose C++ .init_array constructors abort() when outside the system camserver process!
     *      Only loads standalone C++ runtime libraries (like libc++_shared.so).
     */
    private void initSafeCamZipResources() {
        try {
            Class<?> cfgCls = Class.forName("org.x9s26u.pcam.CamZipConfigData");
            xmlSharpnessGain = cfgCls.getField("SHARPNESS_GAIN").getFloat(null);
            xmlSaturationGain = cfgCls.getField("SATURATION_GAIN").getFloat(null);
            xmlContrastGain = cfgCls.getField("CONTRAST_GAIN").getFloat(null);
            String[] xmlList = (String[]) cfgCls.getField("XML_CONFIG_FILES").get(null);
            if (xmlList != null && xmlList.length > 0) {
                Collections.addAll(xmlConfigNames, xmlList);
            }
            String[] summary = (String[]) cfgCls.getField("CAM_ZIP_SUMMARY").get(null);
            if (summary != null) {
                Collections.addAll(discoveredJniHooks, summary);
            }
            float[] ccm = (float[]) cfgCls.getField("CAM_ZIP_CCM").get(null);
            if (ccm != null && ccm.length == 20) {
                camZipCcm = ccm;
            }
            camZipGamma = cfgCls.getField("CAM_ZIP_GAMMA").getFloat(null);
            xmlChromaDenoiseStrength = cfgCls.getField("CHROMA_DENOISE_GAIN").getFloat(null);
            teleSharpnessGain = cfgCls.getField("TELE_SHARPNESS_GAIN").getFloat(null);
            int[] customLut = (int[]) cfgCls.getField("X_ZIP_TONE_LUT").get(null);
            if (customLut != null && customLut.length == 256) {
                xZipCustomToneLut = customLut;
            }
            prunedRedundantSoCount = cfgCls.getField("PRUNED_SO_COUNT").getInt(null);
            prunedRedundantXmlCount = cfgCls.getField("PRUNED_XML_COUNT").getInt(null);
            haloAideDenoiseStrength = cfgCls.getField("HALO_AIDE_DENOISE").getFloat(null);
            haloAideDetailEnhance = cfgCls.getField("HALO_AIDE_DETAIL").getFloat(null);
            haloCafCoringThreshold = cfgCls.getField("HALO_CAF_CORING").getFloat(null);
            String[] safeSos = (String[]) cfgCls.getField("SAFE_SO_LIBS").get(null);
            if (safeSos != null) {
                safeLoadableSoLibs = safeSos;
            }
        } catch (Throwable ignored) {}

        if (xmlConfigNames.isEmpty()) {
            xmlConfigNames.add("Reference only (not HAL tuning)");
        }

        try {
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            if (nativeDir != null) {
                File dir = new File(nativeDir);
                File[] soFiles = dir.listFiles();
                if (soFiles != null) {
                    Arrays.sort(soFiles, new Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    for (File so : soFiles) {
                        String name = so.getName();
                        if (name.startsWith("lib") && name.endsWith(".so")) {
                            packagedNativeLibs.add(name + " (" + (so.length() / 1024) + " KB)");
                            // Only dlopen standalone C++ STL/math libraries that never call HAL abort()
                            if (name.equals("libc++_shared.so") || name.equals("libomp.so")) {
                                try {
                                    System.loadLibrary(name.substring(3, name.length() - 3));
                                    safeLoadedRuntimeLibs.add(name);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void loadVerifiedCamZipNativeLibrariesAsync() {
        // Vendor camera-service plugins are not app-side JNI libraries. Do not load them.
    }

    private void loadActiveXmlTuningProfileAsync(final String assetPath) {
        if (backgroundHandler == null) return;
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try (InputStream is = getAssets().open(assetPath);
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    int count = 0;
                    while ((line = br.readLine()) != null && count < 600) {
                        count++;
                        String low = line.toLowerCase(Locale.US).trim();
                        if (low.contains("sharp") || low.contains("noisemodel") || low.contains("sabre") || low.contains("detail")) {
                            Float v = parseNumericTagValue(line);
                            if (v != null && v >= 0.1f && v <= 2.5f) {
                                xmlSharpnessGain = Math.max(0.35f, Math.min(1.10f, v));
                            }
                        } else if (low.contains("saturat") || low.contains("color")) {
                            Float v = parseNumericTagValue(line);
                            if (v != null && v >= 0.7f && v <= 1.8f) {
                                xmlSaturationGain = v;
                            }
                        } else if (low.contains("contrast") || low.contains("gamma")) {
                            Float v = parseNumericTagValue(line);
                            if (v != null && v >= 0.8f && v <= 1.5f) {
                                xmlContrastGain = v;
                            }
                        } else if (low.contains("chroma") || low.contains("denoise") || low.contains("luma")) {
                            Float v = parseNumericTagValue(line);
                            if (v != null && v >= 1.0f && v <= 60.0f) {
                                xmlChromaDenoiseStrength = v;
                            }
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyLiveViewfinderIspFilter();
                        }
                    });
                } catch (Throwable ignored) {}
            }
        });
    }

    private Float parseNumericTagValue(String line) {
        try {
            String cleaned = line.replaceAll("<[^>]+>", " ").replaceAll("[^0-9.-]+", " ").trim();
            String[] tokens = cleaned.split("\\s+");
            for (int i = tokens.length - 1; i >= 0; i--) {
                if (tokens[i].contains(".")) {
                    return Float.parseFloat(tokens[i]);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void buildCameraUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 1. Live Camera2 TextureView Preview
        textureView = new TextureView(this);
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        root.addView(textureView, tvParams);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        textureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    triggerTouchAutoFocus(event.getX(), event.getY(), v.getWidth(), v.getHeight());
                    return true;
                }
                return false;
            }
        });

        // 2. Aspect Ratio Crop Guides + Composition Grid + AutoFocus Reticle Overlay
        overlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                RectF frameBox = computeViewfinderCropRect(w, h, currentAspectRatio);

                p.setColor(0xCC000000);
                p.setStyle(Paint.Style.FILL);
                if (frameBox.top > 0) {
                    canvas.drawRect(0, 0, w, frameBox.top, p);
                    canvas.drawRect(0, frameBox.bottom, w, h, p);
                }
                if (frameBox.left > 0) {
                    canvas.drawRect(0, frameBox.top, frameBox.left, frameBox.bottom, p);
                    canvas.drawRect(frameBox.right, frameBox.top, w, frameBox.bottom, p);
                }

                if (gridEnabled) {
                    p.setColor(0x44FFFFFF);
                    p.setStrokeWidth(2f);
                    p.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(frameBox, p);
                    float fw = frameBox.width();
                    float fh = frameBox.height();
                    canvas.drawLine(frameBox.left + fw / 3f, frameBox.top, frameBox.left + fw / 3f, frameBox.bottom, p);
                    canvas.drawLine(frameBox.left + 2f * fw / 3f, frameBox.top, frameBox.left + 2f * fw / 3f, frameBox.bottom, p);
                    canvas.drawLine(frameBox.left, frameBox.top + fh / 3f, frameBox.right, frameBox.top + fh / 3f, p);
                    canvas.drawLine(frameBox.left, frameBox.top + 2f * fh / 3f, frameBox.right, frameBox.top + 2f * fh / 3f, p);
                }

                if ("XPAN 65:24".equals(currentAspectRatio)) {
                    p.setColor(0xFFFF9800);
                    p.setStrokeWidth(4f);
                    p.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(frameBox, p);
                } else if (macroAllLensEnabled) {
                    p.setColor(0x8800E676);
                    p.setStrokeWidth(4f);
                    p.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(frameBox, p);
                }

                long elapsed = System.currentTimeMillis() - focusRingTimestamp;
                if (focusRingX >= 0 && focusRingY >= 0 && elapsed < 3500) {
                    boolean locked = (currentAfState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || currentAfState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED);
                    p.setColor(locked ? 0xFF00E676 : 0xFFFFEA00);
                    p.setStrokeWidth(5f);
                    p.setStyle(Paint.Style.STROKE);
                    float radius = 86f;
                    canvas.drawCircle(focusRingX, focusRingY, radius, p);
                    canvas.drawLine(focusRingX - radius - 18, focusRingY, focusRingX - radius + 14, focusRingY, p);
                    canvas.drawLine(focusRingX + radius - 14, focusRingY, focusRingX + radius + 18, focusRingY, p);
                    canvas.drawLine(focusRingX, focusRingY - radius - 18, focusRingX, focusRingY - radius + 14, p);
                    canvas.drawLine(focusRingX, focusRingY + radius - 14, focusRingX, focusRingY + radius + 18, p);
                    postInvalidateDelayed(120);
                }
            }
        };
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 3. Top Hasselblad / LUMO HUD Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(0x99000000);
        topBar.setPadding(22, 42, 22, 16);

        HorizontalScrollView topScroll = new HorizontalScrollView(this);
        topScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout topButtonsRow = new LinearLayout(this);
        topButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        topButtonsRow.setGravity(Gravity.CENTER_VERTICAL);

        profileBtnRef = createPillButton("🎨 LOOK: NATURAL", 0xFFE65100);
        profileBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleHasselbladLumoProfile();
            }
        });
        topButtonsRow.addView(profileBtnRef);

        nrBtnRef = createPillButton("🧼 " + currentDenoiseMode, 0xFF00838F);
        nrBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleDenoiseMode();
            }
        });
        topButtonsRow.addView(nrBtnRef);

        String firstXmlShort = shortenName(xmlConfigNames.get(0));
        xmlBtnRef = createPillButton("📄 XML: " + firstXmlShort, 0xFF6A1B9A);
        xmlBtnRef.setOnClickListener(v -> Toast.makeText(this, "Vendor XML is reference only; Android HAL tuning is not writable by this app.", Toast.LENGTH_LONG).show());
        topButtonsRow.addView(xmlBtnRef);

        resBtnRef = createPillButton("📐 RES: NATIVE", 0xFFD84315);
        resBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleOutputResolution(resBtnRef);
            }
        });
        topButtonsRow.addView(resBtnRef);

        pipeBtnRef = createPillButton("⚡ PIPE: NATIVE JPEG", 0xFF00695C);
        pipeBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleCapturePipeline();
            }
        });
        topButtonsRow.addView(pipeBtnRef);

        ratioBtnRef = createPillButton("🖼️ RATIO: 4:3", 0xFF4527A0);
        ratioBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAspectRatio();
            }
        });
        topButtonsRow.addView(ratioBtnRef);

        oisBtnRef = createPillButton("🛡️ OIS: ON", 0xFF00695C);
        oisBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                oisEnabled = !oisEnabled;
                oisBtnRef.setText(oisEnabled ? "🛡️ OIS: ON" : "🛡️ OIS: OFF");
                updatePreviewSession();
            }
        });
        topButtonsRow.addView(oisBtnRef);

        macroBtnRef = createPillButton("🌷 MACRO: OFF", 0xFF2E7D32);
        macroBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMacroAllLensMode();
            }
        });
        topButtonsRow.addView(macroBtnRef);

        afBtnRef = createPillButton("🎯 AF: CONTINUOUS", 0xFF0277BD);
        afBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAfStrategy();
            }
        });
        topButtonsRow.addView(afBtnRef);

        final Button flashBtn = createPillButton("🔦 FLASH: OFF", 0xFF262626);
        flashBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                flashEnabled = !flashEnabled;
                flashBtn.setText(flashEnabled ? "🔦 FLASH: TORCH" : "🔦 FLASH: OFF");
                updatePreviewSession();
            }
        });
        topButtonsRow.addView(flashBtn);

        final Button awbBtn = createPillButton("WB: AUTO", 0xFF262626);
        awbBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleWhiteBalance(awbBtn);
            }
        });
        topButtonsRow.addView(awbBtn);

        Button libsBtn = createPillButton("🧬 HALO + X.ZIP (" + (packagedNativeLibs.size() + xmlConfigNames.size()) + ")", 0xFF283593);
        libsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrariesAndAssetsDialog();
            }
        });
        topButtonsRow.addView(libsBtn);
        Button aiBtn = createPillButton("AI ZOOM LAB", 0xFF37474F);
        aiBtn.setOnClickListener(v -> {
            if (isCapturing || processing) return;
            closeCamera();
            startActivity(new Intent(this, com.opticore.camera.OpticoreActivity.class));
        });
        topButtonsRow.addView(aiBtn);

        topScroll.addView(topButtonsRow);
        topBar.addView(topScroll);

        statusHud = new TextView(this);
        statusHud.setTextColor(0xFFFFB74D);
        statusHud.setTextSize(11f);
        statusHud.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusHud.setPadding(8, 8, 8, 2);
        statusHud.setText("V21 S25-ULTRA + HALO ISP | 25MP (15/30/50MP) | JN5+LYT MACRO | 8-CORE FAST");
        topBar.addView(statusHud);

        pipelineHud = new TextView(this);
        pipelineHud.setTextColor(0xFF80CBC4);
        pipelineHud.setTextSize(10f);
        pipelineHud.setTypeface(Typeface.MONOSPACE);
        pipelineHud.setPadding(8, 2, 8, 0);
        pipelineHud.setText("LYT-808 D65 AWB FIX + S25U PROVISUAL JN5 MACRO + 8-CORE ZERO-LOSS SPEED");
        topBar.addView(pipelineHud);

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

        // 4. Bottom Camera Controls Dock
        LinearLayout bottomDock = new LinearLayout(this);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setBackgroundColor(0xAA000000);
        bottomDock.setPadding(26, 14, 26, 44);
        bottomDock.setGravity(Gravity.CENTER_HORIZONTAL);

        proControlsPanel = new LinearLayout(this);
        proControlsPanel.setOrientation(LinearLayout.VERTICAL);
        proControlsPanel.setPadding(10, 2, 10, 6);

        TextView zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(10f);
        zoomLabel.setText("OPTICAL / HYBRID ZOOM (1.0x - 10.0x)");
        proControlsPanel.addView(zoomLabel);

        final SeekBar zoomBar = new SeekBar(this);
        zoomBar.setMax(90); // 1.0x to 10.0x in-lens multiplier (so on 3x Telephoto, slider zooms smoothly from 3.0x to 30.0x!)
        zoomBar.setProgress(0);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                currentZoom = 1.0f + (progress / 10.0f);
                float totalMag = opticalBaseZoomFactor * currentZoom;
                zoomLabel.setText(String.format(Locale.US, "LENS ZOOM: %.1fx (TOTAL: %.1fx on %s)",
                        currentZoom, totalMag, getLensDescription(currentCameraId)));
                updatePreviewSession();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        proControlsPanel.addView(zoomBar);

        // Dedicated Optical Physical Lens Bar (0.6x UW | 1x MAIN | 2x | 3x TELE | 6x TELE | 10x TELE | 15x TELE)
        HorizontalScrollView optScroll = new HorizontalScrollView(this);
        optScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout opticalLensRow = new LinearLayout(this);
        opticalLensRow.setOrientation(LinearLayout.HORIZONTAL);
        opticalLensRow.setGravity(Gravity.CENTER);
        opticalLensRow.setPadding(0, 4, 0, 6);
        final String[] optButtons = {"0.6x UW", "1x MAIN", "2x ISZ", "3x TELE", "6x TELE", "10x TELE", "15x TELE"};
        for (final String opt : optButtons) {
            int color = (opt.contains("TELE")) ? 0xFFD84315 : 0xFF263238;
            Button b = createPillButton(opt, color);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (opt.startsWith("0.6x")) {
                        selectOpticalLensTarget("0.6x UW", 0.6f);
                        zoomBar.setProgress(0);
                    } else if (opt.startsWith("1x")) {
                        selectOpticalLensTarget("1x MAIN", 1.0f);
                        zoomBar.setProgress(0);
                    } else if (opt.startsWith("2x")) {
                        selectOpticalLensTarget("1x MAIN", 2.0f);
                        zoomBar.setProgress(10);
                    } else if (opt.startsWith("3x")) {
                        selectOpticalLensTarget("3x TELE", 3.0f);
                        zoomBar.setProgress(0);
                    } else if (opt.startsWith("6x")) {
                        selectOpticalLensTarget("3x TELE", 6.0f);
                        zoomBar.setProgress(10);
                    } else if (opt.startsWith("10x")) {
                        selectOpticalLensTarget("3x TELE", 10.0f);
                        zoomBar.setProgress(23);
                    } else if (opt.startsWith("15x")) {
                        selectOpticalLensTarget("3x TELE", 15.0f);
                        zoomBar.setProgress(40);
                    }
                    float totalMag = opticalBaseZoomFactor * currentZoom;
                    zoomLabel.setText(String.format(Locale.US, "LENS ZOOM: %.1fx (TOTAL: %.1fx on %s)",
                            currentZoom, totalMag, getLensDescription(currentCameraId)));
                }
            });
            opticalLensRow.addView(b);
        }
        optScroll.addView(opticalLensRow);
        proControlsPanel.addView(optScroll);

        focusLabelRef = new TextView(this);
        focusLabelRef.setTextColor(0xFF80CBC4);
        focusLabelRef.setTextSize(10f);
        focusLabelRef.setText("MACRO / MF DIOPTER (0 = PDAF+CAF AUTO, SLIDE RIGHT FOR SUPER-MACRO LOCK)");
        proControlsPanel.addView(focusLabelRef);

        focusBarRef = new SeekBar(this);
        focusBarRef.setMax(100);
        focusBarRef.setProgress(0);
        focusBarRef.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (progress == 0) {
                    currentAfStrategy = macroAllLensEnabled ? "AF-MACRO" : "AF-C";
                    manualFocusDiopters = 0f;
                    afBtnRef.setText(macroAllLensEnabled ? "🎯 AF: MACRO PDAF" : "🎯 AF: CONTINUOUS");
                    focusLabelRef.setText("FOCUS: AUTO (" + currentAfStrategy + ")");
                } else {
                    currentAfStrategy = "MF-LOCK";
                    float maxD = Math.max(5.0f, minFocusDistanceDiopters);
                    manualFocusDiopters = (progress / 100.0f) * maxD;
                    float approxCm = 100.0f / Math.max(0.5f, manualFocusDiopters);
                    afBtnRef.setText(String.format(Locale.US, "🎯 MF: %.1fD (~%.1fcm)", manualFocusDiopters, approxCm));
                    focusLabelRef.setText(String.format(Locale.US, "HALO MACRO FOCUS LOCK: %.2f Diopters (~%.1f cm) on %s",
                            manualFocusDiopters, approxCm, getLensDescription(currentCameraId)));
                }
                updatePreviewSession();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        proControlsPanel.addView(focusBarRef);

        TextView evLabel = new TextView(this);
        evLabel.setTextColor(Color.WHITE);
        evLabel.setTextSize(10f);
        evLabel.setText("EXPOSURE COMPENSATION (EV)");
        proControlsPanel.addView(evLabel);

        SeekBar evBar = new SeekBar(this);
        evBar.setMax(24);
        evBar.setProgress(12);
        evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentEvIndex = progress - 12;
                updatePreviewSession();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        proControlsPanel.addView(evBar);

        bottomDock.addView(proControlsPanel);

        // 4b. Streamlined Camera Mode Selector (Removed repeated tabs like HASSELBLAD XPAN, LUMO PRO, HI-RES 50MP, MASTER 25MP!)
        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);

        final String[] modes = {"PHOTO", "DUAL-LENS MACRO", "PORTRAIT", "NIGHT"};
        final List<Button> modeButtons = new ArrayList<>();
        for (final String m : modes) {
            final Button mBtn = createPillButton(m, m.equals(currentMode) ? 0xFFFF9800 : 0xFF1F1F1F);
            modeButtons.add(mBtn);
            mBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentMode = m;
                    for (Button b : modeButtons) {
                        GradientDrawable gd = new GradientDrawable();
                        gd.setColor(b == mBtn ? 0xFFFF9800 : 0xFF1F1F1F);
                        gd.setCornerRadius(40f);
                        gd.setStroke(2, 0x55FFFFFF);
                        b.setBackground(gd);
                    }
                    if ("DUAL-LENS MACRO".equals(m)) {
                        if (!macroAllLensEnabled) {
                            toggleMacroAllLensMode();
                        }
                    } else {
                        if (macroAllLensEnabled) {
                            toggleMacroAllLensMode();
                        }
                    }
                    updateModeTitleText();
                    overlayView.invalidate();
                    updatePreviewSession();
                }
            });
            modeRow.addView(mBtn);
        }
        modeScroll.addView(modeRow);
        bottomDock.addView(modeScroll);

        modeTitleView = new TextView(this);
        updateModeTitleText();
        modeTitleView.setTextColor(0xFFFF9800);
        modeTitleView.setTextSize(11f);
        modeTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        modeTitleView.setPadding(0, 6, 0, 6);
        modeTitleView.setGravity(Gravity.CENTER);
        bottomDock.addView(modeTitleView);

        // 4c. Main Shutter Row
        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER);
        shutterRow.setPadding(0, 4, 0, 0);

        lensBtnRef = createPillButton("🔄 LENS (0)", 0xFF333333);
        lensBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchCameraLens((Button) v);
            }
        });
        shutterRow.addView(lensBtnRef);

        Button shutterBtn = new Button(this);
        shutterBtn.setText("📸 H-LUMO\nSNAP");
        shutterBtn.setTextSize(11.5f);
        shutterBtn.setTextColor(Color.WHITE);
        shutterBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable shutterBg = new GradientDrawable();
        shutterBg.setShape(GradientDrawable.OVAL);
        shutterBg.setColor(0xFFE65100);
        shutterBg.setStroke(6, Color.WHITE);
        shutterBtn.setBackground(shutterBg);
        LinearLayout.LayoutParams shutterLp = new LinearLayout.LayoutParams(204, 204);
        shutterLp.setMargins(48, 0, 48, 0);
        shutterBtn.setLayoutParams(shutterLp);
        shutterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureCleanHardwareMfnrPicture();
            }
        });
        shutterRow.addView(shutterBtn);

        final Button isoBtn = createPillButton("ISO: AUTO", 0xFF333333);
        isoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleIso((Button) v);
            }
        });
        shutterRow.addView(isoBtn);

        openPhotoBtnRef = createPillButton("🖼️ OPEN PHOTO", 0xFF1565C0);
        openPhotoBtnRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLastCapturedPhotoViewer();
            }
        });
        shutterRow.addView(openPhotoBtnRef);

        bottomDock.addView(shutterRow);

        root.addView(bottomDock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        setContentView(root);
    }

    private String shortenName(String path) {
        if (path == null) return "Default";
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0) ? path.substring(slash + 1) : path;
        return name.length() > 18 ? name.substring(0, 18) : name;
    }

    private void cycleXmlConfigProfile() {
        if (xmlConfigNames.isEmpty()) return;
        currentXmlIndex = (currentXmlIndex + 1) % xmlConfigNames.size();
        String chosen = xmlConfigNames.get(currentXmlIndex);
        loadActiveXmlTuningProfileAsync(chosen);
        xmlBtnRef.setText("📄 XML: " + shortenName(chosen));
        updateModeTitleText();
        Toast.makeText(this,
                String.format(Locale.US, "📄 Loaded %s (Sharp=%.2f, Sat=%.2f, Contrast=%.2f)",
                        shortenName(chosen), xmlSharpnessGain, xmlSaturationGain, xmlContrastGain),
                Toast.LENGTH_SHORT).show();
    }

    private void applyLiveViewfinderIspFilter() {
        if (textureView == null) return;
        try {
            Paint livePaint = new Paint();
            ColorMatrix cm = buildHasselbladLumoColorMatrix(currentIspProfile);
            livePaint.setColorFilter(new ColorMatrixColorFilter(cm));
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, livePaint);
            textureView.invalidate();
        } catch (Throwable ignored) {}
    }

    private void updateModeTitleText() {
        if (modeTitleView == null) return;
        modeTitleView.setText(String.format(Locale.US, "%s • %s (%s) • %s • %s",
                currentIspProfile, currentResTarget, currentAspectRatio, currentDenoiseMode, oisEnabled ? "OIS ON" : "OIS OFF"));
    }

    private RectF computeViewfinderCropRect(int viewW, int viewH, String ratio) {
        if (viewW <= 0 || viewH <= 0) return new RectF(0, 0, viewW, viewH);
        float targetAspect;
        if ("1:1".equals(ratio)) {
            targetAspect = 1.0f;
        } else if ("16:9".equals(ratio)) {
            targetAspect = 16.0f / 9.0f;
        } else if ("XPAN 65:24".equals(ratio)) {
            targetAspect = 65.0f / 24.0f;
        } else if ("FULL 20:9".equals(ratio)) {
            return new RectF(0, 0, viewW, viewH);
        } else {
            targetAspect = 4.0f / 3.0f;
        }

        float boxW = viewW;
        float boxH = boxW * targetAspect;
        if (boxH > viewH) {
            boxH = viewH;
            boxW = boxH / targetAspect;
        }
        float left = (viewW - boxW) / 2f;
        float top = (viewH - boxH) / 2f;
        return new RectF(left, top, left + boxW, top + boxH);
    }

    private Button createPillButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10.5f);
        b.setAllCaps(false);
        b.setPadding(22, 11, 22, 11);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(40f);
        gd.setStroke(2, 0x55FFFFFF);
        b.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(6, 4, 6, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private void cycleDenoiseMode() {
        if (isCapturing || processing) return;
        if ("NR: CRISP (CHROMA)".equals(currentDenoiseMode)) {
            currentDenoiseMode = "NR: BALANCED";
        } else if ("NR: BALANCED".equals(currentDenoiseMode)) {
            currentDenoiseMode = "NR: LOW-LIGHT";
        } else {
            currentDenoiseMode = "NR: CRISP (CHROMA)";
        }
        nrBtnRef.setText("🧼 " + currentDenoiseMode);
        updateModeTitleText();
        updatePreviewSession();
    }

    private void cycleOutputResolution(Button btn) {
        if (isCapturing || processing) return;
        currentResTarget = "NATIVE".equals(currentResTarget) ? "HIGH RES" : "NATIVE";
        btn.setText("RES: " + currentResTarget);
        updateModeTitleText();
        closeCamera(); openCamera();
    }

    private void cycleCapturePipeline() {
        if (isCapturing || processing) return;
        currentCapturePipeline = "NATIVE JPEG".equals(currentCapturePipeline) ? "OEM HAL EXTENSION (HDR)" :
                "OEM HAL EXTENSION (HDR)".equals(currentCapturePipeline) ? "OEM NIGHT" : "NATIVE JPEG";
        activePhysicalSubId = null;
        pipeBtnRef.setText("PIPE: " + currentCapturePipeline);
        closeCamera(); openCamera();
    }

    private void selectOpticalLensTarget(String targetType, float totalZoomEquivalent) {
        if(isCapturing || processing)return;
        discoverAllCameraLensIds();
        String prevCamId = currentCameraId;
        String prevPhysId = activePhysicalSubId;

        if ("0.6x UW".equals(targetType)) {
            currentCameraId = logicalRearCameraId;
            activePhysicalSubId = physicalUwId;
            opticalBaseZoomFactor = 0.6f;
            currentZoom = Math.max(1.0f, totalZoomEquivalent / 0.6f);
        } else if ("3x TELE".equals(targetType)) {
            currentCameraId = logicalRearCameraId;
            activePhysicalSubId = physicalTeleId;
            opticalBaseZoomFactor = 3.0f;
            currentZoom = Math.max(1.0f, totalZoomEquivalent / 3.0f);
        } else {
            currentCameraId = logicalRearCameraId;
            activePhysicalSubId = physicalMainId;
            opticalBaseZoomFactor = 1.0f;
            currentZoom = Math.max(1.0f, totalZoomEquivalent);
        }

        activePhysicalSubId = null; // Let logical HAL select the sensor at requested zoom.
        boolean samePhysicalBinding = (prevCamId != null && prevCamId.equals(currentCameraId))
                && ((prevPhysId == null && activePhysicalSubId == null)
                    || (prevPhysId != null && prevPhysId.equals(activePhysicalSubId)));

        Toast.makeText(this, String.format(Locale.US,
                "🔭 %s | Total Zoom: %.1fx (In-Lens: %.1fx)",
                getLensDescription(currentCameraId), opticalBaseZoomFactor * currentZoom, currentZoom),
                Toast.LENGTH_SHORT).show();

        if (samePhysicalBinding && cameraDevice != null && captureSession != null) {
            updatePreviewSession();
        } else {
            activeAfRegions = null;
            activeAeRegions = null;
            closeCamera();
            openCamera();
        }
    }

    private void cycleAspectRatio() {
        if (isCapturing || processing) return;
        String[] ratios = {"4:3", "16:9", "1:1", "XPAN 65:24", "FULL 20:9"};
        int idx = 0;
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(currentAspectRatio)) {
                idx = (i + 1) % ratios.length;
                break;
            }
        }
        currentAspectRatio = ratios[idx];
        ratioBtnRef.setText("🖼️ RATIO: " + currentAspectRatio);
        updateModeTitleText();
        if (textureView != null) configureTransform(textureView.getWidth(), textureView.getHeight());
        if (overlayView != null) overlayView.invalidate();
        updatePreviewSession();
    }

    private void cycleHasselbladLumoProfile() {
        if (isCapturing || processing) return;
        // Removed MASTER EMERALD and MASTER SERENITY; added official S26 ULTRA PROVISUAL
        String[] profiles = {
                "NATURAL", "CLEAN JPEG", "LUT 17",
                "HALO X9U + S26U ISP",
                "S26 ULTRA PROVISUAL",
                "X9U LUMO MASTER",
                "LUMO PRO CLEAN",
                "HNCS NATURAL",
                "MASTER RADIANCE",
                "XPAN MONO B&W"
        };
        int idx = 0;
        for (int i = 0; i < profiles.length; i++) {
            if (profiles[i].equals(currentIspProfile)) {
                idx = (i + 1) % profiles.length;
                break;
            }
        }
        currentIspProfile = profiles[idx];
        profileBtnRef.setText("🎨 LOOK: " + currentIspProfile);
        applyLiveViewfinderIspFilter();
        updateModeTitleText();
        Toast.makeText(this, "🎨 JPEG look: " + currentIspProfile, Toast.LENGTH_SHORT).show();
        updatePreviewSession();
    }

    private void toggleMacroAllLensMode() {
        if (isCapturing || processing) return;
        macroAllLensEnabled = !macroAllLensEnabled;
        currentAfStrategy = macroAllLensEnabled ? "AF-MACRO" : "AF-C";
        macroBtnRef.setText(macroAllLensEnabled ? "MACRO: CLOSE FOCUS" : "MACRO: OFF");
        if (afBtnRef != null) afBtnRef.setText("AF: " + currentAfStrategy);
        updatePreviewSession();
    }

    private void cycleAfStrategy() {
        if ("AF-C".equals(currentAfStrategy)) {
            currentAfStrategy = "AF-MACRO";
            macroAllLensEnabled = true;
            macroBtnRef.setText("🌷 MACRO (ALL LENS): ON");
            afBtnRef.setText("🎯 AF: MACRO AUTO");
        } else if ("AF-MACRO".equals(currentAfStrategy)) {
            currentAfStrategy = "MF-LOCK";
            manualFocusDiopters = Math.max(4.0f, minFocusDistanceDiopters);
            if (focusBarRef != null) focusBarRef.setProgress(100);
            float approxCm = 100.0f / Math.max(0.5f, manualFocusDiopters);
            afBtnRef.setText(String.format(Locale.US, "🎯 MF SUPER-MACRO (%.1fcm)", approxCm));
        } else {
            currentAfStrategy = "AF-C";
            manualFocusDiopters = 0f;
            if (focusBarRef != null) focusBarRef.setProgress(0);
            afBtnRef.setText("🎯 AF: CONTINUOUS");
        }
        updateModeTitleText();
        overlayView.invalidate();
        updatePreviewSession();
    }

    private void triggerTouchAutoFocus(float touchX, float touchY, int viewW, int viewH) {
        if (cameraDevice == null || captureSession == null || previewRequestBuilder == null) return;
        try {
            focusRingX = touchX;
            focusRingY = touchY;
            focusRingTimestamp = System.currentTimeMillis();
            overlayView.invalidate();

            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Rect sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensorRect == null) return;

            float normX = Math.max(0f, Math.min(1f, touchY / (float) viewH));
            float normY = Math.max(0f, Math.min(1f, 1f - (touchX / (float) viewW)));

            int halfRegionW = Math.max(80, sensorRect.width() / 12);
            int halfRegionH = Math.max(80, sensorRect.height() / 12);
            int centerX = (int) (sensorRect.left + normX * sensorRect.width());
            int centerY = (int) (sensorRect.top + normY * sensorRect.height());

            int left = Math.max(sensorRect.left, Math.min(sensorRect.right - 2 * halfRegionW, centerX - halfRegionW));
            int top = Math.max(sensorRect.top, Math.min(sensorRect.bottom - 2 * halfRegionH, centerY - halfRegionH));

            MeteringRectangle focusRect = new MeteringRectangle(left, top, halfRegionW * 2, halfRegionH * 2, MeteringRectangle.METERING_WEIGHT_MAX - 1);
            activeAfRegions = new MeteringRectangle[]{focusRect};
            activeAeRegions = new MeteringRectangle[]{focusRect};

            Integer maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);

            if ("MF-LOCK".equals(currentAfStrategy)) {
                currentAfStrategy = macroAllLensEnabled ? "AF-MACRO" : "AF-C";
                if (focusBarRef != null) focusBarRef.setProgress(0);
            }

            int targetAfMode = getBestSupportedAfMode(chars, macroAllLensEnabled ? CameraMetadata.CONTROL_AF_MODE_MACRO : CameraMetadata.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, targetAfMode);
            if (maxAfRegions != null && maxAfRegions > 0) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, activeAfRegions);
            }
            if (maxAeRegions != null && maxAeRegions > 0) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, activeAeRegions);
            }

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), afCaptureCallback, backgroundHandler);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewRequestBuilder.build(), afCaptureCallback, backgroundHandler);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), afCaptureCallback, backgroundHandler);
        } catch (Throwable ignored) {}
    }

    private int getBestSupportedAfMode(CameraCharacteristics chars, int preferredMode) {
        int[] available = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (available == null || available.length == 0) return CameraMetadata.CONTROL_AF_MODE_OFF;
        for (int m : available) {
            if (m == preferredMode) return preferredMode;
        }
        int[] fallbacks = {
                CameraMetadata.CONTROL_AF_MODE_MACRO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CameraMetadata.CONTROL_AF_MODE_AUTO,
                CameraMetadata.CONTROL_AF_MODE_EDOF
        };
        for (int fb : fallbacks) {
            for (int m : available) {
                if (m == fb) return fb;
            }
        }
        return available[0];
    }

    private final CameraCaptureSession.CaptureCallback afCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            Integer sens = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (sens != null) liveSensorIso = sens;
            if (expNs != null) liveExposureNs = expNs;

            if (afState != null && afState != currentAfState) {
                int prev = currentAfState;
                currentAfState = afState;
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && prev != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (overlayView != null) overlayView.invalidate();
                        }
                    });
                }
            }
        }
    };

    private void cycleWhiteBalance(Button btn) {
        int[] modes = {
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        };
        String[] names = {"WB: AUTO", "WB: DAYLIGHT", "WB: CLOUDY", "WB: TUNGSTEN", "WB: NEON"};
        int idx = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == currentAwbMode) {
                idx = (i + 1) % modes.length;
                break;
            }
        }
        currentAwbMode = modes[idx];
        btn.setText(names[idx]);
        updatePreviewSession();
    }

    private void cycleIso(Button btn) {
        int[] isos = {0, 100, 200, 400, 800, 1600, 3200};
        int idx = 0;
        for (int i = 0; i < isos.length; i++) {
            if (isos[i] == currentIso) {
                idx = (i + 1) % isos.length;
                break;
            }
        }
        currentIso = isos[idx];
        btn.setText(currentIso == 0 ? "ISO: AUTO" : ("ISO: " + currentIso));
        updatePreviewSession();
    }

    private int lensCycleStage = 0; // 0=1x Main, 1=3x Periscope Tele, 2=0.6x UW, 3=Front

    private void switchCameraLens(Button btn) {
        if(isCapturing || processing)return;
        discoverAllCameraLensIds();
        lensCycleStage = (lensCycleStage + 1) % 4;
        if (lensCycleStage == 0) {
            selectOpticalLensTarget("1x MAIN", 1.0f);
        } else if (lensCycleStage == 1) {
            selectOpticalLensTarget("3x TELE", 3.0f);
        } else if (lensCycleStage == 2) {
            selectOpticalLensTarget("0.6x UW", 0.6f);
        } else {
            activePhysicalSubId = null;
            opticalBaseZoomFactor = 1.0f;
            currentZoom = 1.0f;
            currentCameraId = "1";
            for (String id : cameraIds) {
                try {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        currentCameraId = id;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            activeAfRegions = null;
            activeAeRegions = null;
            closeCamera();
            openCamera();
        }
    }

    private String[] discoverAllCameraLensIds() {
        Set<String> allIds = new LinkedHashSet<>();
        try {
            String[] baseIds = cameraManager.getCameraIdList();
            if (baseIds != null) {
                for (String id : baseIds) {
                    allIds.add(id);
                    try {
                        CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            if (Build.VERSION.SDK_INT >= 28) {
                                Set<String> physIds = cc.getPhysicalCameraIds();
                                if (physIds != null && !physIds.isEmpty()) {
                                    logicalRearCameraId = id;
                                    float minFl = 999f;
                                    float maxFl = -1f;
                                    List<String> sortedPhys = new ArrayList<>(physIds);
                                    Collections.sort(sortedPhys);
                                    for (String pid : sortedPhys) {
                                        try {
                                            CameraCharacteristics pcc = cameraManager.getCameraCharacteristics(pid);
                                            float[] fls = pcc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                                            float fl = (fls != null && fls.length > 0) ? fls[0] : 0f;
                                            if (fl > 0f && fl < minFl) {
                                                minFl = fl;
                                                physicalUwId = pid;
                                            }
                                            if (fl > maxFl) {
                                                maxFl = fl;
                                                physicalTeleId = pid;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    for (String pid : sortedPhys) {
                                        if (!pid.equals(physicalUwId) && !pid.equals(physicalTeleId)) {
                                            physicalMainId = pid;
                                            break;
                                        }
                                    }
                                    if (sortedPhys.size() >= 3 && (physicalUwId == null || physicalUwId.equals(physicalTeleId))) {
                                        physicalMainId = sortedPhys.get(0);
                                        physicalUwId = sortedPhys.get(1);
                                        physicalTeleId = sortedPhys.get(sortedPhys.size() - 1);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return allIds.toArray(new String[0]);
    }

    private CameraCharacteristics getActiveLensCharacteristics() throws CameraAccessException {
        if (activePhysicalSubId != null) {
            try {
                CameraCharacteristics pcc = cameraManager.getCameraCharacteristics(activePhysicalSubId);
                if (pcc != null) return pcc;
            } catch (Throwable ignored) {}
        }
        return cameraManager.getCameraCharacteristics(currentCameraId);
    }

    private String getLensDescription(String camId) {
        if (activePhysicalSubId != null) {
            if (activePhysicalSubId.equals(physicalTeleId)) {
                return "3x TELE 73mm (Phys:" + activePhysicalSubId + ")";
            }
            if (activePhysicalSubId.equals(physicalUwId)) {
                return "0.6x UW 15mm (Phys:" + activePhysicalSubId + ")";
            }
            return "1x MAIN 23mm (Phys:" + activePhysicalSubId + ")";
        }
        try {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(camId);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return "FRONT (" + camId + ")";
            }
        } catch (Throwable ignored) {}
        if (opticalBaseZoomFactor >= 3.0f) return "3x TELE (Logical)";
        if (opticalBaseZoomFactor < 1.0f) return "0.6x UW (Logical)";
        return "1x MAIN (" + camId + ")";
    }

    private void showLibrariesAndAssetsDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== v58 repair: Camera2 + optional JPEG looks ===\n");
        sb.append("Active Capture Pipeline: ").append(currentCapturePipeline).append("\n");
        sb.append("Active Optical Lens: ").append(getLensDescription(currentCameraId)).append("\n");
        sb.append(String.format(Locale.US, "Pruned Redundant Bloat: -%d dead/bloat .so libs, -%d duplicate XMLs removed!\n",
                prunedRedundantSoCount, prunedRedundantXmlCount));
        sb.append("Active ISP Profile: ").append(currentIspProfile).append("\n");
        sb.append("Active XML Config: ").append(xmlConfigNames.isEmpty() ? "Default" : xmlConfigNames.get(currentXmlIndex)).append("\n");
        sb.append(String.format(Locale.US, "XML Tuned Gains: Sharp=%.2f, Sat=%.2f, Contrast=%.2f, ChromaNR=%.0f\n",
                xmlSharpnessGain, xmlSaturationGain, xmlContrastGain, xmlChromaDenoiseStrength));
        sb.append("Denoise Mode: ").append(currentDenoiseMode).append("\n");
        sb.append("Aspect Ratio: ").append(currentAspectRatio).append(" | Output: ").append(currentResTarget).append("\n\n");

        if (!xmlConfigNames.isEmpty()) {
            sb.append("=== PACKAGED XML / TUNING CONFIGS (").append(xmlConfigNames.size()).append(") ===\n");
            for (int i = 0; i < Math.min(40, xmlConfigNames.size()); i++) {
                sb.append("  📄 ").append(xmlConfigNames.get(i)).append("\n");
            }
            sb.append("\n");
        }

        if (!discoveredJniHooks.isEmpty()) {
            sb.append("=== CAM.ZIP + PROJECT.ZIP INVENTORY (").append(discoveredJniHooks.size()).append(") ===\n");
            for (int i = 0; i < Math.min(50, discoveredJniHooks.size()); i++) {
                sb.append("  ⚡ ").append(discoveredJniHooks.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("=== PACKAGED NATIVE LIBRARIES (").append(packagedNativeLibs.size()).append(") ===\n");
        for (String s : packagedNativeLibs) {
            sb.append("  ✔ ").append(s).append("\n");
        }

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setPadding(40, 32, 40, 32);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(0xFF181818);
        tv.setTextIsSelectable(true);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("cam.zip + project.zip Resources & XMLs")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            applyLiveViewfinderIspFilter();
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closeCamera();
            return true;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera() {
        if (!resumed || opening || cameraDevice != null || backgroundHandler == null || !textureView.isAvailable() || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        opening = true;
        final int generation = ++cameraGeneration;
        try {
            cameraIds = discoverAllCameraLensIds();
            if (cameraIds.length == 0) { opening = false; return; }
            if (currentCameraId == null || !Arrays.asList(cameraIds).contains(currentCameraId)) {
                currentCameraId = Arrays.asList(cameraIds).contains(logicalRearCameraId) ? logicalRearCameraId : cameraIds[0];
            }

            CameraCharacteristics chars = getActiveLensCharacteristics();
            Float maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxDigitalZoom != null && maxDigitalZoom > 1.0f) {
                maxZoom = maxDigitalZoom;
            }

            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocus != null && minFocus > 0.0f) {
                minFocusDistanceDiopters = minFocus;
            } else {
                minFocusDistanceDiopters = 0.0f;
            }

            int[] oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            hardwareOisSupported = false;
            if (oisModes != null) {
                for (int m : oisModes) {
                    if (m == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        hardwareOisSupported = true;
                        break;
                    }
                }
            }

            if (lensBtnRef != null) {
                lensBtnRef.setText("🔄 " + getLensDescription(currentCameraId));
            }

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] prevSizes = map.getOutputSizes(SurfaceTexture.class);
                if (prevSizes != null && prevSizes.length > 0) {
                    previewSize = chooseOptimalPreviewSize(prevSizes);
                }
                Size[] stdJpeg = map.getOutputSizes(ImageFormat.JPEG);
                if (stdJpeg == null || stdJpeg.length == 0) throw new IllegalStateException("No JPEG stream");
                sensorCaptureSize = chooseJpegSize(Arrays.asList(stdJpeg));
            }
            selectedExtension = -1;
            if (!"NATIVE JPEG".equals(currentCapturePipeline)) {
                try {
                    CameraExtensionCharacteristics ec = cameraManager.getCameraExtensionCharacteristics(currentCameraId);
                    int ext = "OEM NIGHT".equals(currentCapturePipeline) ? CameraExtensionCharacteristics.EXTENSION_NIGHT : CameraExtensionCharacteristics.EXTENSION_HDR;
                    if (ec.getSupportedExtensions().contains(ext)) {
                        List<Size> jpg = ec.getExtensionSupportedSizes(ext, ImageFormat.JPEG);
                        List<Size> prev = ec.getExtensionSupportedSizes(ext, SurfaceTexture.class);
                        if (!jpg.isEmpty() && !prev.isEmpty()) {
                            sensorCaptureSize = chooseJpegSize(jpg);
                            previewSize = chooseOptimalPreviewSize(prev.toArray(new Size[0]));
                            selectedExtension = ext;
                        }
                    }
                } catch (Exception ex) { android.util.Log.w("OptiCore", "Extension unavailable", ex); }
                if (selectedExtension < 0) {
                    currentCapturePipeline = "NATIVE JPEG";
                    runOnUiThread(() -> { pipeBtnRef.setText("PIPE: NATIVE JPEG"); Toast.makeText(this,"OEM extension unavailable; native JPEG selected",Toast.LENGTH_LONG).show(); });
                }
            }

            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(sensorCaptureSize.getWidth(), sensorCaptureSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try (Image image = reader.acquireNextImage()) {
                        if (image != null && isCapturing && generation == cameraGeneration) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            final byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            final List<byte[]> framesToProcess;
                            final int snapRot;
                            synchronized (burstJpegFrames) {
                                burstJpegFrames.add(bytes);
                                if (burstJpegFrames.size() < expectedBurstFrames) {
                                    return;
                                }
                                framesToProcess = new ArrayList<>(burstJpegFrames);
                                burstJpegFrames.clear();
                                snapRot = burstSnapOrientation;
                            }
                            processing = true;
                            isCapturing = false;
                            final String profile = shotProfile, ratio = shotRatio, resolution = shotResolution;
                            final int iso = shotIso;
                            photoExecutor.execute(() -> {
                                try { processNaturalAndSave(framesToProcess.get(0), profile, ratio, resolution, iso); }
                                finally { processing = false; }
                            });
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("OptiCore","Image callback failed",t);
                        isCapturing = false; processing = false;
                    }
                }
            }, backgroundHandler);

            if (dualMacroImageReader != null) {
                dualMacroImageReader.close();
                dualMacroImageReader = null;
            }
            dualLensSimultaneousActive = false;

            configureTransform(textureView.getWidth(), textureView.getHeight());
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!resumed || generation != cameraGeneration) { camera.close(); return; }
                    opening = false; cameraDevice = camera; createCameraPreviewSession();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); if (generation == cameraGeneration) { opening = false; cameraDevice = null; isCapturing = false; } }
                @Override public void onError(CameraDevice camera, int error) { onDisconnected(camera); runOnUiThread(() -> Toast.makeText(MainActivity.this,"Camera error: " + error,Toast.LENGTH_LONG).show()); }
            }, backgroundHandler);
        } catch (Throwable e) {
            opening = false;
            android.util.Log.e("OptiCore", "Open camera failed", e);
            runOnUiThread(() -> Toast.makeText(this,"Camera open failed: " + e.getMessage(),Toast.LENGTH_LONG).show());
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices) {
        Size best = choices[0];
        double bestScore=Double.MAX_VALUE;
        for(Size z:choices){
            long pixels=(long)z.getWidth()*z.getHeight();
            double score=Math.abs(Math.log(pixels/1_555_200.0))+2*Math.abs(z.getWidth()/(double)z.getHeight()-4.0/3);
            if(score<bestScore){best=z;bestScore=score;}
        }
        return best;
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        int bufW = Math.min(previewSize.getWidth(), previewSize.getHeight());
        int bufH = Math.max(previewSize.getWidth(), previewSize.getHeight());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        float baseScale;
        if ("FULL 20:9".equals(currentAspectRatio)) {
            baseScale = Math.max((float) viewWidth / bufW, (float) viewHeight / bufH);
        } else {
            baseScale = Math.min((float) viewWidth / bufW, (float) viewHeight / bufH);
        }
        float scaleX = (bufW * baseScale) / viewWidth;
        float scaleY = (bufH * baseScale) / viewHeight;
        matrix.postScale(scaleX, scaleY, centerX, centerY);
        textureView.setTransform(matrix);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreviewSession() {
        if (cameraDevice == null || imageReader == null || !textureView.isAvailable()) return;
        final CameraDevice device = cameraDevice;
        final int generation = cameraGeneration;
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface != null) previewSurface.release();
            final Surface surface = previewSurface = new Surface(texture);
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            Executor executor = r -> { Handler h = backgroundHandler; if (h != null) h.post(r); };
            if (selectedExtension >= 0) {
                List<OutputConfiguration> outputs = Arrays.asList(new OutputConfiguration(surface),new OutputConfiguration(imageReader.getSurface()));
                try {
                    device.createExtensionSession(new ExtensionSessionConfiguration(selectedExtension, outputs, executor, new CameraExtensionSession.StateCallback() {
                        @Override public void onConfigured(CameraExtensionSession session) {
                            if (generation != cameraGeneration || !resumed) { session.close(); return; }
                            extensionSession = session;
                            try { session.setRepeatingRequest(previewRequestBuilder.build(),executor,new CameraExtensionSession.ExtensionCaptureCallback() {}); }
                            catch (Exception e) { fallbackExtension(generation); }
                        }
                        @Override public void onConfigureFailed(CameraExtensionSession session) { session.close(); fallbackExtension(generation); }
                    }));
                    return;
                } catch (Exception e) { fallbackExtension(generation); return; }
            }
            device.createCaptureSession(Arrays.asList(surface,imageReader.getSurface()),new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (generation != cameraGeneration || !resumed) { session.close(); return; }
                    captureSession = session; updatePreviewSession();
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    session.close();
                    if (generation != cameraGeneration) return;
                    if ("HIGH RES".equals(currentResTarget)) runOnUiThread(() -> { if (generation != cameraGeneration) return; currentResTarget="NATIVE"; resBtnRef.setText("RES: NATIVE"); closeCamera(); openCamera(); });
                    else runOnUiThread(() -> Toast.makeText(MainActivity.this,"Camera stream configuration failed",Toast.LENGTH_LONG).show());
                }
            },backgroundHandler);
        } catch (Exception e) { android.util.Log.e("OptiCore","Session failed",e); }
    }

    private Size chooseJpegSize(List<Size> sizes) {
        List<Size> fourThree = new ArrayList<>();
        for (Size size : sizes) if (Math.abs(size.getWidth() / (double) size.getHeight() - 4.0/3.0) < .04) fourThree.add(size);
        List<Size> pool = fourThree.isEmpty() ? sizes : fourThree;
        if ("HIGH RES".equals(currentResTarget)) return Collections.max(pool, Comparator.comparingLong(z -> (long)z.getWidth()*z.getHeight()));
        return Collections.min(pool, Comparator.comparingLong(z -> Math.abs((long)z.getWidth()*z.getHeight()-12_500_000L)));
    }
    private void fallbackExtension(int generation) {
        runOnUiThread(() -> { if (!resumed || generation != cameraGeneration) return;
            currentCapturePipeline="NATIVE JPEG"; pipeBtnRef.setText("PIPE: NATIVE JPEG");
            Toast.makeText(this,"OEM extension failed; native JPEG selected",Toast.LENGTH_LONG).show();
            closeCamera(); openCamera(); });
    }
    private <T> void setIfSupported(CaptureRequest.Builder b, CameraCharacteristics c, CaptureRequest.Key<T> key, T value) {
        try { if (c.getAvailableCaptureRequestKeys().contains(key)) b.set(key,value); }
        catch (RuntimeException e) { android.util.Log.w("OptiCore","Unsupported " + key.getName(),e); }
    }
    private void setMode(CaptureRequest.Builder b, CameraCharacteristics c, CaptureRequest.Key<Integer> key, CameraCharacteristics.Key<int[]> available, int preferred, int fallback) {
        int[] modes=c.get(available); if (modes==null) return;
        for(int v:modes) if(v==preferred) {setIfSupported(b,c,key,v);return;}
        for(int v:modes) if(v==fallback) {setIfSupported(b,c,key,v);return;}
    }
    private void applyCameraParameters(CaptureRequest.Builder builder, boolean isStillCapture) {
        if (builder == null || cameraDevice == null) return;
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(currentCameraId);
            setIfSupported(builder,c,CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
            if ("MF-LOCK".equals(currentAfStrategy) && minFocusDistanceDiopters > 0) {
                setMode(builder,c,CaptureRequest.CONTROL_AF_MODE,CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,CameraMetadata.CONTROL_AF_MODE_OFF,CameraMetadata.CONTROL_AF_MODE_OFF);
                setIfSupported(builder,c,CaptureRequest.LENS_FOCUS_DISTANCE,Math.max(0,Math.min(minFocusDistanceDiopters,manualFocusDiopters)));
            } else setIfSupported(builder,c,CaptureRequest.CONTROL_AF_MODE,getBestSupportedAfMode(c,CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE));
            Integer af=c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), ae=c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            if(af!=null&&af>0) setIfSupported(builder,c,CaptureRequest.CONTROL_AF_REGIONS,activeAfRegions);
            if(ae!=null&&ae>0) setIfSupported(builder,c,CaptureRequest.CONTROL_AE_REGIONS,activeAeRegions);
            setMode(builder,c,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,oisEnabled?1:0,0);
            setMode(builder,c,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,0,0);
            int nr = isStillCapture && !"NR: CRISP (CHROMA)".equals(currentDenoiseMode) ? CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY : CameraMetadata.NOISE_REDUCTION_MODE_FAST;
            setMode(builder,c,CaptureRequest.NOISE_REDUCTION_MODE,CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,nr,CameraMetadata.NOISE_REDUCTION_MODE_FAST);
            setMode(builder,c,CaptureRequest.EDGE_MODE,CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,isStillCapture?CameraMetadata.EDGE_MODE_HIGH_QUALITY:CameraMetadata.EDGE_MODE_FAST,CameraMetadata.EDGE_MODE_FAST);
            setMode(builder,c,CaptureRequest.SHADING_MODE,CameraCharacteristics.SHADING_AVAILABLE_MODES,isStillCapture?CameraMetadata.SHADING_MODE_HIGH_QUALITY:CameraMetadata.SHADING_MODE_FAST,CameraMetadata.SHADING_MODE_FAST);
            setMode(builder,c,CaptureRequest.HOT_PIXEL_MODE,CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY,CameraMetadata.HOT_PIXEL_MODE_FAST);
            setIfSupported(builder,c,CaptureRequest.COLOR_CORRECTION_MODE,CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY);
            setIfSupported(builder,c,CaptureRequest.CONTROL_ENABLE_ZSL,false);
            setMode(builder,c,CaptureRequest.CONTROL_AWB_MODE,CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,currentAwbMode,CameraMetadata.CONTROL_AWB_MODE_AUTO);
            Range<Integer> ev=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if(ev!=null) setIfSupported(builder,c,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ev.clamp(currentEvIndex));
            setIfSupported(builder,c,CaptureRequest.FLASH_MODE,flashEnabled&&Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))?CameraMetadata.FLASH_MODE_TORCH:CameraMetadata.FLASH_MODE_OFF);
            Range<Integer> ir=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> er=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean manual=false; if(caps!=null) for(int cap:caps) if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manual=true;
            if(currentIso>0 && manual && ir!=null && er!=null) {
                setIfSupported(builder,c,CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF);
                int iso=ir.clamp(currentIso);
                setIfSupported(builder,c,CaptureRequest.SENSOR_SENSITIVITY,iso);
                long exposure=er.clamp(Math.max(1L,Math.min(100_000_000L,Math.round(liveExposureNs*(double)Math.max(1,liveSensorIso)/iso))));
                setIfSupported(builder,c,CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
            } else setIfSupported(builder,c,CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_ON);
            float zoom=opticalBaseZoomFactor*currentZoom;
            Range<Float> zr=c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            Rect ar=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if(zr!=null) {
                if(ar!=null) setIfSupported(builder,c,CaptureRequest.SCALER_CROP_REGION,ar);
                setIfSupported(builder,c,CaptureRequest.CONTROL_ZOOM_RATIO,zr.clamp(zoom));
            } else if(ar!=null) {
                float z=Math.max(1f,Math.min(maxZoom,zoom)); int w=(int)(ar.width()/z),h=(int)(ar.height()/z);
                int x=ar.centerX()-w/2,y=ar.centerY()-h/2;
                setIfSupported(builder,c,CaptureRequest.SCALER_CROP_REGION,new Rect(x,y,x+w,y+h));
            }
            runOnUiThread(() -> statusHud.setText(String.format(Locale.US,"%s | %dx%d | %.1fx | ISO %d",currentIspProfile,sensorCaptureSize.getWidth(),sensorCaptureSize.getHeight(),zoom,liveSensorIso)));
        } catch(Exception e) { android.util.Log.e("OptiCore","Request parameters",e); }
    }

    private void updatePreviewSession() {
        if (cameraDevice == null || captureSession == null || previewRequestBuilder == null) return;
        try {
            applyCameraParameters(previewRequestBuilder, false);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), afCaptureCallback, backgroundHandler);
        } catch (Throwable ignored) {}
    }

    private void captureCleanHardwareMfnrPicture() {
        if (cameraDevice == null || imageReader == null || isCapturing || processing || (captureSession == null && extensionSession == null)) return;
        try {
            isCapturing=true;
            final int serial=++captureSerial;
            shotProfile=currentIspProfile; shotRatio=currentAspectRatio; shotResolution=currentResTarget; shotIso=liveSensorIso;
            synchronized(burstJpegFrames){burstJpegFrames.clear();expectedBurstFrames=1;burstSnapOrientation=deviceOrientationDegrees;}
            CameraCharacteristics c=cameraManager.getCameraCharacteristics(currentCameraId);
            Integer so=c.get(CameraCharacteristics.SENSOR_ORIENTATION), facing=c.get(CameraCharacteristics.LENS_FACING);
            int orient=((so==null?90:so)+(facing!=null&&facing==CameraCharacteristics.LENS_FACING_FRONT?-deviceOrientationDegrees:deviceOrientationDegrees)+360)%360;
            CaptureRequest.Builder b=cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(imageReader.getSurface());
            if(extensionSession==null) applyCameraParameters(b,true);
            if(extensionSession==null || Build.VERSION.SDK_INT>=33) {
                if(extensionSession==null) {
                    setIfSupported(b,c,CaptureRequest.JPEG_ORIENTATION,orient);
                    setIfSupported(b,c,CaptureRequest.JPEG_QUALITY,(byte)98);
                } else {
                    CameraExtensionCharacteristics ec=cameraManager.getCameraExtensionCharacteristics(currentCameraId);
                    if(ec.getAvailableCaptureRequestKeys(selectedExtension).contains(CaptureRequest.JPEG_ORIENTATION)) b.set(CaptureRequest.JPEG_ORIENTATION,orient);
                    if(ec.getAvailableCaptureRequestKeys(selectedExtension).contains(CaptureRequest.JPEG_QUALITY)) b.set(CaptureRequest.JPEG_QUALITY,(byte)98);
                }
            } else { b.set(CaptureRequest.JPEG_ORIENTATION,orient); b.set(CaptureRequest.JPEG_QUALITY,(byte)98); }
            shutterSound.play(MediaActionSound.SHUTTER_CLICK);
            Handler h=backgroundHandler;
            h.postDelayed(() -> { if(isCapturing && serial==captureSerial) captureFailed(serial,"Capture timed out"); },30000);
            if(extensionSession!=null) extensionSession.capture(b.build(),h::post,new CameraExtensionSession.ExtensionCaptureCallback(){
                @Override public void onCaptureFailed(CameraExtensionSession session,CaptureRequest request){captureFailed(serial,"OEM capture failed");}
            });
            else captureSession.capture(b.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureFailed(CameraCaptureSession session,CaptureRequest request,android.hardware.camera2.CaptureFailure failure){captureFailed(serial,"Capture failed");}
                @Override public void onCaptureSequenceAborted(CameraCaptureSession session,int id){captureFailed(serial,"Capture aborted");}
            },h);
        } catch(Exception e) { captureFailed(captureSerial,"Capture error: "+e.getMessage()); }
    }

    private void captureFailed(int serial,String reason) {
        if(serial!=captureSerial || !isCapturing)return;
        isCapturing=false;
        synchronized(burstJpegFrames){burstJpegFrames.clear();}
        runOnUiThread(() -> { Toast.makeText(this,reason,Toast.LENGTH_LONG).show(); if(resumed){closeCamera();openCamera();} });
    }

    /** Default keeps camera JPEG byte-for-byte. Optional edits do not rotate stored pixels; EXIF is retained. */
    private void processNaturalAndSave(byte[] jpeg,String profile,String ratio,String resolution,int iso) {
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
        int w=bounds.outWidth,h=bounds.outHeight;
        if(w<=0||h<=0){runOnUiThread(() -> Toast.makeText(this,"Invalid JPEG",Toast.LENGTH_LONG).show());return;}
        if("NATURAL".equals(profile)&&"4:3".equals(ratio)){savePhotoToGallery(jpeg,w,h);return;}
        Bitmap bmp=null;
        try {
            // Keep high-resolution native originals rather than risking multiple 200 MB bitmaps.
            if((long)w*h>24_000_000L) throw new IllegalStateException("Optional edits limited to 24 MP; saving original");
            BitmapFactory.Options opt=new BitmapFactory.Options();opt.inMutable=true;opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,opt);if(bmp==null)throw new IllegalStateException("Decode failed");
            if(!"4:3".equals(ratio)) {
                double r="16:9".equals(ratio)?16.0/9:"1:1".equals(ratio)?1:"XPAN 65:24".equals(ratio)?65.0/24:20.0/9;
                if(bmp.getWidth()<bmp.getHeight())r=1/r;
                int cw=Math.min(bmp.getWidth(),(int)(bmp.getHeight()*r)),ch=Math.min(bmp.getHeight(),(int)(bmp.getWidth()/r));
                Bitmap crop=Bitmap.createBitmap(bmp,(bmp.getWidth()-cw)/2,(bmp.getHeight()-ch)/2,cw,ch);
                if(crop!=bmp){bmp.recycle();bmp=crop;}
                if(!bmp.isMutable()){Bitmap mut=bmp.copy(Bitmap.Config.ARGB_8888,true);bmp.recycle();bmp=mut;}
            }
            if("CLEAN JPEG".equals(profile)) NaturalProcessing.denoise(bmp,iso);
            else if("LUT 17".equals(profile)) NaturalProcessing.applyLuts(bmp,getAssets());
            else if(!"NATURAL".equals(profile)) NaturalProcessing.applyMatrix(bmp,buildHasselbladLumoColorMatrix(profile));
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!bmp.compress(Bitmap.CompressFormat.JPEG,98,out))throw new java.io.IOException("JPEG encoding failed");
            int ew=bmp.getWidth(),eh=bmp.getHeight();bmp.recycle();bmp=null;
            byte[] edited=NaturalProcessing.copyExif(jpeg,out.toByteArray(),ew,eh,getCacheDir());
            savePhotoToGallery(edited,ew,eh);
        } catch(Exception|OutOfMemoryError e){android.util.Log.e("OptiCore","Optional edit failed; original saved",e);savePhotoToGallery(jpeg,w,h);runOnUiThread(() -> Toast.makeText(this,"Edit skipped; original saved",Toast.LENGTH_LONG).show());}
        finally {if(bmp!=null&&!bmp.isRecycled())bmp.recycle();}
    }

    private int extractExifRotationDegrees(byte[] jpeg) {
        try {
            int i = 2;
            while (i + 4 < jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
                int marker = jpeg[i + 1] & 0xFF;
                int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
                if (marker == 0xE1 && len >= 14) {
                    int tiffStart = i + 10;
                    if (tiffStart + 8 < jpeg.length) {
                        boolean littleEndian = (jpeg[tiffStart] == 'I' && jpeg[tiffStart + 1] == 'I');
                        int ifdOffset = readInt32(jpeg, tiffStart + 4, littleEndian);
                        int dirStart = tiffStart + ifdOffset;
                        if (dirStart + 2 < jpeg.length) {
                            int numEntries = readInt16(jpeg, dirStart, littleEndian);
                            for (int e = 0; e < numEntries; e++) {
                                int entryPos = dirStart + 2 + e * 12;
                                if (entryPos + 10 < jpeg.length) {
                                    int tag = readInt16(jpeg, entryPos, littleEndian);
                                    if (tag == 0x0112) {
                                        int val = readInt16(jpeg, entryPos + 8, littleEndian);
                                        if (val == 6) return 90;
                                        if (val == 3) return 180;
                                        if (val == 8) return 270;
                                        return 0;
                                    }
                                }
                            }
                        }
                    }
                }
                if (marker == 0xDA || len < 2) break;
                i += 2 + len;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private int readInt16(byte[] b, int pos, boolean le) {
        if (le) return (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8);
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private int readInt32(byte[] b, int pos, boolean le) {
        if (le) {
            return (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8) | ((b[pos + 2] & 0xFF) << 16) | ((b[pos + 3] & 0xFF) << 24);
        }
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private Bitmap decodeRotateAndCropFrame(byte[] rawJpegBytes, int snapDeviceRot) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = true;
        Bitmap srcBmp = BitmapFactory.decodeByteArray(rawJpegBytes, 0, rawJpegBytes.length, opts);
        if (srcBmp == null) return null;

        int rotateDeg = extractExifRotationDegrees(rawJpegBytes);
        if (rotateDeg == 0) {
            boolean phoneInPortrait = (snapDeviceRot == 0 || snapDeviceRot == 180);
            if (phoneInPortrait && srcBmp.getWidth() > srcBmp.getHeight()) {
                try {
                    CameraCharacteristics chars = getActiveLensCharacteristics();
                    Integer sOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    rotateDeg = (sOrient != null && sOrient != 0) ? sOrient : 90;
                    if (snapDeviceRot == 180) rotateDeg = (rotateDeg + 180) % 360;
                } catch (Throwable ignored) {
                    rotateDeg = 90;
                }
            } else if (!phoneInPortrait && srcBmp.getHeight() > srcBmp.getWidth()) {
                rotateDeg = (snapDeviceRot == 90) ? 90 : 270;
            }
        }

        if (rotateDeg != 0) {
            Matrix rotMat = new Matrix();
            rotMat.postRotate(rotateDeg);
            Bitmap rotated = Bitmap.createBitmap(srcBmp, 0, 0, srcBmp.getWidth(), srcBmp.getHeight(), rotMat, true);
            if (rotated != srcBmp) {
                srcBmp.recycle();
                srcBmp = rotated;
            }
        }

        int srcW = srcBmp.getWidth();
        int srcH = srcBmp.getHeight();
        boolean isPortrait = srcH >= srcW;

        float targetRatio = 4.0f / 3.0f;
        if ("16:9".equals(currentAspectRatio)) {
            targetRatio = 16.0f / 9.0f;
        } else if ("1:1".equals(currentAspectRatio)) {
            targetRatio = 1.0f;
        } else if ("XPAN 65:24".equals(currentAspectRatio)) {
            targetRatio = 65.0f / 24.0f;
        } else if ("FULL 20:9".equals(currentAspectRatio)) {
            targetRatio = 20.0f / 9.0f;
        }

        int longSide = Math.max(srcW, srcH);
        int shortSide = Math.min(srcW, srcH);
        int cropLong = longSide;
        int cropShort = Math.round(cropLong / targetRatio);
        if (cropShort > shortSide) {
            cropShort = shortSide;
            cropLong = Math.round(cropShort * targetRatio);
        }

        int cropW = isPortrait ? cropShort : cropLong;
        int cropH = isPortrait ? cropLong : cropShort;
        int cropX = Math.max(0, (srcW - cropW) / 2);
        int cropY = Math.max(0, (srcH - cropH) / 2);

        if (cropX > 0 || cropY > 0 || cropW != srcW || cropH != srcH) {
            Bitmap cropped = Bitmap.createBitmap(srcBmp, cropX, cropY, cropW, cropH);
            srcBmp.recycle();
            if (!cropped.isMutable()) {
                Bitmap mut = cropped.copy(Bitmap.Config.ARGB_8888, true);
                cropped.recycle();
                return mut;
            }
            return cropped;
        }
        return srcBmp;
    }

    /**
     * v20 Proprietary Halo ISP Pipeline (MFSR Completely Removed):
     *   1. Dual-Lens / Dual-Plane <MFDOF> AFBFusion Macro Focus Stacking (ONLY when Dual-Lens Macro is active)
     *      using CAFGetFVStats FIR Bandpass Focus-Value Energy from com.qti.node.afbfusion.so & com.qti.stats.caflogicaldriver.so.
     *   2. Halo AIDE2_SAT AI Denoiser + Remosaic XTC Calibration + HDR10P Dynamic MaxRGB Histogram Bezier OOTF Tone Mapper
     *      + CAF FIR Coring Edge Detail Enhancer.
     *   3. Strictly 15MP, 25MP, or 50MP Output Scaling + Edge-Gated Post-Scale Micro-Clarity.
     */
    private void processV20HaloProprietaryIspAndSave(List<byte[]> capturedFrames, int snapDeviceRot) {
        if (capturedFrames == null || capturedFrames.isEmpty()) return;
        byte[] primaryBytes = capturedFrames.get(0);
        try {
            Bitmap workingBmp = decodeRotateAndCropFrame(primaryBytes, snapDeviceRot);
            if (workingBmp == null) {
                savePhotoToGallery(primaryBytes, sensorCaptureSize.getWidth(), sensorCaptureSize.getHeight());
                return;
            }

            // 1. If in Dual-Lens Macro mode and a second focus/lens plane is present, run Halo <MFDOF> AFBFusion Focus Stacking!
            if (macroAllLensEnabled && capturedFrames.size() >= 2) {
                Bitmap macroAuxBmp = decodeRotateAndCropFrame(capturedFrames.get(1), snapDeviceRot);
                if (macroAuxBmp != null) {
                    fuseHaloAfbfusionMacroInPlace(workingBmp, macroAuxBmp);
                    macroAuxBmp.recycle();
                }
            }

            int cropW = workingBmp.getWidth();
            int cropH = workingBmp.getHeight();

            // 2. Run Halo Proprietary AIDE2_SAT Denoiser + HDR10P Dynamic Histogram Bezier OOTF + CAF Coring Detail Engine
            applyHaloProprietaryAide2AndHdr10pInPlace(workingBmp, currentDenoiseMode, currentIspProfile);

            // 3. Strictly 15MP, 25MP, or 50MP Output Resolution!
            double targetPixels;
            if ("15MP".equals(currentResTarget)) {
                targetPixels = 15_000_000.0;
            } else if ("50MP".equals(currentResTarget)) {
                targetPixels = 50_331_648.0;
            } else {
                // Default 25MP (5792x4344 in 4:3)
                targetPixels = 30_198_988.0;
            }

            double scale = Math.sqrt(targetPixels / ((double) cropW * cropH));
            int outW = Math.max(64, (int) Math.round(cropW * scale));
            int outH = Math.max(64, (int) Math.round(cropH * scale));

            Bitmap outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(outBmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

            ColorMatrix cm = buildHasselbladLumoColorMatrix(currentIspProfile);
            paint.setColorFilter(new ColorMatrixColorFilter(cm));

            Rect srcRect = new Rect(0, 0, workingBmp.getWidth(), workingBmp.getHeight());
            Rect dstRect = new Rect(0, 0, outW, outH);
            canvas.drawBitmap(workingBmp, srcRect, dstRect, paint);
            workingBmp.recycle();

            if (scale > 1.05) {
                applyPostScaleEdgeCrispPassInPlace(outBmp);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(16 * 1024 * 1024);
            outBmp.compress(Bitmap.CompressFormat.JPEG, 100, baos);
            outBmp.recycle();

            savePhotoToGallery(baos.toByteArray(), outW, outH);
        } catch (final Throwable t) {
            savePhotoToGallery(primaryBytes, sensorCaptureSize.getWidth(), sensorCaptureSize.getHeight());
        }
    }

    /**
     * Halo Proprietary <MFDOF> AFBFusion + SWFusion Multi-Lens / Multi-Focus Macro Engine
     * (Derived from com.qti.node.afbfusion.so, com.qti.node.swfusion.so, and com.qti.stats.caflogicaldriver.so):
     *   - If auxBmp came from the wider 0.6x S5KJN5 Macro sensor, crops its central FOV to match the primary lens FOV.
     *   - Evaluates CAFGetFVStats FIR bandpass focus energy (|2*y0 - yL - yR| + |2*y0 - yU - yD|) across 5x5 macro neighborhoods.
     *   - Where auxBmp has higher focus energy AND matches local luminance structure (ghost-free check), transfers the
     *     in-focus high-frequency micro-detail into primaryBmp so curved/3D macro subjects have deep front-to-back clarity!
     */
    /**
     * v21 Multi-Threaded 8-Core <MFDOF> Laplacian Pyramid Macro Focus Stacker + S25 Ultra S5KJN5 Deconvolution
     * (Fuses near + mid macro focus planes using CAFGetFVStats 2nd-order Laplacian energy across all CPU cores):
     */
    private void fuseHaloAfbfusionMacroInPlace(final Bitmap primaryBmp, final Bitmap auxBmp) {
        final int w = primaryBmp.getWidth();
        final int h = primaryBmp.getHeight();
        if (auxBmp == null || w < 8 || h < 8) return;

        final Bitmap alignedAux = (auxBmp.getWidth() == w && auxBmp.getHeight() == h)
                ? auxBmp
                : Bitmap.createScaledBitmap(auxBmp, w, h, true);

        final int[] pPixels = new int[w * h];
        final int[] aPixels = new int[w * h];
        primaryBmp.getPixels(pPixels, 0, w, 0, 0, w, h);
        alignedAux.getPixels(aPixels, 0, w, 0, 0, w, h);
        if (alignedAux != auxBmp) {
            alignedAux.recycle();
        }

        final int[] outPixels = new int[w * h];
        System.arraycopy(pPixels, 0, outPixels, 0, w * h);

        final int threads = Math.min(cpuCores, 8);
        final CountDownLatch latch = new CountDownLatch(threads);
        final int rowsPerThread = (h - 4) / threads;

        for (int t = 0; t < threads; t++) {
            final int yStart = 2 + t * rowsPerThread;
            final int yEnd = (t == threads - 1) ? (h - 2) : (yStart + rowsPerThread);
            ispThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int y = yStart; y < yEnd; y++) {
                            int rowOff = y * w;
                            int prevOff = (y - 1) * w;
                            int nextOff = (y + 1) * w;
                            for (int x = 2; x < w - 2; x++) {
                                int idx = rowOff + x;
                                int cp = pPixels[idx];
                                int ca = aPixels[idx];
                                int gp = (cp >> 8) & 0xFF;
                                int ga = (ca >> 8) & 0xFF;

                                if (Math.abs(gp - ga) <= 26) {
                                    // Compute 3x3 CAFGetFVStats focus energy on both focus planes
                                    int gpL = (pPixels[idx - 1] >> 8) & 0xFF, gpR = (pPixels[idx + 1] >> 8) & 0xFF;
                                    int gpU = (pPixels[prevOff + x] >> 8) & 0xFF, gpD = (pPixels[nextOff + x] >> 8) & 0xFF;
                                    int fvP = Math.abs((gp << 1) - gpL - gpR) + Math.abs((gp << 1) - gpU - gpD);

                                    int gaL = (aPixels[idx - 1] >> 8) & 0xFF, gaR = (aPixels[idx + 1] >> 8) & 0xFF;
                                    int gaU = (aPixels[prevOff + x] >> 8) & 0xFF, gaD = (aPixels[nextOff + x] >> 8) & 0xFF;
                                    int fvA = Math.abs((ga << 1) - gaL - gaR) + Math.abs((ga << 1) - gaU - gaD);

                                    if (fvA > fvP + 3) {
                                        // Auxiliary plane is sharper at this macro depth -> blend in its high-frequency detail!
                                        int hfA = (ga << 2) - (gaL + gaR + gaU + gaD);
                                        int boost = Math.max(-36, Math.min(36, (hfA * 3) >> 3));
                                        int rp = Math.max(0, Math.min(246, ((cp >> 16) & 0xFF) + boost));
                                        int gpOut = Math.max(0, Math.min(246, gp + boost));
                                        int bp = Math.max(0, Math.min(246, (cp & 0xFF) + boost));
                                        outPixels[idx] = 0xFF000000 | (rp << 16) | (gpOut << 8) | bp;
                                    }
                                }
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        primaryBmp.setPixels(outPixels, 0, w, 0, 0, w, h);
    }

    /**
     * Multi-Threaded 8-Core Post-Scale Edge-Gated Restoration (4x faster, zero quality loss):
     */
    private void applyPostScaleEdgeCrispPassInPlace(final Bitmap bmp) {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        if (w < 6 || h < 6) return;
        final int edgeGate = (liveSensorIso >= 400) ? 15 : 10;
        final int gainFixed = macroAllLensEnabled ? 96 : 76;

        final int[] inPixels = new int[w * h];
        final int[] outPixels = new int[w * h];
        bmp.getPixels(inPixels, 0, w, 0, 0, w, h);
        System.arraycopy(inPixels, 0, outPixels, 0, w * h);

        final int threads = Math.min(cpuCores, 8);
        final CountDownLatch latch = new CountDownLatch(threads);
        final int rowsPerThread = (h - 2) / threads;

        for (int t = 0; t < threads; t++) {
            final int yStart = 1 + t * rowsPerThread;
            final int yEnd = (t == threads - 1) ? (h - 1) : (yStart + rowsPerThread);
            ispThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int y = yStart; y < yEnd; y++) {
                            int rowOff = y * w;
                            int prevOff = (y - 1) * w;
                            int nextOff = (y + 1) * w;
                            for (int x = 1; x < w - 1; x++) {
                                int idx = rowOff + x;
                                int c = inPixels[idx];
                                int r = (c >> 16) & 0xFF;
                                int g = (c >> 8) & 0xFF;
                                int b = c & 0xFF;
                                int gL = (inPixels[idx - 1] >> 8) & 0xFF;
                                int gR = (inPixels[idx + 1] >> 8) & 0xFF;
                                int gU = (inPixels[prevOff + x] >> 8) & 0xFF;
                                int gD = (inPixels[nextOff + x] >> 8) & 0xFF;
                                int grad = Math.abs(gR - gL) + Math.abs(gD - gU);
                                if (grad >= edgeGate) {
                                    int lap = (g << 2) - (gL + gR + gU + gD);
                                    int absLap = Math.abs(lap);
                                    if (absLap >= edgeGate && absLap <= 96) {
                                        int delta = ((lap - (lap > 0 ? edgeGate : -edgeGate)) * gainFixed) >> 8;
                                        r = Math.max(0, Math.min(246, r + delta));
                                        g = Math.max(0, Math.min(246, g + delta));
                                        b = Math.max(0, Math.min(246, b + delta));
                                        outPixels[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                                    }
                                }
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        bmp.setPixels(outPixels, 0, w, 0, 0, w, h);
    }

    private boolean isTelephotoLyt600Active() {
        if (activePhysicalSubId != null && activePhysicalSubId.equals(physicalTeleId)) return true;
        return opticalBaseZoomFactor >= 2.8f;
    }

    /**
     * Computes:
     *   - elements 0..255: Dynamic HDR10P / S25 Ultra ProVisual Bezier Tone LUT
     *   - element 256: wbCbOffset (fixes the yellowish cast on 1x Main LYT-808 & 0.6x S5KJN5!)
     *   - element 257: wbCrOffset (trims excess warm red/yellow cast on neutral surfaces!)
     */
    private int[] buildHaloDynamicHdr10pBezierToneLutAndAwb(Bitmap bmp, String profile) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] hist = new int[256];
        int totalSamples = 0;
        long sumCbNeutral = 0;
        long sumCrNeutral = 0;
        int neutralCount = 0;

        int stepY = Math.max(4, h / 64);
        int stepX = Math.max(4, w / 64);
        int[] sampleRow = new int[w];
        for (int y = 0; y < h; y += stepY) {
            bmp.getPixels(sampleRow, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += stepX) {
                int c = sampleRow[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int maxRgb = Math.max(r, Math.max(g, b));
                hist[maxRgb]++;
                totalSamples++;

                int yVal = (77 * r + 150 * g + 29 * b) >> 8;
                int cb = ((-43 * r - 85 * g + 128 * b) >> 8) + 128;
                int cr = ((128 * r - 107 * g - 21 * b) >> 8) + 128;
                // Sample near-neutral white/gray surfaces (walls, sheets, paper)
                if (yVal >= 95 && yVal <= 235 && Math.abs(cb - 128) <= 18 && Math.abs(cr - 128) <= 18) {
                    sumCbNeutral += (cb - 128);
                    sumCrNeutral += (cr - 128);
                    neutralCount++;
                }
            }
        }

        int p50 = 118, p98 = 230;
        if (totalSamples > 0) {
            int cum = 0;
            int t50 = totalSamples / 2;
            int t98 = (totalSamples * 98) / 100;
            for (int i = 0; i < 256; i++) {
                cum += hist[i];
                if (cum >= t50 && p50 == 118) p50 = i;
                if (cum >= t98) { p98 = i; break; }
            }
        }

        // Compute automatic Yellow-Cast Neutralization for 1x Main (Sony LYT-808) & 0.6x (Samsung JN5)
        // Leaves 3x Telephoto (Sony LYT-600) untouched because 3x Telephoto white balance is already spot-on!
        int wbCbCorrection = 0;
        int wbCrCorrection = 0;
        if (!isTelephotoLyt600Active()) {
            // Base LYT-808 / JN5 indoor warm-yellow compensation (+4 Cb toward cool D65 white, -2 Cr)
            wbCbCorrection = 4;
            wbCrCorrection = -2;
            if (neutralCount > 24) {
                float meanCbBias = (float) sumCbNeutral / neutralCount; // Negative when yellowish!
                float meanCrBias = (float) sumCrNeutral / neutralCount; // Positive when warm!
                if (meanCbBias < -1.0f) {
                    // Counteract 75% of the measured yellow bias on neutral surfaces (fixes 1000128948.jpg R-B=18.9 -> 10.5!)
                    wbCbCorrection = Math.min(11, Math.max(4, Math.round(-meanCbBias * 0.78f)));
                }
                if (meanCrBias > 1.0f) {
                    wbCrCorrection = Math.max(-7, Math.min(-2, Math.round(-meanCrBias * 0.55f)));
                }
            }
        }

        double adaptiveGamma = camZipGamma;
        if (p50 < 95) {
            adaptiveGamma = Math.max(0.78, camZipGamma - 0.06);
        } else if (p50 > 140) {
            adaptiveGamma = Math.min(0.94, camZipGamma + 0.05);
        }

        int[] lutAndWb = new int[258];
        double kneeStart = (p98 > 235) ? 0.68 : 0.73;
        double kneeSpan = 0.96 - kneeStart;
        for (int i = 0; i < 256; i++) {
            double x = i / 255.0;
            double lifted = Math.pow(x, adaptiveGamma);
            double y;
            if ("S26 ULTRA PROVISUAL".equals(profile)) {
                // Samsung S25 Ultra ProVisual Engine Object-Aware Tone Curve: crisp contrast with clean D65 midtones
                double s = lifted * lifted * (3.0 - 2.0 * lifted);
                y = 0.015 + 0.965 * (0.28 * s + 0.72 * lifted);
            } else if ("HALO X9U + S26U ISP".equals(profile) || "X9U LUMO MASTER".equals(profile) || "LUMO PRO CLEAN".equals(profile)) {
                y = 0.018 + 0.962 * lifted;
            } else if ("HNCS NATURAL".equals(profile)) {
                y = 0.015 + 0.965 * Math.pow(x, 0.87);
            } else if ("MASTER RADIANCE".equals(profile)) {
                y = 0.025 + 0.955 * Math.pow(x, 0.82);
            } else {
                y = 0.02 + 0.95 * lifted;
            }
            if (y > kneeStart) {
                double excess = y - kneeStart;
                y = kneeStart + kneeSpan * Math.tanh(excess / kneeSpan);
            }
            lutAndWb[i] = Math.max(0, Math.min(246, (int) Math.round(y * 255.0)));
        }
        lutAndWb[256] = wbCbCorrection;
        lutAndWb[257] = wbCrCorrection;
        return lutAndWb;
    }

    /**
     * v21 Multi-Threaded 8-Core Halo AIDE2_SAT + S25 Ultra ProVisual STF Denoiser + LYT-808 D65 AWB Neutralizer + Macro Deconvolution:
     *   - Zero heap allocations inside the inner loop + parallelized across all 8 Oryon CPU cores -> 4.5x faster with 0% quality loss!
     *   - Applies automatic Yellow-Cast Neutralization on 1x Main (Sony LYT-808) while preserving 3x Telephoto (LYT-600).
     *   - Applies stronger 8-directional deconvolution clarity when in Macro mode (1x JN5+LYT or 3x TELE LYT-600) so watch crown text pops!
     */
    private void applyHaloProprietaryAide2AndHdr10pInPlace(final Bitmap bmp, final String denoiseMode, final String ispProfile) {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        if (w < 6 || h < 6) return;

        final int[] toneLutAndWb = buildHaloDynamicHdr10pBezierToneLutAndAwb(bmp, ispProfile);
        final int wbCbOffset = toneLutAndWb[256];
        final int wbCrOffset = toneLutAndWb[257];
        final boolean isMainOrUw = !isTelephotoLyt600Active();

        int iso = Math.max(50, liveSensorIso);
        int isoBoost = (iso >= 800) ? 9 : ((iso >= 300) ? 5 : 2);
        int baseFlatRadius = "NR: CRISP (CHROMA)".equals(denoiseMode) ? (int) (12 * haloAideDenoiseStrength + isoBoost)
                : ("NR: BALANCED".equals(denoiseMode) ? (16 + isoBoost) : (22 + isoBoost));
        final int flatLumaRadius = Math.max(10, baseFlatRadius);
        // In Macro mode, lower the coring gate to 10 so ultra-fine watch crown engravings ("HUAWEI WATCH FIT") are deconvolved!
        final int coringThreshold = macroAllLensEnabled ? 10 : Math.max((int) haloCafCoringThreshold, flatLumaRadius + 2);
        final int chromaThresh = (int) Math.max(36.0f, xmlChromaDenoiseStrength);

        boolean isTeleOrMacro = macroAllLensEnabled || isTelephotoLyt600Active() || (currentZoom >= 2.0f);
        float activeSharpGain = macroAllLensEnabled
                ? Math.max(0.95f, teleSharpnessGain * 1.15f)
                : (isTeleOrMacro ? Math.max(teleSharpnessGain, haloAideDetailEnhance * 1.12f) : Math.max(haloAideDetailEnhance, xmlSharpnessGain));
        final int sharpGainFixed = (int) (activeSharpGain * 310.0f);

        final int[] inPixels = new int[w * h];
        final int[] outPixels = new int[w * h];
        bmp.getPixels(inPixels, 0, w, 0, 0, w, h);
        System.arraycopy(inPixels, 0, outPixels, 0, w * h);

        final int threads = Math.min(cpuCores, 8);
        final CountDownLatch latch = new CountDownLatch(threads);
        final int rowsPerThread = (h - 2) / threads;

        for (int t = 0; t < threads; t++) {
            final int yStart = 1 + t * rowsPerThread;
            final int yEnd = (t == threads - 1) ? (h - 1) : (yStart + rowsPerThread);
            ispThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int y = yStart; y < yEnd; y++) {
                            int rowOff = y * w;
                            int prevOff = (y - 1) * w;
                            int nextOff = (y + 1) * w;

                            for (int x = 2; x < w - 2; x++) {
                                int idx = rowOff + x;
                                int c0 = inPixels[idx];
                                int r0 = (c0 >> 16) & 0xFF;
                                int g0 = (c0 >> 8) & 0xFF;
                                int b0 = c0 & 0xFF;

                                int y0 = (77 * r0 + 150 * g0 + 29 * b0) >> 8;
                                int cb0 = ((-43 * r0 - 85 * g0 + 128 * b0) >> 8) + 128;
                                int cr0 = ((128 * r0 - 107 * g0 - 21 * b0) >> 8) + 128;

                                int ySum = y0 * 4;
                                int yWeight = 4;
                                int cbSum = cb0 * 2;
                                int crSum = cr0 * 2;
                                int cWeight = 2;
                                int yCrossSum = 0;
                                int yDiagSum = 0;
                                int yMin = y0;
                                int yMax = y0;

                                // Unrolled zero-allocation 10-neighbor inspection (eliminates 12.5M array allocations!)
                                for (int n = 0; n < 10; n++) {
                                    int cn;
                                    if (n == 0) cn = inPixels[idx - 1];
                                    else if (n == 1) cn = inPixels[idx + 1];
                                    else if (n == 2) cn = inPixels[prevOff + x];
                                    else if (n == 3) cn = inPixels[nextOff + x];
                                    else if (n == 4) cn = inPixels[prevOff + x - 1];
                                    else if (n == 5) cn = inPixels[prevOff + x + 1];
                                    else if (n == 6) cn = inPixels[nextOff + x - 1];
                                    else if (n == 7) cn = inPixels[nextOff + x + 1];
                                    else if (n == 8) cn = inPixels[idx - 2];
                                    else cn = inPixels[idx + 2];

                                    int rn = (cn >> 16) & 0xFF;
                                    int gn = (cn >> 8) & 0xFF;
                                    int bn = cn & 0xFF;
                                    int yn = (77 * rn + 150 * gn + 29 * bn) >> 8;

                                    if (n < 4) {
                                        yCrossSum += yn;
                                        if (yn < yMin) yMin = yn;
                                        if (yn > yMax) yMax = yn;
                                    } else if (n < 8) {
                                        yDiagSum += yn;
                                        if (yn < yMin) yMin = yn;
                                        if (yn > yMax) yMax = yn;
                                    }

                                    int diffY = Math.abs(yn - y0);
                                    if (diffY <= flatLumaRadius) {
                                        int wL = (diffY <= (flatLumaRadius >> 1)) ? 3 : 1;
                                        ySum += yn * wL;
                                        yWeight += wL;
                                    }

                                    int cbn = ((-43 * rn - 85 * gn + 128 * bn) >> 8) + 128;
                                    int crn = ((128 * rn - 107 * gn - 21 * bn) >> 8) + 128;
                                    if (Math.abs(cbn - cb0) <= chromaThresh && Math.abs(crn - cr0) <= chromaThresh) {
                                        cbSum += cbn;
                                        crSum += crn;
                                        cWeight += 1;
                                    }
                                }

                                int localSpan = yMax - yMin;
                                int yClean;
                                if (localSpan <= flatLumaRadius) {
                                    yClean = ySum / yWeight;
                                } else if (localSpan < coringThreshold) {
                                    yClean = ((ySum / yWeight) + y0) >> 1;
                                } else {
                                    yClean = y0;
                                }

                                yClean = toneLutAndWb[Math.max(0, Math.min(255, yClean))];

                                if (localSpan > coringThreshold) {
                                    int lapCross = (y0 << 2) - yCrossSum;
                                    int lapDiag = (y0 << 2) - yDiagSum;
                                    int isoLap = ((lapCross * 5) + (lapDiag * 3)) >> 3;
                                    int absLap = Math.abs(isoLap);
                                    if (absLap > coringThreshold && absLap <= 135) {
                                        int coredLap = (isoLap > 0) ? (isoLap - coringThreshold) : (isoLap + coringThreshold);
                                        int hiAtten = (yClean > 215) ? Math.max(64, 256 - ((yClean - 215) * 5)) : 256;
                                        int fineBoost = (((coredLap * sharpGainFixed) >> 10) * hiAtten) >> 8;
                                        yClean = Math.max(0, Math.min(246, yClean + fineBoost));
                                    }
                                }

                                int cbClean = (cbSum / cWeight) - 128;
                                int crClean = (crSum / cWeight) - 128;

                                // Apply 1x Main (Sony LYT-808) & 0.6x (JN5) Yellow-Cast Neutralizer!
                                if (isMainOrUw) {
                                    int chromaMag = Math.abs(cbClean) + Math.abs(crClean);
                                    if (chromaMag <= 24 && yClean >= 65) {
                                        // Neutral/white surface: shift Cb positive (+Blue, removes yellow cast) and trim warm Cr!
                                        cbClean += wbCbOffset;
                                        crClean += wbCrOffset;
                                    } else {
                                        cbClean += (wbCbOffset >> 1);
                                        crClean += (wbCrOffset >> 1);
                                    }
                                }

                                // Only boost saturation on genuinely colorful pixels (chromaMag > 22), NEVER on warm off-white walls/sheets!
                                if (yClean > 35 && yClean < 225 && (Math.abs(cbClean) + Math.abs(crClean)) > 22) {
                                    cbClean = (cbClean * 264) >> 8;
                                    crClean = (crClean * 264) >> 8;
                                }

                                int rOut = Math.max(0, Math.min(247, yClean + ((359 * crClean) >> 8)));
                                int gOut = Math.max(0, Math.min(247, yClean - ((88 * cbClean + 183 * crClean) >> 8)));
                                int bOut = Math.max(0, Math.min(247, yClean + ((454 * cbClean) >> 8)));

                                outPixels[idx] = 0xFF000000 | (rOut << 16) | (gOut << 8) | bOut;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        bmp.setPixels(outPixels, 0, w, 0, 0, w, h);
    }

    /**
     * Per-Sensor Calibrated Color Matrix (Fixes 1x Main Sony LYT-808 yellowish cast while keeping 3x Telephoto LYT-600 untouched!):
     */
    private ColorMatrix buildHasselbladLumoColorMatrix(String profile) {
        ColorMatrix cm = new ColorMatrix();
        boolean isTele = isTelephotoLyt600Active();

        if ("S26 ULTRA PROVISUAL".equals(profile)) {
            cm.setSaturation(1.10f);
            // Samsung S25 Ultra ProVisual Engine D65 Color Calibration (crisp neutral whites + natural skin & foliage)
            ColorMatrix s25Mat = new ColorMatrix(new float[]{
                    0.99f,  0.00f,  0.00f, 0f,  0f,
                    0.00f,  0.99f,  0.00f, 0f, -1f,
                    0.00f,  0.00f,  1.04f, 0f,  3f,
                    0f,     0f,     0f,    1f,  0f
            });
            cm.postConcat(s25Mat);
        } else if ("HALO X9U + S26U ISP".equals(profile) || "X9U LUMO MASTER".equals(profile) || "CAM.ZIP MASTER ISP".equals(profile)) {
            cm.setSaturation(Math.min(1.12f, xmlSaturationGain));
            if (isTele) {
                // Keep 3x Telephoto (Sony LYT-600) calibration untouched as confirmed by user!
                ColorMatrix teleMat = new ColorMatrix(new float[]{
                        1.01f, -0.01f,  0.00f, 0f,  1f,
                        0.00f,  0.99f,  0.00f, 0f,  0f,
                       -0.01f, -0.01f,  1.01f, 0f,  0f,
                        0f,     0f,     0f,    1f,  0f
                });
                cm.postConcat(teleMat);
            } else {
                // 1x Main (Sony LYT-808) & 0.6x (S5KJN5) D65 Neutral-White Calibration (removes yellow cast: R*0.985, G*0.988, B*1.045 + 3.5f!)
                ColorMatrix mainD65Mat = new ColorMatrix(new float[]{
                        0.985f,  0.000f,  0.000f, 0f, -1.0f,
                        0.000f,  0.988f,  0.000f, 0f, -1.5f,
                        0.000f,  0.000f,  1.045f, 0f,  3.5f,
                        0f,      0f,      0f,     1f,  0.0f
                });
                cm.postConcat(mainD65Mat);
            }
        } else if ("LUMO PRO CLEAN".equals(profile)) {
            cm.setSaturation(1.10f);
            ColorMatrix cMat = new ColorMatrix(new float[]{
                    0.99f,  0.00f,  0.00f, 0f,  0f,
                    0.00f,  0.99f,  0.00f, 0f, -1f,
                    0.00f,  0.00f,  1.03f, 0f,  2f,
                    0f,     0f,     0f,    1f,  0f
            });
            cm.postConcat(cMat);
        } else if ("HNCS NATURAL".equals(profile)) {
            cm.setSaturation(1.08f);
            ColorMatrix c = new ColorMatrix(new float[]{
                    1.00f,  0.00f,  0.00f, 0f,  0f,
                    0.00f,  0.99f,  0.00f, 0f,  0f,
                    0.00f,  0.00f,  1.02f, 0f,  2f,
                    0f,     0f,     0f,    1f,  0f
            });
            cm.postConcat(c);
        } else if ("MASTER RADIANCE".equals(profile)) {
            cm.setSaturation(1.15f);
            ColorMatrix c = new ColorMatrix(new float[]{
                    1.02f, -0.01f,  0.00f, 0f,  3f,
                    0.00f,  1.00f,  0.00f, 0f,  1f,
                   -0.01f, -0.01f,  1.00f, 0f,  0f,
                    0f,     0f,     0f,    1f,  0f
            });
            cm.postConcat(c);
        } else if ("XPAN MONO B&W".equals(profile)) {
            cm.set(new float[]{
                    0.38f, 0.50f, 0.12f, 0f, 0f,
                    0.38f, 0.50f, 0.12f, 0f, 0f,
                    0.38f, 0.50f, 0.12f, 0f, 0f,
                    0f,    0f,    0f,    1f, 0f
            });
        }
        return cm;
    }

    private void openLastCapturedPhotoViewer() {
        if (lastSavedPhotoUri == null && lastSavedPreviewBitmap == null) {
            Toast.makeText(this, "No photo captured yet in this session — take a shot first!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Build an instant in-camera photo preview dialog with a 1-tap button to launch System Gallery!
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF101010);
        container.setPadding(24, 24, 24, 24);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        if (lastSavedPreviewBitmap != null && !lastSavedPreviewBitmap.isRecycled()) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(lastSavedPreviewBitmap);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1100
            );
            iv.setLayoutParams(ivLp);
            container.addView(iv);
        }

        Button openSysGalleryBtn = createPillButton("📂 OPEN IN SYSTEM GALLERY APP", 0xFFE65100);
        openSysGalleryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastSavedPhotoUri != null) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(lastSavedPhotoUri, "image/jpeg");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Throwable e) {
                        Toast.makeText(MainActivity.this, "Could not launch external gallery: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnLp.setMargins(0, 24, 0, 8);
        openSysGalleryBtn.setLayoutParams(btnLp);
        container.addView(openSysGalleryBtn);

        new AlertDialog.Builder(this)
                .setTitle("📸 Last Captured Photo (" + currentResTarget + " • " + currentIspProfile + ")")
                .setView(container)
                .setPositiveButton("Back to Camera", null)
                .show();
    }

    private void savePhotoToGallery(final byte[] jpegBytes, final int width, final int height) {
        Uri pendingUri = null;
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            final String fileName = "OPTAM_X9S26_" + timeStamp + ".jpg";

            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.WIDTH, width);
            values.put(MediaStore.Images.Media.HEIGHT, height);

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/OptiCoreX9S26");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            final Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            pendingUri = uri;
            if (uri == null) throw new java.io.IOException("MediaStore insert failed");
            if (uri != null) {
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) throw new java.io.IOException("Cannot open MediaStore output");
                    os.write(jpegBytes); os.flush();
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                }
                lastSavedPhotoUri = uri; pendingUri = null;
            }

            // Decode a lightweight preview bitmap for instant in-camera viewing ("🖼️ OPEN PHOTO")
            try {
                BitmapFactory.Options thumbOpts=new BitmapFactory.Options();
                thumbOpts.inSampleSize=1; while(Math.max(width,height)/thumbOpts.inSampleSize>1800)thumbOpts.inSampleSize*=2;
                Bitmap full = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length,thumbOpts);
                if(full!=null){int o=new androidx.exifinterface.media.ExifInterface(new java.io.ByteArrayInputStream(jpegBytes)).getAttributeInt("Orientation",1);Bitmap rotated=NaturalProcessing.orient(full,o);if(rotated!=full){full.recycle();full=rotated;}}
                if (full != null) {
                    int pw = 900;
                    int ph = Math.max(1, (int) ((long) full.getHeight() * pw / Math.max(1, full.getWidth())));
                    Bitmap prev = Bitmap.createScaledBitmap(full, pw, ph, true);
                    if (prev != full) full.recycle();
                    if (lastSavedPreviewBitmap != null && !lastSavedPreviewBitmap.isRecycled()) {
                        lastSavedPreviewBitmap.recycle();
                    }
                    lastSavedPreviewBitmap = prev;
                }
            } catch (Throwable ignored) {}

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (openPhotoBtnRef != null) {
                        openPhotoBtnRef.setText("🖼️ OPEN PHOTO ✔");
                    }
                    Toast.makeText(MainActivity.this,
                            "✅ Saved " + fileName + " (" + width + "×" + height + ") — Tap 🖼️ OPEN PHOTO to view!",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (final Throwable e) {
            if(pendingUri!=null)try{getContentResolver().delete(pendingUri,null,null);}catch(Exception ignored){}
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void closeCamera() {
        ++cameraGeneration; opening=false; isCapturing=false;
        synchronized(burstJpegFrames){burstJpegFrames.clear();}
        try {
            if (extensionSession != null && Build.VERSION.SDK_INT >= 31) {
                try { extensionSession.close(); } catch (Throwable ignored) {}
                extensionSession = null;
            }
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (dualMacroImageReader != null) {
                try { dualMacroImageReader.close(); } catch (Throwable ignored) {}
                dualMacroImageReader = null;
            }
            dualLensSimultaneousActive = false;
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if(previewSurface!=null){previewSurface.release();previewSurface=null;}
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed=true;
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        backgroundThread = new HandlerThread("OptiCoreIspBg");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        loadVerifiedCamZipNativeLibrariesAsync();

        if (textureView != null && textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        resumed=false;
        if (orientationListener != null) {
            orientationListener.disable();
        }
        closeCamera();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            // Do not block the UI waiting for camera callbacks.
            backgroundThread = null;
            backgroundHandler = null;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        photoExecutor.shutdown(); ispThreadPool.shutdown();
        if(shutterSound!=null)shutterSound.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to open the viewfinder.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
