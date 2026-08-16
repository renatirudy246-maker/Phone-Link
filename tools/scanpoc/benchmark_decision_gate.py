"""Phase 4B Scanner Decision Gate — Real Dataset 4-Pipeline Unified Benchmark.

Evaluates:
  Pipeline A: Current Android Production DocumentDetector (replicated in Python)
  Pipeline B: Improved Multi-hypothesis Classical Detector (Lab L + Morph Grad + Multi-threshold)
  Pipeline C: Segmentation-first PoC (FairScan TFLite Oracle)
  Pipeline D: MakeACopy DocQuadNet-256 (Apache 2.0 ONNX / ORT Model)

Outputs:
  - Quantitative comparison JSON & Markdown tables
  - Side-by-side Contact Sheets (Original | Manual GT | Pipeline A | Pipeline B | Pipeline C | Pipeline D)
  - Perspective Warped Output comparisons (full-resolution A | B | C | D)
  - Individual overlay, mask, heatmap, and warp files per pipeline

Usage:
  py -3.10 tools/scanpoc/benchmark_decision_gate.py [dataset_dir] [fairscan_model_path] [docquad_model_path] [out_dir]
"""
import json
import math
import os
import sys
import time

import cv2
import numpy as np

try:
    from ai_edge_litert.interpreter import Interpreter
    HAVE_LITERT = True
except ImportError:
    HAVE_LITERT = False

try:
    import onnxruntime as ort
    HAVE_ORT = True
except ImportError:
    HAVE_ORT = False


MODEL_IN = 256
MODEL_THRESHOLD = 0.5


def to_serializable(val):
    if isinstance(val, np.ndarray):
        return val.tolist()
    elif isinstance(val, (np.float32, np.float64, np.floating)):
        return float(val)
    elif isinstance(val, (np.int32, np.int64, np.integer)):
        return int(val)
    elif isinstance(val, dict):
        return {k: to_serializable(v) for k, v in val.items()}
    elif isinstance(val, list):
        return [to_serializable(v) for v in val]
    elif isinstance(val, tuple):
        return [to_serializable(v) for v in val]
    return val


# =====================================================================
# Geometry & Helper Math
# =====================================================================

def intersect_lines(l1, l2):
    """Intersect two lines in (a, b, c) form where a*x + b*y + c = 0."""
    a1, b1, c1 = l1
    a2, b2, c2 = l2
    det = a1 * b2 - a2 * b1
    if abs(det) < 1e-8:
        return None
    return np.array([(b1 * c2 - b2 * c1) / det, (a2 * c1 - a1 * c2) / det], np.float32)


def order_corners(corners):
    """Order 4 points cyclically: TL, TR, BR, BL."""
    c = np.array(corners, np.float64)
    best = None
    for i in range(4):
        j = (i + 1) % 4
        my = (c[i, 1] + c[j, 1]) / 2.0
        if best is None or my < best[0]:
            best = (my, i, j)
    _, i, j = best
    ti, tj = (i, j) if c[i, 0] < c[j, 0] else (j, i)  # TL, TR
    rest = [k for k in range(4) if k != i and k != j]
    bl, br = (rest[0], rest[1]) if c[rest[0], 0] < c[rest[1], 0] else (rest[1], rest[0])
    return np.float32([c[ti], c[tj], c[br], c[bl]])


def validate_quad(q, img_w, img_h, min_area_frac=0.08):
    """Check: length > 0, convex, no self-intersection, min area, angle range 35..145 deg."""
    if q is None or len(q) != 4:
        return False
    q = np.asarray(q, np.float64)
    if np.min(np.linalg.norm(np.roll(q, -1, 0) - q, axis=1)) < 1e-4:
        return False
    area = abs(cv2.contourArea(np.int32(q)))
    if area < min_area_frac * img_w * img_h:
        return False
    # Convexity check
    edges = np.roll(q, -1, 0) - q
    cr = edges[:, 0] * np.roll(edges, -1, 0)[:, 1] - edges[:, 1] * np.roll(edges, -1, 0)[:, 0]
    if not (np.all(cr > 0) or np.all(cr < 0)):
        return False
    # Corner angles within [35, 145] deg
    for i in range(4):
        v1 = q[i] - q[(i - 1) % 4]
        v2 = q[(i + 1) % 4] - q[i]
        c = np.clip(np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9), -1, 1)
        deg = np.degrees(np.arccos(c))
        if deg < 35 or deg > 145:
            return False
    return True


def partition_contour_sides(pts, cx, cy):
    """Split 2D contour points into 4 side clusters using radial distance peaks."""
    rad = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
    ang = (np.degrees(np.arctan2(pts[:, 1] - cy, pts[:, 0] - cx)) + 360.0) % 360.0
    bins = 72
    hist = np.zeros(bins)
    for a, r in zip(ang, rad):
        hist[int(a / 5) % bins] += r
    hist = np.convolve(np.concatenate([hist, hist, hist]), np.ones(5) / 5, "same")[bins:2 * bins]

    peaks = []
    for i in range(bins):
        left = hist[(i - 1) % bins]
        right = hist[(i + 1) % bins]
        if hist[i] >= left and hist[i] >= right:
            peaks.append(i)

    if len(peaks) >= 4:
        order = sorted(peaks, key=lambda i: hist[i], reverse=True)
        keep = []
        for p in order:
            if all(min(abs(p - q), bins - abs(p - q)) * 5 >= 40 for q in keep):
                keep.append(p)
            if len(keep) == 4:
                break
        if len(keep) == 4:
            corners_idx = sorted(keep)
            cuts = [c * 5 for c in corners_idx]
            cuts_arr = np.array(cuts + [cuts[0] + 360])
            groups = []
            for k in range(4):
                lo, hi = cuts_arr[k], cuts_arr[k + 1]
                if k == 3:
                    sel = (ang >= lo) | (ang < hi - 360)
                else:
                    sel = (ang >= lo) & (ang < hi)
                groups.append(pts[sel])
            if all(len(g) >= 3 for g in groups):
                return groups
    return None


def fit_and_intersect_quad(groups, img_w, img_h):
    """Fit 4 lines and compute 4 intersections, ordered and validated."""
    lines = []
    for g in groups:
        fit = cv2.fitLine(np.float32(g), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        vx, vy, x0, y0 = float(fit[0]), float(fit[1]), float(fit[2]), float(fit[3])
        a_, b_ = vy, -vx
        norm = math.hypot(a_, b_) + 1e-9
        lines.append((a_ / norm, b_ / norm, -(a_ * x0 + b_ * y0) / norm))

    c_pts = [
        intersect_lines(lines[3], lines[0]),
        intersect_lines(lines[0], lines[1]),
        intersect_lines(lines[1], lines[2]),
        intersect_lines(lines[2], lines[3]),
    ]
    if any(x is None for x in c_pts):
        return None
    ordered = order_corners(c_pts)
    if validate_quad(ordered, img_w, img_h, min_area_frac=0.08):
        return ordered
    return None


def refine_edges_gradient(orig_bgr, quad_orig, search_dist_frac=0.03):
    """Refine corners using 1D Sobel gradient along edges."""
    h, w = orig_bgr.shape[:2]
    gray = cv2.cvtColor(orig_bgr, cv2.COLOR_BGR2GRAY)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.hypot(gx, gy)

    diag = math.hypot(w, h)
    search_dist = max(8, int(diag * search_dist_frac))
    num_samples = 40
    lines = []

    for i in range(4):
        p1 = quad_orig[i]
        p2 = quad_orig[(i + 1) % 4]
        dx, dy = p2[0] - p1[0], p2[1] - p1[1]
        length = math.hypot(dx, dy)
        if length < 10:
            return quad_orig
        nx, ny = -dy / length, dx / length

        edge_pts = []
        for s in range(1, num_samples):
            t = s / float(num_samples)
            bx, by = p1[0] + t * dx, p1[1] + t * dy
            best_val, best_off = -1.0, 0
            for off in range(-search_dist, search_dist + 1):
                sx = int(round(bx + off * nx))
                sy = int(round(by + off * ny))
                if 0 <= sx < w and 0 <= sy < h:
                    v = mag[sy, sx]
                    if v > best_val:
                        best_val, best_off = v, off
            if best_val > 15.0:
                edge_pts.append([bx + best_off * nx, by + best_off * ny])

        if len(edge_pts) >= 6:
            fit = cv2.fitLine(np.float32(edge_pts), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
            vx, vy, x0, y0 = float(fit[0]), float(fit[1]), float(fit[2]), float(fit[3])
            a_, b_ = vy, -vx
            norm = math.hypot(a_, b_) + 1e-9
            lines.append((a_ / norm, b_ / norm, -(a_ * x0 + b_ * y0) / norm))
        else:
            a_, b_ = -dy / length, dx / length
            lines.append((a_, b_, -(a_ * p1[0] + b_ * p1[1])))

    if len(lines) == 4:
        c_pts = [
            intersect_lines(lines[3], lines[0]),
            intersect_lines(lines[0], lines[1]),
            intersect_lines(lines[1], lines[2]),
            intersect_lines(lines[2], lines[3]),
        ]
        if not any(x is None for x in c_pts):
            ordered = order_corners(c_pts)
            if validate_quad(ordered, w, h, min_area_frac=0.08):
                return ordered
    return quad_orig


def warp_document(img_bgr, quad):
    """Execute high-resolution perspective transform."""
    quad = np.float32(quad)
    w_top = np.linalg.norm(quad[1] - quad[0])
    w_bot = np.linalg.norm(quad[2] - quad[3])
    h_left = np.linalg.norm(quad[3] - quad[0])
    h_right = np.linalg.norm(quad[2] - quad[1])

    target_w = max(100, int(round(max(w_top, w_bot))))
    target_h = max(100, int(round(max(h_left, h_right))))

    target_pts = np.float32([[0, 0], [target_w, 0], [target_w, target_h], [0, target_h]])
    matrix = cv2.getPerspectiveTransform(quad, target_pts)
    return cv2.warpPerspective(img_bgr, matrix, (target_w, target_h), flags=cv2.INTER_LANCZOS4)


# =====================================================================
# Pipeline A: Current Android Production DocumentDetector (Replication)
# =====================================================================

def detect_pipeline_a(img_bgr):
    """Exact logic of current Android DocumentDetector.kt."""
    t0 = time.perf_counter()
    h, w = img_bgr.shape[:2]
    max_dim = 1080
    scale = 1.0
    if max(h, w) > max_dim:
        scale = max_dim / float(max(h, w))
        scaled_w, scaled_h = int(w * scale), int(h * scale)
        small = cv2.resize(img_bgr, (scaled_w, scaled_h), interpolation=cv2.INTER_AREA)
    else:
        small = img_bgr.copy()
        scaled_w, scaled_h = w, h

    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 75, 200)

    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    image_area = float(scaled_w * scaled_h)

    best_quad = None
    max_area = 0.0

    for c in contours:
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) == 4 and cv2.isContourConvex(approx):
            area = cv2.contourArea(approx)
            if area > max_area and area > 0.10 * image_area:
                max_area = area
                best_quad = approx.reshape(4, 2)

    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    if best_quad is not None:
        pts = best_quad.astype(np.float32) / scale
        ordered = order_corners(pts)
        conf = float(min(1.0, max_area / (image_area * 0.95)))
        return {
            "status": "Detected",
            "corners": ordered,
            "conf": conf,
            "time_ms": elapsed_ms,
            "is_fallback": False,
        }
    else:
        inset_x, inset_y = w * 0.05, h * 0.05
        fallback = np.float32([
            [inset_x, inset_y],
            [w - inset_x, inset_y],
            [w - inset_x, h - inset_y],
            [inset_x, h - inset_y]
        ])
        return {
            "status": "FallbackDefault",
            "corners": fallback,
            "conf": 0.0,
            "time_ms": elapsed_ms,
            "is_fallback": True,
        }


# =====================================================================
# Pipeline B: Improved Multi-hypothesis Classical Detector
# =====================================================================

def detect_pipeline_b(img_bgr):
    """Improved classical: Lab L + morphological gradient + RANSAC/Huber."""
    t0 = time.perf_counter()
    h, w = img_bgr.shape[:2]
    proc_w = 480
    scale = proc_w / float(w)
    proc_h = int(round(h * scale))
    small = cv2.resize(img_bgr, (proc_w, proc_h), interpolation=cv2.INTER_AREA)

    lab = cv2.cvtColor(small, cv2.COLOR_BGR2LAB)
    l_channel = lab[:, :, 0]
    blurred = cv2.bilateralFilter(l_channel, 7, 50, 50)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(blurred)

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    morph_grad = cv2.morphologyEx(enhanced, cv2.MORPH_GRADIENT, kernel)
    _, bin1 = cv2.threshold(morph_grad, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    bin2 = cv2.adaptiveThreshold(enhanced, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 15, 2)
    binary = cv2.bitwise_or(bin1, cv2.bitwise_not(bin2))
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    best_quad_small = None
    best_score = -1.0

    for c in contours:
        area = cv2.contourArea(c)
        if area < 0.10 * proc_w * proc_h:
            continue
        hull = cv2.convexHull(c)
        pts = hull.reshape(-1, 2).astype(np.float64)
        if len(pts) < 16:
            continue
        cx, cy = pts.mean(axis=0)
        side_clusters = partition_contour_sides(pts, cx, cy)
        if side_clusters is not None:
            quad = fit_and_intersect_quad(side_clusters, proc_w, proc_h)
            if quad is not None:
                score = cv2.contourArea(np.int32(quad)) / float(proc_w * proc_h)
                if score > best_score:
                    best_score = score
                    best_quad_small = quad

    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    if best_quad_small is not None:
        quad_orig = order_corners(best_quad_small / scale)
        quad_refined = refine_edges_gradient(img_bgr, quad_orig)
        return {
            "status": "Detected",
            "corners": quad_refined,
            "conf": float(min(1.0, best_score + 0.2)),
            "time_ms": elapsed_ms,
            "is_fallback": False,
        }
    else:
        inset_x, inset_y = w * 0.05, h * 0.05
        fallback = np.float32([
            [inset_x, inset_y],
            [w - inset_x, inset_y],
            [w - inset_x, h - inset_y],
            [inset_x, h - inset_y]
        ])
        return {
            "status": "FallbackDefault",
            "corners": fallback,
            "conf": 0.0,
            "time_ms": elapsed_ms,
            "is_fallback": True,
        }


# =====================================================================
# Pipeline C: Segmentation-first PoC (FairScan TFLite Oracle)
# =====================================================================

class FairScanOracle:
    def __init__(self, model_path):
        self.model_path = model_path
        self.interp = None
        if os.path.exists(model_path) and HAVE_LITERT:
            try:
                self.interp = Interpreter(model_path=model_path, num_threads=2)
                self.interp.allocate_tensors()
            except Exception as e:
                print(f"Failed to load FairScan model: {e}")

    def is_available(self):
        return self.interp is not None

    def detect(self, img_bgr):
        if not self.is_available():
            return None
        t0 = time.perf_counter()
        orig_h, orig_w = img_bgr.shape[:2]
        small = cv2.resize(img_bgr, (MODEL_IN, MODEL_IN), interpolation=cv2.INTER_AREA)
        x = ((small.astype(np.float32) - 127.5) / 127.5)[np.newaxis, ...]

        inp = self.interp.get_input_details()[0]
        out = self.interp.get_output_details()[0]
        self.interp.set_tensor(inp["index"], x)
        self.interp.invoke()
        logits = self.interp.get_tensor(out["index"])[0, :, :, 0]
        prob = 1.0 / (1.0 + np.exp(-logits))

        # Mask cleanup
        m = (prob >= MODEL_THRESHOLD).astype(np.uint8) * 255
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
        m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, k)
        ff = m.copy()
        cv2.floodFill(ff, None, (0, 0), 255)
        ff = cv2.bitwise_not(ff)
        m = cv2.bitwise_or(m, ff)

        n, labels, stats, _ = cv2.connectedComponentsWithStats(m, 8)
        if n <= 1:
            ms = (time.perf_counter() - t0) * 1000.0
            inset_x, inset_y = orig_w * 0.05, orig_h * 0.05
            return {
                "status": "FallbackDefault",
                "conf": 0.0,
                "corners": np.float32([[inset_x, inset_y], [orig_w - inset_x, inset_y], [orig_w - inset_x, orig_h - inset_y], [inset_x, orig_h - inset_y]]),
                "time_ms": ms,
                "mask": m,
                "is_fallback": True,
            }

        areas = stats[1:, cv2.CC_STAT_AREA]
        idx = 1 + int(np.argmax(areas))
        comp = (labels == idx).astype(np.uint8) * 255

        contours, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        if not contours:
            ms = (time.perf_counter() - t0) * 1000.0
            inset_x, inset_y = orig_w * 0.05, orig_h * 0.05
            return {
                "status": "FallbackDefault",
                "conf": 0.0,
                "corners": np.float32([[inset_x, inset_y], [orig_w - inset_x, inset_y], [orig_w - inset_x, orig_h - inset_y], [inset_x, orig_h - inset_y]]),
                "time_ms": ms,
                "mask": m,
                "is_fallback": True,
            }

        cnt = max(contours, key=cv2.contourArea)
        pts = cnt.reshape(-1, 2).astype(np.float64)
        cx, cy = pts.mean(axis=0)

        groups = partition_contour_sides(pts, cx, cy)
        corners_model = None
        if groups is not None:
            corners_model = fit_and_intersect_quad(groups, MODEL_IN, MODEL_IN)

        if corners_model is None:
            hull = cv2.convexHull(cnt)
            approx = cv2.approxPolyDP(hull, 3.0, True)
            if len(approx) == 4:
                corners_model = order_corners(approx.reshape(4, 2))

        ms = (time.perf_counter() - t0) * 1000.0
        if corners_model is not None and validate_quad(corners_model, MODEL_IN, MODEL_IN, min_area_frac=0.05):
            sx, sy = orig_w / float(MODEL_IN), orig_h / float(MODEL_IN)
            corners_full = np.float32([(px * sx, py * sy) for px, py in corners_model])
            corners_full = refine_edges_gradient(img_bgr, corners_full)
            return {
                "status": "Detected",
                "conf": float(prob[comp.astype(bool)].mean()),
                "corners": corners_full,
                "time_ms": ms,
                "mask": m,
                "is_fallback": False,
            }
        else:
            inset_x, inset_y = orig_w * 0.05, orig_h * 0.05
            return {
                "status": "FallbackDefault",
                "conf": 0.0,
                "corners": np.float32([[inset_x, inset_y], [orig_w - inset_x, inset_y], [orig_w - inset_x, orig_h - inset_y], [inset_x, orig_h - inset_y]]),
                "time_ms": ms,
                "mask": m,
                "is_fallback": True,
            }


# =====================================================================
# Pipeline D: MakeACopy DocQuadNet-256 (Apache 2.0 ONNX / ORT Model)
# =====================================================================

class DocQuadNetPipeline:
    def __init__(self, model_path):
        self.available = False
        self.session = None
        self.model_path = model_path
        if HAVE_ORT and os.path.exists(model_path):
            try:
                self.session = ort.InferenceSession(model_path)
                self.available = True
            except Exception as e:
                print(f"Failed to load DocQuadNet model: {e}")

    def detect(self, img_bgr):
        if not self.available:
            return None
        t0 = time.perf_counter()
        h, w = img_bgr.shape[:2]

        # 1. Letterbox transformation (mirrors MakeACopy DocQuadLetterbox)
        scale = min(256.0 / w, 256.0 / h)
        new_w, new_h = int(round(w * scale)), int(round(h * scale))
        ox, oy = (256.0 - new_w) / 2.0, (256.0 - new_h) / 2.0

        resized = cv2.resize(img_bgr, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
        in256 = np.zeros((256, 256, 3), dtype=np.uint8)
        ix0, iy0 = int(round(ox)), int(round(oy))
        in256[iy0:iy0 + new_h, ix0:ix0 + new_w] = resized

        # RGB float [0.0, 1.0] NCHW
        rgb = cv2.cvtColor(in256, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        nchw = np.transpose(rgb, (2, 0, 1))[np.newaxis, ...]

        # 2. Run ORT Inference
        outs = self.session.run(["corner_heatmaps", "mask_logits"], {"input": nchw})
        ch, ml = outs[0], outs[1]  # ch: [1, 4, 64, 64], ml: [1, 1, 64, 64]

        # 3. Postprocess corners with 5x5 quadratic subpixel refinement
        corners256 = []
        for c in range(4):
            hm = ch[0, c]
            idx = int(np.argmax(hm))
            best_y, best_x = np.unravel_index(idx, hm.shape)

            dx, dy = 0.0, 0.0
            if 0 < best_x < 63:
                l, val, r = float(hm[best_y, best_x - 1]), float(hm[best_y, best_x]), float(hm[best_y, best_x + 1])
                denom = l - 2.0 * val + r
                if denom < -1e-12:
                    dx = np.clip(0.5 * (l - r) / denom, -0.5, 0.5)

            if 0 < best_y < 63:
                t, val, b = float(hm[best_y - 1, best_x]), float(hm[best_y, best_x]), float(hm[best_y + 1, best_x])
                denom = t - 2.0 * val + b
                if denom < -1e-12:
                    dy = np.clip(0.5 * (t - b) / denom, -0.5, 0.5)

            x64 = float(best_x) + 0.5 + dx
            y64 = float(best_y) + 0.5 + dy
            # Convert from 64-grid to 256-space: x256 = x64 * 4.0
            corners256.append([x64 * 4.0, y64 * 4.0])

        # 4. Map back to original image space
        corners_orig = []
        for cx, cy in corners256:
            ox_orig = (cx - ox) / scale
            oy_orig = (cy - oy) / scale
            corners_orig.append([ox_orig, oy_orig])

        quad_orig = order_corners(np.float32(corners_orig))
        elapsed_ms = (time.perf_counter() - t0) * 1000.0

        # Mask probability
        mask_prob = 1.0 / (1.0 + np.exp(-ml[0, 0]))
        mask_uint8 = (mask_prob > 0.5).astype(np.uint8) * 255

        # Visual heatmaps grid (2x2)
        heatmaps_vis = np.zeros((128, 128, 3), dtype=np.uint8)
        names = ["TL", "TR", "BR", "BL"]
        for c in range(4):
            hm = ch[0, c]
            hm_norm = cv2.normalize(hm, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
            hm_color = cv2.applyColorMap(hm_norm, cv2.COLORMAP_JET)
            hm_color = cv2.resize(hm_color, (64, 64), interpolation=cv2.INTER_NEAREST)
            cv2.putText(hm_color, names[c], (4, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
            row = (c // 2) * 64
            col = (c % 2) * 64
            heatmaps_vis[row:row + 64, col:col + 64] = hm_color

        valid = validate_quad(quad_orig, w, h, min_area_frac=0.08)
        conf = 0.95 if valid else 0.40

        return {
            "status": "Detected" if valid else "FallbackDefault",
            "corners": quad_orig,
            "conf": conf,
            "time_ms": elapsed_ms,
            "mask": mask_uint8,
            "heatmaps": heatmaps_vis,
            "is_fallback": not valid,
        }


# =====================================================================
# Evaluation Metrics & Contact Sheet Generation
# =====================================================================

def compute_corner_errors(gt_corners, pred_corners, img_w, img_h):
    """Compute corner distance error % relative to diagonal."""
    diag = math.hypot(img_w, img_h)
    dists = [math.hypot(p[0] - g[0], p[1] - g[1]) for g, p in zip(gt_corners, pred_corners)]
    err_pct = [(d / diag) * 100.0 for d in dists]
    return {
        "errors_pct": err_pct,
        "mean_err_pct": float(np.mean(err_pct)),
        "max_err_pct": float(np.max(err_pct)),
        "p95_err_pct": float(np.percentile(err_pct, 95)),
    }


def draw_labeled_quad(img_bgr, quad, color_bgr, label, is_fallback=False):
    vis = img_bgr.copy()
    h, w = vis.shape[:2]
    pts = np.int32(quad)
    cv2.polylines(vis, [pts], True, color_bgr, 6, cv2.LINE_AA)

    names = ["TL", "TR", "BR", "BL"]
    corner_colors = [(0, 0, 255), (0, 255, 255), (0, 255, 0), (255, 128, 0)]
    for p, n, c in zip(quad, names, corner_colors):
        ip = (int(round(p[0])), int(round(p[1])))
        cv2.circle(vis, ip, 12, (0, 0, 0), -1, cv2.LINE_AA)
        cv2.circle(vis, ip, 10, c, -1, cv2.LINE_AA)
        cv2.circle(vis, ip, 12, (255, 255, 255), 2, cv2.LINE_AA)

    cv2.rectangle(vis, (0, 0), (w, 55), (20, 20, 20), -1)
    status_str = "FALLBACK" if is_fallback else "DETECTED"
    cv2.putText(vis, f"{label} [{status_str}]", (15, 38), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 2, cv2.LINE_AA)
    return vis


def build_comparison_sheet_6col(orig_bgr, gt_vis, a_vis, b_vis, c_vis, d_vis, target_h=480):
    """Build side-by-side: [Original | Manual GT | Pipeline A | Pipeline B | Pipeline C | Pipeline D]."""
    panels = [orig_bgr, gt_vis, a_vis, b_vis, c_vis, d_vis]
    titles = [
        "Original Image",
        "Manual Ground Truth",
        "Pipeline A (Current Canny)",
        "Pipeline B (Improved Classical)",
        "Pipeline C (FairScan Oracle)",
        "Pipeline D (DocQuadNet-256)"
    ]
    resized_panels = []
    for p, t in zip(panels, titles):
        h, w = p.shape[:2]
        scale = target_h / float(h)
        nw = int(round(w * scale))
        r = cv2.resize(p, (nw, target_h), interpolation=cv2.INTER_AREA)
        cv2.rectangle(r, (0, 0), (nw, 32), (15, 15, 15), -1)
        cv2.putText(r, t, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
        resized_panels.append(r)
    return np.hstack(resized_panels)


def build_warp_comparison_4col(a_warp, b_warp, c_warp, d_warp, target_h=520):
    """Build side-by-side: [A Warp | B Warp | C Warp | D Warp]."""
    panels = [a_warp, b_warp, c_warp, d_warp]
    titles = [
        "Pipeline A Warp (Current)",
        "Pipeline B Warp (Classical)",
        "Pipeline C Warp (FairScan)",
        "Pipeline D Warp (DocQuadNet)"
    ]
    resized = []
    for p, t in zip(panels, titles):
        h, w = p.shape[:2]
        scale = target_h / float(h)
        nw = int(round(w * scale))
        r = cv2.resize(p, (nw, target_h), interpolation=cv2.INTER_AREA)
        cv2.rectangle(r, (0, 0), (nw, 32), (15, 15, 15), -1)
        cv2.putText(r, t, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
        resized.append(r)
    return np.hstack(resized)


# =====================================================================
# Main Benchmark Runner
# =====================================================================

def main():
    dataset_dir = sys.argv[1] if len(sys.argv) > 1 else "tools/scanpoc/real-input"
    fairscan_path = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\Yy\AppData\Local\Temp\opencode\scanpoc\model\fairscan-segmentation-model.tflite"
    docquad_path = sys.argv[3] if len(sys.argv) > 3 else r"C:\Users\Yy\AppData\Local\Temp\opencode\makeacopy\app\src\main\assets\docquad\docquadnet256_trained_opset17.ort"
    out_dir = sys.argv[4] if len(sys.argv) > 4 else "tools/scanpoc/out"

    os.makedirs(out_dir, exist_ok=True)
    images = [
        f for f in sorted(os.listdir(dataset_dir))
        if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
        and not f.endswith(".overlay.png")
        and not f.endswith(".mask.png")
        and not f.endswith("_comparison.jpg")
        and not "_warp_" in f
    ]

    print(f"=== Starting 4-Pipeline Benchmark on {len(images)} images ===")
    print(f"Dataset: {dataset_dir}")
    print(f"FairScan Oracle: {fairscan_path} (Available: {os.path.exists(fairscan_path)})")
    print(f"DocQuadNet Model: {docquad_path} (Available: {os.path.exists(docquad_path)})")

    fairscan = FairScanOracle(fairscan_path)
    docquad = DocQuadNetPipeline(docquad_path)

    results = []
    pipe_keys = ["pipeline_a_current", "pipeline_b_classical", "pipeline_c_segmentation", "pipeline_d_docquadnet"]

    metrics = {
        k: {
            "document_total": 0,
            "detected_count": 0,
            "direct_usable_count": 0,
            "minor_adjust_count": 0,
            "complete_fail_count": 0,
            "wrong_object_count": 0,
            "all_errors_pct": [],
            "all_times_ms": [],
            "no_doc_total": 0,
            "no_doc_false_positives": 0,
        }
        for k in pipe_keys
    }

    for img_name in images:
        stem = os.path.splitext(img_name)[0]
        img_path = os.path.join(dataset_dir, img_name)
        gt_path = os.path.join(dataset_dir, f"{stem}.gt.json")
        img_bgr = cv2.imread(img_path)
        if img_bgr is None:
            continue

        h, w = img_bgr.shape[:2]
        is_doc = True
        gt_corners = None
        if os.path.exists(gt_path):
            with open(gt_path, "r", encoding="utf-8") as f:
                gt_data = json.load(f)
            is_doc = gt_data.get("documentPresent", True)
            if is_doc:
                gt_corners = np.float32([gt_data["TL"], gt_data["TR"], gt_data["BR"], gt_data["BL"]])

        # Run 4 Pipelines
        res_a = detect_pipeline_a(img_bgr)
        res_b = detect_pipeline_b(img_bgr)
        res_c = fairscan.detect(img_bgr) if fairscan.is_available() else res_a
        res_d = docquad.detect(img_bgr) if docquad.available else res_a

        quad_a, quad_b, quad_c, quad_d = res_a["corners"], res_b["corners"], res_c["corners"], res_d["corners"]

        # Warps
        warp_a = warp_document(img_bgr, quad_a)
        warp_b = warp_document(img_bgr, quad_b)
        warp_c = warp_document(img_bgr, quad_c)
        warp_d = warp_document(img_bgr, quad_d)

        # Overlays
        vis_gt = img_bgr.copy()
        if is_doc and gt_corners is not None:
            cv2.polylines(vis_gt, [np.int32(gt_corners)], True, (0, 255, 255), 6, cv2.LINE_AA)
            cv2.rectangle(vis_gt, (0, 0), (w, 55), (20, 20, 20), -1)
            cv2.putText(vis_gt, f"Manual GT: {stem} [DOCUMENT]", (15, 38), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 2, cv2.LINE_AA)
        else:
            cv2.rectangle(vis_gt, (w // 4, h // 3), (w * 3 // 4, h * 2 // 3), (0, 0, 180), -1)
            cv2.putText(vis_gt, "NO DOCUMENT", (w // 4 + 40, h // 2), cv2.FONT_HERSHEY_SIMPLEX, 1.8, (255, 255, 255), 4, cv2.LINE_AA)

        vis_a = draw_labeled_quad(img_bgr, quad_a, (0, 255, 0), "Pipeline A (Current)", res_a["is_fallback"])
        vis_b = draw_labeled_quad(img_bgr, quad_b, (255, 100, 0), "Pipeline B (Classical)", res_b["is_fallback"])
        vis_c = draw_labeled_quad(img_bgr, quad_c, (0, 200, 255), "Pipeline C (FairScan)", res_c["is_fallback"])
        vis_d = draw_labeled_quad(img_bgr, quad_d, (255, 0, 200), "Pipeline D (DocQuadNet)", res_d["is_fallback"])

        # Save individual outputs
        cv2.imwrite(os.path.join(out_dir, f"{stem}.original.jpg"), img_bgr)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.gt-overlay.jpg"), vis_gt)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.A.overlay.jpg"), vis_a)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.A.warped.jpg"), warp_a)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.B.overlay.jpg"), vis_b)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.B.warped.jpg"), warp_b)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.C.overlay.jpg"), vis_c)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.C.warped.jpg"), warp_c)
        if "mask" in res_c:
            cv2.imwrite(os.path.join(out_dir, f"{stem}.C.mask.png"), res_c["mask"])

        cv2.imwrite(os.path.join(out_dir, f"{stem}.D.overlay.jpg"), vis_d)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.D.warped.jpg"), warp_d)
        if "mask" in res_d:
            cv2.imwrite(os.path.join(out_dir, f"{stem}.D.mask.png"), res_d["mask"])
        if "heatmaps" in res_d:
            cv2.imwrite(os.path.join(out_dir, f"{stem}.D.heatmaps.png"), res_d["heatmaps"])

        # Comparison Sheets
        sheet_6col = build_comparison_sheet_6col(img_bgr, vis_gt, vis_a, vis_b, vis_c, vis_d)
        cv2.imwrite(os.path.join(out_dir, f"{stem}_comparison.jpg"), sheet_6col)

        warp_sheet_4col = build_warp_comparison_4col(warp_a, warp_b, warp_c, warp_d)
        cv2.imwrite(os.path.join(out_dir, f"{stem}_warp_comparison.jpg"), warp_sheet_4col)

        # Evaluation metrics
        entry = {
            "image": img_name,
            "documentPresent": is_doc,
            "width": w,
            "height": h,
        }

        pipeline_runs = [
            ("pipeline_a_current", res_a, quad_a),
            ("pipeline_b_classical", res_b, quad_b),
            ("pipeline_c_segmentation", res_c, quad_c),
            ("pipeline_d_docquadnet", res_d, quad_d),
        ]

        for p_key, p_res, p_quad in pipeline_runs:
            m = metrics[p_key]
            m["all_times_ms"].append(p_res["time_ms"])

            if is_doc:
                m["document_total"] += 1
                if p_res["status"] == "Detected" and not p_res["is_fallback"]:
                    m["detected_count"] += 1

                err_info = compute_corner_errors(gt_corners, p_quad, w, h)
                mean_err = err_info["mean_err_pct"]
                max_err = err_info["max_err_pct"]
                m["all_errors_pct"].append(mean_err)

                if mean_err <= 1.5 and max_err <= 2.5:
                    cat = "DirectUsable"
                    m["direct_usable_count"] += 1
                    warp_usable = True
                elif mean_err <= 4.0 and max_err <= 6.0:
                    cat = "MinorAdjust"
                    m["minor_adjust_count"] += 1
                    warp_usable = False
                else:
                    cat = "CompleteFailure"
                    m["complete_fail_count"] += 1
                    warp_usable = False
                    if p_res["status"] == "Detected":
                        m["wrong_object_count"] += 1

                entry[p_key] = {
                    "status": p_res["status"],
                    "conf": p_res["conf"],
                    "time_ms": p_res["time_ms"],
                    "mean_err_pct": round(mean_err, 2),
                    "max_err_pct": round(max_err, 2),
                    "category": cat,
                    "warp_usable": warp_usable,
                }
            else:
                m["no_doc_total"] += 1
                if p_res["status"] == "Detected" and not p_res["is_fallback"]:
                    m["no_doc_false_positives"] += 1
                    cat = "CompleteFailure"
                    warp_usable = False
                    mean_err = 100.0
                else:
                    cat = "DirectUsable"
                    warp_usable = True
                    mean_err = 0.0

                entry[p_key] = {
                    "status": p_res["status"],
                    "conf": p_res["conf"],
                    "time_ms": p_res["time_ms"],
                    "mean_err_pct": mean_err,
                    "category": cat,
                    "warp_usable": warp_usable,
                }

        results.append(entry)
        print(f"[{stem}] is_doc={is_doc} | A: {entry['pipeline_a_current']['mean_err_pct']}% | B: {entry['pipeline_b_classical']['mean_err_pct']}% | C: {entry['pipeline_c_segmentation']['mean_err_pct']}% | D: {entry['pipeline_d_docquadnet']['mean_err_pct']}%")

    # Aggregate summaries
    summary = {}
    print("\n================== 4-PIPELINE DECISION GATE SUMMARY ==================")
    for p_key, m in metrics.items():
        doc_tot = max(1, m["document_total"])
        det_rate = (m["detected_count"] / doc_tot) * 100.0
        usable_rate = (m["direct_usable_count"] / doc_tot) * 100.0
        minor_rate = (m["minor_adjust_count"] / doc_tot) * 100.0
        fail_rate = (m["complete_fail_count"] / doc_tot) * 100.0
        wrong_rate = (m["wrong_object_count"] / doc_tot) * 100.0

        errors = m["all_errors_pct"]
        mean_err = float(np.mean(errors)) if errors else 0.0
        p95_err = float(np.percentile(errors, 95)) if errors else 0.0
        max_err = float(np.max(errors)) if errors else 0.0
        mean_time = float(np.mean(m["all_times_ms"])) if m["all_times_ms"] else 0.0

        summary[p_key] = {
            "document_total": m["document_total"],
            "detection_rate_pct": round(det_rate, 1),
            "detected_count": m["detected_count"],
            "direct_usable_count": m["direct_usable_count"],
            "direct_usable_rate_pct": round(usable_rate, 1),
            "minor_adjust_count": m["minor_adjust_count"],
            "minor_adjust_rate_pct": round(minor_rate, 1),
            "complete_fail_count": m["complete_fail_count"],
            "complete_fail_rate_pct": round(fail_rate, 1),
            "wrong_object_count": m["wrong_object_count"],
            "wrong_object_rate_pct": round(wrong_rate, 1),
            "mean_corner_error_pct": round(mean_err, 2),
            "p95_corner_error_pct": round(p95_err, 2),
            "max_corner_error_pct": round(max_err, 2),
            "mean_time_ms": round(mean_time, 1),
            "no_doc_total": m["no_doc_total"],
            "no_doc_false_positives": m["no_doc_false_positives"],
        }

        print(f"\n[{p_key}]")
        print(f"  detection_rate: {m['detected_count']}/{m['document_total']} ({det_rate:.1f}%)")
        print(f"  direct_usable: {m['direct_usable_count']}/{m['document_total']} ({usable_rate:.1f}%)")
        print(f"  minor_adjust: {m['minor_adjust_count']}/{m['document_total']} ({minor_rate:.1f}%)")
        print(f"  complete_fail: {m['complete_fail_count']}/{m['document_total']} ({fail_rate:.1f}%)")
        print(f"  wrong_object: {m['wrong_object_count']}/{m['document_total']} ({wrong_rate:.1f}%)")
        print(f"  mean_corner_error_pct: {mean_err:.2f}")
        print(f"  p95_corner_error_pct: {p95_err:.2f}")
        print(f"  max_corner_error_pct: {max_err:.2f}")
        print(f"  mean_time_ms: {mean_time:.1f}")
        print(f"  no_doc_false_positives: {m['no_doc_false_positives']}/{m['no_doc_total']}")

    final_payload = {
        "summary": summary,
        "results": results,
    }

    out_json = os.path.join(out_dir, "results_decision_gate.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(to_serializable(final_payload), f, indent=2)

    print(f"\nAll outputs saved to: {out_dir}")


if __name__ == "__main__":
    main()
