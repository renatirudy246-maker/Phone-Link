# Phone-Link — Gemini / Antigravity Handoff

## 1. Handoff purpose

You are taking over an existing Windows + Android project named **Phone-Link**.

Do **not** rebuild the project from scratch.

The project already has a stable cross-device pipeline. The current blocker is the Android **Phase 4B document scanner**. Your task is to audit the existing scanner implementation and make real-world page detection, manual corner adjustment, and perspective correction reliable enough for daily study use.

The user’s core scenario is:

Phone camera -> photograph a book/worksheet question -> automatically detect the page -> correct perspective -> optionally crop -> send to Windows -> later feed to AI.

The scanner must work **offline** in the user’s normal environment. Do not make Google Play Services / ML Kit Document Scanner a required runtime dependency.

## 2. Product principle

The product north star is:

> Let content captured on the phone continue on the PC with as little friction as possible.

For the current scanner phase:

> Point the phone at a reasonably flat book page / worksheet, press capture, and get a correctly bounded, perspective-corrected page without requiring tedious manual corner correction.

Do not expand scope into OCR, AI answering, cloud sync, iOS, PDF management, multi-page document management, or social features.

## 3. Existing stable functionality

Treat these areas as stable unless an actual regression proves otherwise:

### Windows
- WPF desktop app
- .NET 10
- HTTPS local receiver
- SQLite transfer history
- latest image preview
- recent thumbnail filmstrip
- copy/open/open-folder
- tray behavior
- pause receiving
- paired device management

### Transport / Security
- QR pairing
- persistent desktop TLS identity
- SHA-256 certificate fingerprint pinning
- per-device token authentication
- token verification without raw token storage
- device revoke
- NSD / mDNS reconnect
- HTTPS image upload
- SHA-256 transfer integrity
- idempotent retry / transfer status confirmation

### Android
- Kotlin
- Jetpack Compose
- CameraX
- paired desktop reconnect
- Home screen
- Camera screen
- Gallery picker
- capture preview
- send / retry
- WYSIWYG CameraX preview/capture work already done
- manual rectangular crop foundation
- original / working-image lifecycle
- manifest / hash regeneration after image processing

Do not rewrite these areas for architectural preference.

## 4. Known Git history

Known stable historical commits:

- `e1bb9bf` — `phase-0: bootstrap phone-link workspace`
- `9ce0233` — `phase-1: add local image receiver`
- `2e09f9a` — `phase-2: add secure QR pairing and discovery`
- `3481147` — `phase-3: complete camera-to-pc flow`

There were later Phase 3.x UI changes and Phase 4A / 4B work.

**Do not assume their commit hashes from this document.**

At startup, inspect:

```text
git status
git branch --show-current
git log --oneline --decorate -25
git diff
git diff --cached
```

Preserve all current user work and uncommitted scanner experiments.

Never run destructive reset/clean without explicit user authorization.

## 5. Repository areas to inspect first

Likely important paths:

```text
src/android/PhoneLinkAndroid/
src/android/PhoneLinkAndroid/app/
src/desktop/
tests/
tools/scanpoc/
docs/
PROJECT_STATUS.md
AGENT_BUILD_SPEC.md
```

Scanner-related files may include names similar to:

```text
scanner/
DocumentDetector
DocumentBoundaryExtractor
PerspectiveTransformer
DocumentEnhancer
Quadrilateral
QuadEditorMath.kt
CropMath.kt
CropEditorScreen.kt
AdjustEdgesScreen.kt
ScannerImageMapper.kt
```

Do not assume exact names; locate the actual implementation.

Also inspect:

```text
tools/scanpoc/out/
```

if present.

## 6. Current Phase 4B failure

The current scanner is **not accepted**.

Real-device testing on a **MEIZU 21** showed:

### Failure A — real page detection

The scanner frequently fails to correctly lock onto one complete page of a book / worksheet.

Static or synthetic PoC fixtures looked good, but **real CameraX captures do not reach acceptable quality**.

This is the primary blocker.

Do not claim success based only on generated fixtures.

### Failure B — perspective result

Because the detected quadrilateral is inaccurate, the final `warpPerspective` output is not comparable to a good commercial document scanner.

For reasonably flat pages, the expected experience is:

```text
slightly angled photo
-> detect full page
-> four corners near true paper corners
-> perspective correction
-> clean rectangular page
```

### Failure C — manual four-corner editor

A severe jump bug was identified in an older implementation:

```text
normalizedPoint += dragAmount
```

where `dragAmount` was pixels but the point was normalized 0..1.

This causes a tiny physical movement to clamp a corner to an extreme.

A fix was reportedly introduced using an absolute pointer mapping such as:

```text
nx = (pointerX - imageRect.left) / imageRect.width
ny = (pointerY - imageRect.top) / imageRect.height
```

and keeping the active corner stable for the entire pointer gesture.

**Verify that this fix is actually integrated into the real Adjust Edges UI, not only a unit-tested helper.**

The manual editor must allow pixel-level / small-step adjustment without jumps.

## 7. ML Kit experiment — do not depend on it

Google Play Services exists on the MEIZU 21, and capability probes succeeded, but launching Google’s Document Scanner attempted to download a Play Services scanner/update module and became stuck in the user’s normal network environment.

Therefore:

- do not make ML Kit Document Scanner the default scanner;
- do not require VPN/proxy;
- do not require Google runtime downloads;
- remove debug ML Kit probe UI if still present;
- if ML Kit dependencies remain, audit whether they are still needed.

The final core scanner must work locally/offline.

## 8. OpenCV state

OpenCV Android `4.10.0` was downloaded manually as an AAR because Gradle/Maven download was unreliable.

Likely local path:

```text
src/android/PhoneLinkAndroid/app/libs/opencv-4.10.0.aar
```

The file is about 107 MiB / 112 MB decimal.

The APK was reported around 61.7 MB with OpenCV ABIs.

Audit the actual current Gradle dependency.

Do not redownload or change OpenCV versions without a concrete reason.

## 9. Segmentation PoC

A proof of concept used the **FairScan v1.2.0 segmentation model**:

- DeepLabV3+ / MobileNetV2 style document segmentation
- 256x256 input
- about 4.9 MB
- PoC inference roughly 76–141 ms on the development machine
- synthetic/static fixture corner error looked good

However:

**The FairScan model/license is GPLv3 and it was only used as a PoC/reference.**

It was reportedly stored outside the repository under a temp path and must not silently become a production dependency.

Before shipping any model/code, explicitly audit its license.

The user wants the project to remain free to choose its future distribution/license, so do not copy GPL code/model into production without explicit user approval.

## 10. Important lesson from the failed PoC

Do not optimize for synthetic fixtures.

The actual problem is real-world detection:

- printed book pages
- worksheets
- wooden / textured desks
- light backgrounds
- dark backgrounds
- handwriting
- shadows
- nearby keyboards/screens/rectangles
- page occupying ~50% of frame
- page occupying ~90% of frame
- perspective skew
- book spine nearby

The scanner must be evaluated on real photos from the MEIZU 21.

## 11. Technical direction

You may redesign the scanner internals, but keep the rest of the app stable.

A reasonable offline architecture is:

```text
CameraX capture
    |
    v
document-region proposal / segmentation
    |
    v
boundary extraction
    |
    v
four robust edge lines
    |
    v
corner intersections
    |
    v
high-resolution edge refinement
    |
    v
validated quadrilateral
    |
    v
manual correction if needed
    |
    v
high-resolution perspective transform
    |
    v
document enhancement
    |
    v
existing Preview / Send pipeline
```

Do not assume a single:

```text
Canny -> findContours -> approxPolyDP -> largest quad
```

pipeline is sufficient.

A segmentation-first or multi-hypothesis approach is acceptable.

You may propose another fully-offline approach if it is better.

## 12. Scanner geometry requirements

Quadrilateral semantic order must remain stable:

```text
TL
TR
BR
BL
```

Validation must prevent:

- self-intersection
- non-convex quadrilateral
- degenerate area
- corner identity swapping during drag

Perspective correction should use the full-resolution oriented capture, not the low-resolution detection tensor.

For an approximately planar page:

```text
outputWidth  = max(topWidth, bottomWidth)
outputHeight = max(leftHeight, rightHeight)
```

with a correctly ordered four-point homography.

Do not claim that a four-point homography can fully flatten a strongly curved book page. Curved-page dewarping is a separate future problem.

## 13. Manual corner drag requirements

This must be independently reliable even if automatic detection is imperfect.

Rules:

1. Hit-test the active corner once on pointer down.
2. Keep that same semantic corner until pointer up/cancel.
3. Use absolute pointer coordinates in the editor’s local **pixel** space.
4. Use the actual displayed image rectangle, including Fit/letterbox offsets.
5. Convert pixel pointer -> normalized image point exactly once.
6. Do not add pixel deltas directly to normalized coordinates.
7. Do not re-run `sortCorners()` every move.
8. In manual mode, automatic detector/refinement must not overwrite user points.
9. If a proposed move makes the quad invalid, reject that small move and retain `lastValidQuad`; do not snap the point somewhere far away.
10. Consume pointer events appropriately.
11. Respect Android gesture-safe insets for handle hit targets.
12. Logical corner coordinates must still be allowed to reach the actual image edges.

Numerical sanity example:

```text
displayed image width = 800 px
finger movement = 8 px

normalized delta should be about:
8 / 800 = 0.01

not 0.1 / 1.0 / 8.0
```

## 14. CameraX coordinate audit

Before blaming detection, verify coordinate transforms.

The project previously fixed CameraX Preview/ImageCapture WYSIWYG behavior.

Do not break it.

For live analysis overlays, verify:

```text
ImageAnalysis coordinate space
-> rotation / crop rect / ViewPort
-> PreviewView coordinate space
```

Do not rely on naive:

```text
x / imageWidth * previewWidth
```

if PreviewView is cropped/scaled/rotated.

If live overlay is currently confusing debugging, disable it temporarily.

Get **post-capture scanning correct first**.

## 15. Development order

Do not attempt everything at once.

### Stage 1 — baseline audit

Before changing code:

- inspect git state
- read `AGENT_BUILD_SPEC.md`
- read `PROJECT_STATUS.md`
- locate scanner architecture
- locate current tests
- run current desktop and Android tests
- build Android
- identify all scanner/model/OpenCV/MLKit dependencies
- identify the exact runtime path used after CameraX capture

Report the baseline.

### Stage 2 — manual corner editor

Make the real UI editor precise and stable.

Verify on MEIZU 21.

Do not proceed if the user still reports jumping.

### Stage 3 — post-capture automatic page detection

Ignore live overlay initially.

Use real captures.

For each test image produce debug artifacts:

```text
input.jpg
mask/proposal.png
boundary.png
quad-overlay.png
warped.jpg
```

This makes failures diagnosable.

### Stage 4 — real-world dataset

Test at least:

```text
A dark desk + white page
B wood-grain desk + book page
C light desk + white page
D strong shadow
E perspective skew
F handwriting
G keyboard/display/other rectangle in background
H page ~50% frame
I page ~90% frame
J no document
```

Prefer images captured by the actual MEIZU 21.

### Stage 5 — only after post-capture is reliable

Add/re-enable stable live overlay.

## 16. Acceptance standard

Do not report Phase 4B complete simply because tests/build pass.

Real-device acceptance is required.

For a reasonably flat full page:

- auto quad should usually be directly usable or require only minor correction;
- it must identify the **page**, not a nearby monitor/keyboard/table rectangle;
- perspective result must look like a straight rectangular document;
- small text, formulas and handwriting must remain readable;
- manual corners must move smoothly and predictably;
- no Google network/runtime dependency;
- Windows must receive the final processed image through the existing pipeline.

If confidence is low, prefer:

```text
“Please adjust page edges”
```

over confidently producing a wrong crop.

## 17. Do not expand scope

Do not implement yet:

- OCR
- question-number recognition
- automatic problem block splitting
- AI question answering
- curved-page neural dewarping
- multi-page PDF
- cloud relay
- iOS

Fix the scanner first.

## 18. Test expectations

Do not reduce existing test coverage.

Reported test counts have changed as the project grew; **verify actual current totals instead of trusting stale numbers in this handoff.**

At various points the project reported:

```text
Desktop: 100+ tests
Android: 99 tests
```

Treat the repository as source of truth.

Add focused tests for:

- pointer px -> normalized mapping
- letterboxed image rect
- stable active-corner identity
- invalid quad rejection
- quadrilateral ordering/validation
- homography corner ordering
- detector result on real fixtures
- fallback behavior
- final output manifest/hash

## 19. Git discipline

Create a dedicated handoff branch if one does not already exist, for example:

```text
handoff/gemini-scanner
```

Do not delete existing experiments before understanding them.

Do not make a giant cleanup commit before establishing the baseline.

Use checkpoint commits where useful.

Do not push secrets, tokens, certificates, personal photos, or local build caches.

Large local AAR/model files should be handled intentionally.

## 20. What I want from your first pass

Do **not** immediately rewrite the scanner.

First:

1. inspect repository and git state;
2. run baseline builds/tests;
3. identify the exact production scanner path currently executed;
4. identify whether manual corner fix is really wired into UI;
5. identify why static PoC success does not transfer to real CameraX photos;
6. audit all model licenses/dependencies;
7. produce a concrete repair plan;
8. then implement the smallest high-confidence repair path.

Routine engineering decisions do not require asking the user.

Ask the user only when a real-device physical action is needed or when a product/license decision cannot be inferred safely.

## 21. First response format

After your audit, report:

```text
Repository status:
Current branch:
Current HEAD:
Dirty files:

Desktop build/tests:
Android build/tests:

Current scanner runtime path:
Current detector:
Current model/dependency:
Current OpenCV integration:
ML Kit remnants:

Manual-corner real implementation:
Likely cause(s) of remaining scanner failure:

Files you will change:
Files you will not touch:

Plan:
1.
2.
3.

First real-device checkpoint:
```

Then proceed with the scanner repair.

Do not enter OCR/AI work.
