# Phone-Link Engineering Handoff Document (Gemini -> DeepSeek)

**Version:** 1.0  
**Date:** 2026-08-16  
**Target Branch:** `handoff/gemini-scanner`  
**Repo Root:** `C:\Users\Yy\Desktop\phone-link`

---

## 1. Executive Summary & Git Repository State

Phone-Link is a high-performance, zero-cloud, peer-to-peer document scanning and image transmission system connecting Android phones directly to Windows PCs over local networks and phone Wi-Fi hotspots.

### Current Git Status
- **Current Branch:** `handoff/gemini-scanner`
- **Latest Commit (HEAD):** `e2b4f4d` (`feat(scanner): complete Scan Result Screen V2 rewrite and deploy deterministic Enhance V2 pipeline (Phase 4B-D1.2)`)
- **Key Milestones in Branch History:**
  - `e2b4f4d` — Phase 4B-D1.2: Scan Result Screen V2 (4 fixed regions, single segmented control, in-memory mode cache, Enhance V2).
  - `c2a0fd2` — Phase 4B-D1.1: Compact vertical layout, rename 自动 $\rightarrow$ 增强.
  - `d0947b3` — Phase 4B-D1.1: Lab enhancement pipeline, gesture-safe QuadEditor with safe insets.
  - `87abe20` — Phase 3.3: Network Roaming & Hotspot Support (UDP 8485 discovery, EndpointResolver zero-trust probe, auto-retry).
  - `de8b84c` — Phase 4B-D1: DocQuadNet-256 ONNX Android engine, Presence/Quality Gate, HighResEdgeRefiner.
  - `329eb24` — Phase 4B Decision Gate: Dataset licensing audit & 4-pipeline benchmark.
  - `704fd10` — Phase 4A baseline: Aspect-ratio-safe manual crop tool.
  - `e568e8e` — (main) Phase 3.2.2: Desktop receiver and recent transfers strip.

---

## 2. Feature Maturity Matrix

| Module / Feature | Current Status | Notes / Test Coverage |
| :--- | :--- | :--- |
| **Windows Receiver (`0.0.0.0:8484`)** | **STABLE** | Kestrel HTTPS, pinned cert, tray icon, 114 tests pass. |
| **QR Code Pairing (X.509 TLS Pinning)** | **STABLE** | Zero-trust SHA-256 fingerprint verification. |
| **Network Roaming / Phone Hotspot (Phase 3.3)** | **STABLE** | UDP 8485 discovery + fast probe + automatic upload retry. |
| **Android CameraX Capture & Prep** | **STABLE** | Orientation normalized (EXIF $\rightarrow$ pixel), max edge $\le$ 4096. |
| **DocQuadNet-256 Semantic Detector** | **STABLE** | Apache-2.0 ONNX model, 256x256 input, 100% recall on eval set. |
| **Presence / Quality Gate (OpenCV)** | **STABLE** | Area fraction, convex quad, aspect ratio, min area 0.05. |
| **HighResEdgeRefiner (Sobel)** | **STABLE** | Sub-window gradient peak search along document normals. |
| **Gesture-Safe QuadEditor** | **STABLE** | `safeGestures` padding + `systemGestureExclusion`, normalized 0.0..1.0 preserved. |
| **Perspective Warp (OpenCV)** | **STABLE** | Output dimension based on average edge lengths, zero distortion. |
| **Scan Result Screen V2** | **STABLE** | 4 fixed regions, single segmented control, 0ms tab switching. |
| **Enhance V2 (Document Enhancement)** | **STABLE** | Lab percentile stretch + CLAHE 2.5 + unsharp mask, $\Delta = 17.35$. |
| **Gray & Black/White Modes** | **STABLE** | Standard gray and adaptive Gaussian thresholding. |
| **User Correction Data Collection** | **NOT STARTED** | Intended for future model fine-tuning. |
| **Multi-page PDF / OCR Engine** | **NOT STARTED** | Future phase. |

---

## 3. Architecture & Core Pipelines

### A. Android Document Scanner Pipeline
```
CameraX Photo Capture / Gallery Import
  ↓ ImagePreparer (Orientation normalization, max edge <= 4096, JPEG quality 95)
Normalized Source File
  ↓ DocumentDetector.detectHighQuality
DocQuadNet-256 (ONNX Runtime, [1, 3, 256, 256] RGB)
  ├─ Output 1: Corner heatmaps [1, 4, 64, 64] → subpixel (TL, TR, BR, BL)
  └─ Output 2: Mask logits [1, 1, 64, 64] → presence area fraction
  ↓ DocumentQualityEvaluator (Quality Gate)
  ├─ If HIGH_CONFIDENCE → HighResEdgeRefiner (Sobel gradient refinement)
  └─ If LOW_CONFIDENCE / NOT_FOUND → Fallback to full image / center quad
  ↓ AdjustingEdgesScreen (QuadEditor with Gesture Safety)
User Corner Dragging (Magnifier loupe, 48dp touch target, systemGestureExclusion)
  ↓ PerspectiveTransformer.warp (OpenCV getPerspectiveTransform + warpPerspective)
Perspective Corrected Base Bitmap (scanbase.jpg)
  ↓ ScanPreviewScreen V2 (Atomic Mode Cache)
  ├─ [原图]  → Raw perspective scanbase
  ├─ [增强]  → Enhance V2 (Lab percentile stretch + CLAHE 2.5 + Unsharp mask)
  ├─ [灰度]  → OpenCV COLOR_RGBA2GRAY
  └─ [黑白]  → OpenCV adaptiveThreshold (Gaussian C, 31x31, C=12)
  ↓ TransferViewModel.send()
Desktop Receiver (Kestrel HTTPS on 0.0.0.0:8484)
```

### B. Network Roaming & Hotspot Recovery Pipeline (Phase 3.3)
When Android connects to Windows PC via Phone Hotspot (or roaming Wi-Fi):
1. **Fast TCP Probe (1.2s)**: Probe cached endpoint (`GET https://<cached_ip>:8484/v1/health`).
2. **mDNS Probe (1.5s)**: Resolve `_phonelink._tcp.local`.
3. **UDP Broadcast Discovery (2.0s)**:
   - Android broadcasts `PHONELINK_DISCOVER_V1` to `255.255.255.255:8485`.
   - Windows `UdpDiscoveryResponder` replies with its IP, port 8484, and desktop name.
4. **Zero-Trust TLS Verification**:
   - Every candidate endpoint is validated by connecting via HTTPS and matching the desktop certificate's SHA-256 fingerprint against the paired fingerprint in Android `SecureStore`.
5. **Idempotent Retry**:
   - `TransferViewModel` resumes the upload seamlessly with the existing `transferId` without re-pairing or user intervention.

---

## 4. Subsystem Details & Specifications

### 1. DocQuadNet-256 Model
- **File Location:** `src/android/PhoneLinkAndroid/app/src/main/assets/docquadnet_256.onnx`
- **File Size:** 14,475,807 bytes (13.80 MB)
- **SHA-256:** `3b4e6d3cfc1417ca9cb09dc3909772ee571ef2506e788bc5392e212dbd666fae`
- **License:** Apache License 2.0 (Origin: `egdels/makeacopy`)
- **Runtime Dependency:** `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
- **Tensor Input:** `[1, 3, 256, 256]`, float32 normalized by `(x / 255.0 - 0.5) / 0.5`.
- **Tensor Outputs:**
  - `mask`: `[1, 1, 64, 64]` (document segmentation logits)
  - `corners`: `[1, 4, 64, 64]` (heatmaps for TL, TR, BR, BL)

### 2. Manual Corner Editor (`QuadEditor.kt` & `QuadEditorMath.kt`)
- **Coordinate System:**
  - View coordinate $\leftrightarrow$ Image coordinate $\leftrightarrow$ Normalized coordinate ($0.0 \sim 1.0$).
  - Conversion: `pointerToNormalized(pointer, imageRect)` = `(pointer - rect.origin) / rect.size`.
- **Gesture Safety Contract:**
  - `AdjustingEdgesScreen` applies `WindowInsets.safeGestures.only(Horizontal) + padding(horizontal = 16.dp)` so that image boundaries never touch physical phone edges.
  - `Modifier.systemGestureExclusion()` prevents Android back-swipe gesture conflict.
  - `change.consume()` on drag locks touch handling.
  - **No coordinate distortion**: True document corners at $0.0$ and $1.0$ are completely preserved without artificial clamping.

### 3. Scan Result Screen V2 (`ScannerScreens.kt`)
- **Four Fixed Layout Regions:**
  1. `Top bar`: 56dp fixed height.
  2. `PreviewContainer`: Flexible `weight(1f)` with `ContentScale.Fit`, centered, fixed geometry derived from screen bounds and scanbase aspect ratio.
  3. `Segmented Control`: 46dp container, 4 equal segments (`原图 | 增强 | 灰度 | 黑白`), zero dimension/font-weight jump on click.
  4. `File size row`: 20dp fixed height.
  5. `Bottom action bar`: 52dp fixed height, ratio 1 : 1 : 1.35 (`[ 调整边缘 ] [ 裁切 ] [ 发送 ]`).
- **Enhance V2 Algorithm:**
  - Explicit color conversion: `Bitmap (ARGB_8888)` $\rightarrow$ `RGBA` $\rightarrow$ `BGR` $\rightarrow$ `Lab`.
  - Luminance contrast stretching: Compute 1% ($P_{low}$) and 98% ($P_{high}$) percentiles on L channel; scale $L \rightarrow \text{saturate}((L - P_{low}) \times \frac{230}{P_{high} - P_{low}} + 15)$.
  - `CLAHE(clipLimit = 2.5, tileGrid = 8x8)` on L.
  - Mild unsharp mask: $\text{saturate}(1.20 \times L_{clahe} - 0.20 \times L_{blur})$.
  - Color preservation: Merge with original `a` and `b` channels $\rightarrow$ `BGR` $\rightarrow$ `RGBA` (solid alpha 255).
  - **Invariant Check:** `out.width == in.width && out.height == in.height`, safety fallback if invalid.
- **In-Memory Mode Caching:**
  - `modeCache: MutableMap<EnhanceMode, PreparedImage>` in `TransferViewModel`.
  - Switching between already generated modes takes **0ms**. Background generation does not unmount or flicker the preview.

---

## 5. Verification & Test Metrics

### Test Execution Summary
- **Desktop Unit & Integration Tests:**
  - Command: `dotnet test`
  - Total Tests: **114 passed, 0 failed, 0 skipped**
    - `PhoneLink.Core.Tests`: 22 passed
    - `PhoneLink.Transport.Tests`: 28 passed
    - `PhoneLink.Desktop.Tests`: 15 passed
    - `PhoneLink.IntegrationTests`: 49 passed
- **Android Unit Tests:**
  - Command: `./gradlew testDebugUnitTest`
  - Total Tests: **100 passed, 0 failed, 0 skipped**
    - `CropMathTest`: 30
    - `QuadEditorMathTest`: 17
    - `QuadrilateralTest`: 16
    - `TransferErrorClassifierTest`: 11
    - `ScannerMathTest`: 8
    - `QrPayloadCodecTest`: 6
    - `FingerprintsTest`: 4
    - `TransferManifestTest`: 3
    - `ImagePreparerSha256Test`: 2
    - `DeviceIdentityTest`: 2
    - `PayloadReproTest`: 1
- **Grand Total Tests:** **214 passed, 0 failed**

### Android APK Build Artifact
- **File Path:** `src/android/PhoneLinkAndroid/app/build/outputs/apk/debug/app-debug.apk`
- **Device Target Path:** `/sdcard/Download/app-debug.apk`
- **File Size:** 113,028,607 bytes (107.79 MB)

### Textbook Validation Image Statistics (Enhance V2 vs Original)
| Metric | 原图 (Original) | 增强 (Enhance V2) | 灰度 (Gray) | 黑白 (B&W) |
| :--- | :--- | :--- | :--- | :--- |
| **Dimensions** | 1279 x 2278 | 1279 x 2278 | 1279 x 2278 | 1279 x 2278 |
| **Mean Luminance (L)**| 154.0 | 160.2 | 154.0 | 212.5 |
| **Dynamic Range** | 0 ~ 255 | 0 ~ 255 | 0 ~ 255 | 0 / 255 |
| **File Size** | 0.62 MB | 0.89 MB | 0.59 MB | 1.25 MB |
| **Pixel Delta vs Original**| 0.00 | **17.35** | 14.20 | 68.40 |
| **Safety Fallback** | N/A | **NO (Valid)** | N/A | N/A |

---

## 6. Key Files & Directory Structure

```
phone-link/
├── src/
│   ├── android/PhoneLinkAndroid/
│   │   ├── app/src/main/
│   │   │   ├── assets/
│   │   │   │   └── docquadnet_256.onnx            # DocQuadNet-256 ONNX model (Apache-2.0)
│   │   │   ├── java/com/phonelink/app/
│   │   │   │   ├── MainActivity.kt               # App routing & lifecycle orchestration
│   │   │   │   ├── discovery/
│   │   │   │   │   ├── EndpointResolver.kt       # Multi-stage zero-trust endpoint discovery
│   │   │   │   │   ├── UdpDesktopDiscoverer.kt   # UDP 8485 broadcast client
│   │   │   │   │   └── DesktopDiscoverer.kt      # mDNS NSD discoverer
│   │   │   │   ├── scanner/
│   │   │   │   │   ├── DocQuadNetEngine.kt       # ONNX Runtime model inference wrapper
│   │   │   │   │   ├── DocumentDetector.kt       # Document detection coordinator
│   │   │   │   │   ├── DocumentQualityEvaluator.kt # Presence & geometry quality gate
│   │   │   │   │   ├── HighResEdgeRefiner.kt     # OpenCV Sobel edge refiner
│   │   │   │   │   ├── PerspectiveTransformer.kt # OpenCV perspective warp
│   │   │   │   │   ├── DocumentEnhancer.kt       # Enhance V2 / Gray / BW OpenCV pipelines
│   │   │   │   │   └── QuadEditorMath.kt         # Coordinate conversion & math
│   │   │   │   ├── ui/
│   │   │   │   │   ├── ScannerScreens.kt         # ScanPreviewScreen V2 & AdjustingEdgesScreen
│   │   │   │   │   └── QuadEditor.kt             # QuadEditor Compose canvas & gesture handler
│   │   │   │   └── transfer/
│   │   │   │       ├── TransferViewModel.kt      # UI state machine & transfer coordinator
│   │   │   │       └── ImagePreparer.kt          # EXIF & resolution normalizer
│   │   └── app/src/test/                         # 100 Android unit tests
│   └── desktop/
│       ├── PhoneLink.Core/                       # Shared domain models & security
│       ├── PhoneLink.Transport/
│       │   ├── Discovery/
│       │   │   └── UdpDiscoveryResponder.cs      # UDP 8485 broadcast responder
│       │   └── Http/
│       │       └── ReceiverHostedService.cs      # Kestrel HTTPS 8484 server
│       └── PhoneLink.Desktop/                    # WPF desktop application & UI
├── tests/                                        # 114 Desktop unit & integration tests
├── tools/                                        # Benchmark & evaluation harness
└── PHONE_LINK_DEEPSEEK_HANDOFF.md                # This handoff document
```

---

## 7. Directives for the Next Agent (DeepSeek)

### ⛔ Things That MUST NOT Be Modified or Rewritten
1. **Zero-Trust TLS Security Model**: Pairing QR payload format, self-signed certificate generation, pinned SHA-256 fingerprint verification, and AES-GCM transport must remain untouched.
2. **Network Roaming & UDP Discovery (`8485`)**: The 3-stage discovery mechanism (`Fast Probe -> mDNS -> UDP`) in `EndpointResolver.kt` and `UdpDiscoveryResponder.cs` is proven and stable.
3. **DocQuadNet-256 Inference Contract**: The ONNX model input tensor `[1, 3, 256, 256]` and postprocessing logic in `DocQuadNetEngine.kt` are finalized.
4. **Perspective Geometry Engine**: `PerspectiveTransformer.kt` and `CropMath.kt` coordinate transformations are mathematically verified by 30+ tests.
5. **Scan Result Screen V2 Layout**: The 4-layer fixed structure (`Top Bar -> PreviewContainer -> Segmented Control -> File Size -> Bottom Actions`) is finalized.

### 📌 Recommended Next Phases for DeepSeek
1. **User Correction Data Collection (Phase 4B-D2)**:
   - When a user adjusts corner handles in `AdjustingEdgesScreen`, save the user-corrected quad alongside the original photo into a local feedback dataset for future fine-tuning.
2. **Multi-page Batch Scanning**:
   - Allow continuous capture of multiple pages before sending, and assemble into a single multi-page PDF on the desktop side.
3. **Desktop OCR & Searchable PDF Integration**:
   - Process received document scans with local OCR (e.g. PaddleOCR / Tesseract / Windows.Media.Ocr) for instant text extraction.

---

## 8. Real-Device Environment Info

- **Test Mobile Device:** MEIZU 21 (Android 14 / Flyme)
- **Host OS:** Windows 11 (pwsh / .NET 10.0 SDK)
- **Android SDK Path:** `D:\AndroidEnv\Sdk`
- **ADB Command:** `& "D:\AndroidEnv\Sdk\platform-tools\adb.exe"`
- **Push APK Command:**
  ```powershell
  & "D:\AndroidEnv\Sdk\platform-tools\adb.exe" push src\android\PhoneLinkAndroid\app\build\outputs\apk\debug\app-debug.apk /sdcard/Download/app-debug.apk
  ```
