# OptiCore v58 Natural repair (2.71.0)

This is a repair of the actual uploaded `OptiCore_v58_Master_AI_LUT17_NoConflict_Buildable_Source.zip`, not the older recovered Java script. The original launcher, application ID `org.x9s26u.rcm8`, main UI, camera controls, assets, and both AI source implementations are retained. Android compilation and on-device validation are still pending.

## Build

- Android Studio with Android SDK 35 and JDK 17, Gradle 8.10.2. Open the extracted project root and sync. AGP 8.8.2 and Kotlin 2.1.21 are pinned. No Gradle wrapper was provided in the input; use installed Gradle, or `gradle wrapper --gradle-version 8.10.2`.
- Alternatively upload extracted project files to a GitHub repository, including `.github/workflows/build-apk.yml`. Actions > Build OptiCore v58 > Run workflow. The debug APK appears under Artifacts after a successful build.
- Local command: `gradle :app:assembleDebug --stacktrace`. APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signature may differ from an installed copy; back up photos/settings before any uninstall. No signed release APK is supplied.

## Capture modes

- Default: NATURAL look, NATIVE JPEG pipe, NATIVE resolution (supported 4:3 JPEG size nearest 12.5 MP), auto ISO, OIS, balanced hardware NR. Standard 4:3 JPEGs are saved byte-for-byte, with camera EXIF. Native camera output quality depends on the HAL. No artificial 30/50 MP interpolation, fake HDR, extra sharpening or forced max-resolution sensor mode.
- PIPE cycles Native JPEG, OEM HDR, OEM Night. Only exposed extension modes and supported stream sizes are requested. Failed configurations reopen the normal pipeline. OEM modes own their capture controls; manual ISO, zoom, WB and focus controls are not guaranteed there. Test native mode first.
- RES: HIGH RES requests largest standard supported 4:3 JPEG stream; may fall back to NATIVE if the session fails. This is not guaranteed 50 MP. Optional bitmap edits skip images above 24 MP to avoid excess allocation.
- LOOK: CLEAN JPEG enables optional 3x3 edge-aware luma/chroma smoothing. LUT 17 applies both supplied .cube files via trilinear interpolation (they are nearly identity). Other original styles use existing Java colour matrices only, not vendor RAW ISP. Other ratios are JPEG-domain center crops. Edited output preserves selected EXIF tags; untouched JPEG preserves all source metadata. Optional edits can fall back to original on errors.
- Macro: standard continuous close-focus mode, not dual-lens stacking. In normal camera mode optical routing is delegated to the logical camera HAL. No physical lens access guarantee.
- AI ZOOM LAB: opens the existing CameraX activity. Original is default and always saved before AI. Optional Clean 1x, Zoom 2x, Zoom 3x crop modes load the supplied QuickSRNetLarge on demand, GPU with CPU creation fallback. Native 4x model is reduced to 1x or 2x output, 16px overlap, two tile rows at a time, 96 MiB output bitmap cap (not a total RAM guarantee). Low memory may shrink the crop or skip AI; check the actual output dimensions. NPU not enabled in this build. The separate QuickSrNet.kt implementation is retained but is not invoked by the launcher; the streaming OpticoreEnhancer is the active AI implementation.

## Original proprietary assets

All original packaged native libraries were preserved in `reference/original-jniLibs`, outside the Android source set. They will NOT ship in the APK or be loaded; bundled Maven LiteRT supplies its own matching runtime. The original vendor configs and model files remain in assets, but do not become HAL plugins by being present. The missing CamZipConfigData class is not required for startup. Unused vendor tuning/ISP Java methods remain as legacy code, not active capture paths. No claim of Qualcomm NPU, OPPO native ISP, proprietary HDR10P, DETR autofocus or cross-sensor fusion activation.

## Device checklist

1. Install/debug launch; allow camera permission; confirm live preview. Capture 1x native 4:3, repeat 5 times.
2. Shoot the same stationary scene with same framing, lighting, distance, lens and no digital zoom using stock and native custom camera. Keep EXIF. Compare shutter/ISO, resolution, noise and real texture at matched scale.
3. Test HDR and Night availability, fallback, portrait/landscape orientation, 0.6x/3x and front camera. Test high resolution and manual ISO only in native mode.
4. Test CLEAN JPEG and LUT 17 independently. Check gallery preview orientation and image dimensions.
5. Open AI Zoom Lab; test Original, then an optional AI crop on a well-lit scene. If model initialization/inference fails, the original should remain saved. Read logs rather than assume an accelerator is active.
6. Check `adb logcat -s OptiCore Opticore OpticoreEnhancer CameraX` and `adb shell dumpsys meminfo org.x9s26u.rcm8` during capture. Record actual peak PSS and elapsed time.

## Validation and limitations

Java syntax parsed with JDK compiler API (not type-checked against Android SDK); manifest and resource XML parsed; ZIP CRC checked. Gradle, Android SDK and a OnePlus device are unavailable in this environment, so neither an APK nor runtime performance is verified. Kotlin/LiteRT/CameraX compatibility requires a real Gradle build. The native JPEG pipeline can reduce avoidable processing artifacts but cannot guarantee stock camera quality, which depends on OEM multi-frame capture and vendor tuning. No changes to device system files or root required.

API references: https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics and https://developers.google.com/edge/litert/next/android_kotlin .
