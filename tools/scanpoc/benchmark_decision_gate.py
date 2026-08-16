"""Phase 4B Scanner Decision Gate — Real Dataset Unified Benchmark.

Evaluates:
  Pipeline A: Current Android Production DocumentDetector (replicated in Python)
  Pipeline B: Improved Multi-hypothesis Classical Detector (RANSAC lines + High-res Edge Refinement)
  Pipeline C: Segmentation-first PoC (FairScan TFLite Oracle)

Outputs:
  - Quantitative comparison JSON & Markdown tables
  - Side-by-side Contact Sheets (Original | Current | Classical | Segmentation | GT)
  - Perspective Warped Output comparisons (full-resolution)

Usage:
  py -3.10 tools/scanpoc/benchmark_decision_gate.py [dataset_dir] [model_path] [out_dir]
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
# Geometry & Helper Math (Consistent across all pipelines)
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
    # The top edge is the edge whose midpoint has the smallest y
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


def validate_quad(q, img_w, img_h, min_area_frac=0.10):
    """Check: length > 0, convex, no self-intersection, min area, angle range 45..135 deg."""
    if q is None or len(q) != 4:
        return False
    q = np.asarray(q, np.float64)
    if np.min(np.linalg.norm(np.roll(q, -1, 0) - q, axis=1)) < 1e-4:
        return False
    area = abs(cv2.contourArea(np.int32(q)))
    if area < min_area_frac * img_w * img_h:
        return False
    # Convexity check: cross products have same sign
    edges = np.roll(q, -1, 0) - q
    cr = edges[:, 0] * np.roll(edges, -1, 0)[:, 1] - edges[:, 1] * np.roll(edges, -1, 0)[:, 0]
    if not (np.all(cr > 0) or np.all(cr < 0)):
        return False
    # Corner angles within [45, 135] deg
    for i in range(4):
        v1 = q[i] - q[(i - 1) % 4]
        v2 = q[(i + 1) % 4] - q[i]
        c = np.clip(np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9), -1, 1)
        deg = np.degrees(np.arccos(c))
        if deg < 45 or deg > 135:
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


def warp_perspective_full(img, corners):
    """Warp full resolution image to upright document rectangle."""
    tl, tr, br, bl = corners
    top_w = np.linalg.norm(tr - tl)
    bot_w = np.linalg.norm(br - bl)
    left_h = np.linalg.norm(bl - tl)
    right_h = np.linalg.norm(br - tr)
    ow = int(round(max(top_w, bot_w)))
    oh = int(round(max(left_h, right_h)))
    ow = max(ow, 10)
    oh = max(oh, 10)
    dst = np.float32([[0, 0], [ow - 1, 0], [ow - 1, oh - 1], [0, oh - 1]])
    M = cv2.getPerspectiveTransform(np.float32(corners), dst)
    return cv2.warpPerspective(img, M, (ow, oh), flags=cv2.INTER_LINEAR)


def refine_edges_gradient(img, corners, band_px=12, search=28):
    """Gradient-band edge refinement on full-resolution image."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    mag = np.hypot(gx, gy)
    h, w = img.shape[:2]
    lines = []
    for i in range(4):
        p1, p2 = corners[i], corners[(i + 1) % 4]
        length = int(np.linalg.norm(p2 - p1))
        if length < 10:
            return corners
        t = np.linspace(0, 1, length)
        xc = np.clip((p1[0] + (p2[0] - p1[0]) * t).astype(int), 0, w - 1)
        yc = np.clip((p1[1] + (p2[1] - p1[1]) * t).astype(int), 0, h - 1)
        # Normal vector to edge
        nx, ny = -(p2[1] - p1[1]), (p2[0] - p1[0])
        n = np.hypot(nx, ny) + 1e-9
        nx, ny = nx / n, ny / n
        samples = []
        for off in range(-search, search + 1):
            xs = np.clip((xc + nx * off).astype(int), 0, w - 1)
            ys = np.clip((yc + ny * off).astype(int), 0, h - 1)
            sel = mag[ys, xs] > 45
            if sel.sum() > 0:
                samples.append(np.stack([xs[sel], ys[sel]], axis=1))
        if not samples:
            return corners
        sp = np.concatenate(samples, axis=0).astype(np.float32)
        if len(sp) < 10:
            return corners
        fit = cv2.fitLine(sp, cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        vx, vy, x0, y0 = float(fit[0]), float(fit[1]), float(fit[2]), float(fit[3])
        a_, b_ = vy, -vx
        norm = math.hypot(a_, b_) + 1e-9
        a_, b_ = a_ / norm, b_ / norm
        c_ = -(a_ * x0 + b_ * y0)
        lines.append((a_, b_, c_))

    if len(lines) == 4:
        c = [
            intersect_lines(lines[3], lines[0]),  # TL
            intersect_lines(lines[0], lines[1]),  # TR
            intersect_lines(lines[1], lines[2]),  # BR
            intersect_lines(lines[2], lines[3]),  # BL
        ]
        if all(x is not None for x in c):
            c = np.float32(c)
            if validate_quad(c, w, h, min_area_frac=0.08):
                return c
    return corners


# =====================================================================
# Pipeline A: Current Production OpenCV Detector (from DocumentDetector.kt)
# =====================================================================

def detect_pipeline_a_current(img_bgr):
    """Exact replication of DocumentDetector.kt (downscale, CLAHE, Canny, findContours, approxPolyDP)."""
    t0 = time.perf_counter()
    orig_h, orig_w = img_bgr.shape[:2]
    max_edge = 2048
    longest = max(orig_w, orig_h)
    scale = max_edge / float(longest) if longest > max_edge else 1.0
    sw, sh = int(round(orig_w * scale)), int(round(orig_h * scale))
    scaled = cv2.resize(img_bgr, (sw, sh), interpolation=cv2.INTER_AREA) if scale < 1.0 else img_bgr

    gray = cv2.cvtColor(scaled, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    equalized = clahe.apply(gray)
    blurred = cv2.GaussianBlur(equalized, (5, 5), 0)
    edges = cv2.Canny(blurred, 50.0, 150.0)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    image_area = sw * sh
    best_quad = None
    best_score = 0.0

    for contour in contours:
        perimeter = cv2.arcLength(contour, True)
        if perimeter <= 0:
            continue
        approx = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
        if len(approx) != 4:
            continue
        pts = approx.reshape(4, 2).astype(np.float32)
        # Normalized coordinates relative to scaled image
        norm_pts = pts / np.array([sw, sh], np.float32)
        # Order points TL, TR, BR, BL via sum/diff
        s = norm_pts[:, 0] + norm_pts[:, 1]
        d = norm_pts[:, 1] - norm_pts[:, 0]
        tl = norm_pts[np.argmin(s)]
        br = norm_pts[np.argmax(s)]
        tr = norm_pts[np.argmin(d)]
        bl = norm_pts[np.argmax(d)]
        ordered = np.float32([tl, tr, br, bl])

        # Area & validity check
        quad_area = abs(cv2.contourArea(np.float32(ordered)))
        if quad_area < 0.12:
            continue
        # Convexity
        edg = np.roll(ordered, -1, 0) - ordered
        cr = edg[:, 0] * np.roll(edg, -1, 0)[:, 1] - edg[:, 1] * np.roll(edg, -1, 0)[:, 0]
        if not (np.all(cr > 0) or np.all(cr < 0)):
            continue

        # Score candidate (scoring formula from DocumentDetector.kt)
        area_ratio = quad_area
        area_score = area_ratio / 0.1 if area_ratio < 0.1 else (1.0 if area_ratio <= 0.95 else max(0.0, 1.0 - (area_ratio - 0.95) / 0.05 * 0.8))
        cx, cy = ordered.mean(axis=0)
        center_dist = abs(cx - 0.5) + abs(cy - 0.5)
        center_score = max(0.0, 1.0 - center_dist)

        # Aspect score
        sides = [np.linalg.norm(ordered[(i + 1) % 4] - ordered[i]) for i in range(4)]
        min_side = min(sides)
        max_side = max(sides)
        aspect = min_side / max_side if max_side > 0 else 0
        aspect_score = 1.0 if aspect >= 0.25 else aspect / 0.25

        # Border penalty
        border_penalty = sum(0.2 for p in ordered if p[0] < 0.02 or p[0] > 0.98 or p[1] < 0.02 or p[1] > 0.98)
        border_score = max(0.0, 1.0 - border_penalty)

        # Angle score
        ang_scores = []
        for i in range(4):
            v1 = ordered[(i + 1) % 4] - ordered[i]
            v2 = ordered[(i + 2) % 4] - ordered[(i + 1) % 4]
            n1 = np.linalg.norm(v1)
            n2 = np.linalg.norm(v2)
            if n1 > 0 and n2 > 0:
                dot = np.dot(v1, v2) / (n1 * n2)
                ang_scores.append(max(0.0, 1.0 - abs(dot)))
        angle_score = np.mean(ang_scores) if ang_scores else 0.5

        score = area_score * 0.35 + center_score * 0.25 + aspect_score * 0.20 + border_score * 0.10 + angle_score * 0.10
        if score > best_score:
            best_score = score
            best_quad = ordered

    ms = (time.perf_counter() - t0) * 1000.0
    status = "NotFound"
    corners_full = None
    conf = best_score

    if best_quad is not None and best_score >= 0.30:
        status = "Detected" if best_score >= 0.55 else "LowConfidence"
        corners_full = best_quad * np.array([orig_w, orig_h], np.float32)
    else:
        # Fallback to default 5% inset
        status = "FallbackDefault"
        corners_full = np.float32([
            [0.05 * orig_w, 0.05 * orig_h],
            [0.95 * orig_w, 0.05 * orig_h],
            [0.95 * orig_w, 0.95 * orig_h],
            [0.05 * orig_w, 0.95 * orig_h],
        ])
        conf = 0.0

    return {
        "status": status,
        "conf": conf,
        "corners": corners_full,
        "time_ms": ms,
        "name": "Pipeline A (Current)",
    }


# =====================================================================
# Pipeline B: Improved Multi-hypothesis Classical Detector
# =====================================================================

def detect_pipeline_b_classical(img_bgr):
    """Multi-hypothesis classical detector: multi-thresholding, contour hull, 4-side RANSAC, gradient refinement."""
    t0 = time.perf_counter()
    orig_h, orig_w = img_bgr.shape[:2]
    scale = 1024.0 / max(orig_w, orig_h)
    sw, sh = int(round(orig_w * scale)), int(round(orig_h * scale))
    small = cv2.resize(img_bgr, (sw, sh), interpolation=cv2.INTER_AREA)

    # 1. Multi-channel representation
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    lab = cv2.cvtColor(small, cv2.COLOR_BGR2LAB)
    l_chan = lab[:, :, 0]

    # Hypotheses generation
    hypotheses = []

    # Hypo 1: Otsu on Lab L-channel
    _, b1 = cv2.threshold(l_chan, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    hypotheses.append(b1)

    # Hypo 2: Adaptive Threshold on gray
    b2 = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 35, 8.0)
    hypotheses.append(b2)

    # Hypo 3: Morphological Gradient
    k_grad = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    grad = cv2.morphologyEx(gray, cv2.MORPH_GRADIENT, k_grad)
    _, b3 = cv2.threshold(grad, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    hypotheses.append(b3)

    # Hypo 4: Color contrast against image borders (background estimation)
    border_pixels = np.concatenate([small[0, :], small[-1, :], small[:, 0], small[:, -1]], axis=0)
    bg_color = np.median(border_pixels, axis=0)
    color_dist = np.linalg.norm(small.astype(np.float32) - bg_color, axis=2)
    _, b4 = cv2.threshold(color_dist.astype(np.uint8), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    hypotheses.append(b4)

    best_corners = None
    best_conf = 0.0

    # Evaluate candidates across hypotheses
    for h_idx, binary in enumerate(hypotheses):
        # Morphology cleanup
        k_close = cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7))
        cleaned = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k_close)

        # Connected components
        n, labels, stats, _ = cv2.connectedComponentsWithStats(cleaned, 8)
        if n <= 1:
            continue

        for comp_idx in range(1, n):
            area = stats[comp_idx, cv2.CC_STAT_AREA]
            area_frac = area / float(sw * sh)
            if area_frac < 0.12 or area_frac > 0.98:
                continue

            comp_mask = (labels == comp_idx).astype(np.uint8) * 255
            contours, _ = cv2.findContours(comp_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
            if not contours:
                continue
            cnt = max(contours, key=cv2.contourArea)
            hull = cv2.convexHull(cnt)
            pts = hull.reshape(-1, 2).astype(np.float64)
            if len(pts) < 12:
                continue

            cx, cy = pts.mean(axis=0)
            groups = partition_contour_sides(pts, cx, cy)
            if groups is None:
                continue

            quad = fit_and_intersect_quad(groups, sw, sh)
            if quad is None:
                continue

            # Score quad
            q_area = abs(cv2.contourArea(np.int32(quad))) / float(sw * sh)
            area_score = 1.0 - abs(q_area - 0.55) / 0.55
            conf = 0.5 + 0.5 * max(0.0, area_score)

            if conf > best_conf:
                best_conf = conf
                best_corners = quad

    ms = (time.perf_counter() - t0) * 1000.0
    status = "NotFound"
    corners_full = None

    if best_corners is not None and best_conf >= 0.35:
        status = "Detected"
        # Scale to full res
        corners_full = best_corners / scale
        # Refine on high-res gradient
        corners_full = refine_edges_gradient(img_bgr, corners_full)
    else:
        status = "FallbackDefault"
        corners_full = np.float32([
            [0.05 * orig_w, 0.05 * orig_h],
            [0.95 * orig_w, 0.05 * orig_h],
            [0.95 * orig_w, 0.95 * orig_h],
            [0.05 * orig_w, 0.95 * orig_h],
        ])
        best_conf = 0.0

    return {
        "status": status,
        "conf": best_conf,
        "corners": corners_full,
        "time_ms": ms,
        "name": "Pipeline B (Improved Classical)",
    }


# =====================================================================
# Pipeline C: Segmentation-first PoC (FairScan Oracle)
# =====================================================================

class SegmentationOracle:
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
            return {
                "status": "ModelUnavailable",
                "conf": 0.0,
                "corners": None,
                "time_ms": 0.0,
                "name": "Pipeline C (Segmentation Oracle)",
            }

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
            return {
                "status": "NotFound",
                "conf": 0.0,
                "corners": np.float32([[0.05*orig_w, 0.05*orig_h], [0.95*orig_w, 0.05*orig_h], [0.95*orig_w, 0.95*orig_h], [0.05*orig_w, 0.95*orig_h]]),
                "time_ms": ms,
                "name": "Pipeline C (Segmentation Oracle)",
            }

        areas = stats[1:, cv2.CC_STAT_AREA]
        idx = 1 + int(np.argmax(areas))
        comp = (labels == idx).astype(np.uint8) * 255

        contours, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        if not contours:
            ms = (time.perf_counter() - t0) * 1000.0
            return {
                "status": "NotFound",
                "conf": 0.0,
                "corners": np.float32([[0.05*orig_w, 0.05*orig_h], [0.95*orig_w, 0.05*orig_h], [0.95*orig_w, 0.95*orig_h], [0.05*orig_w, 0.95*orig_h]]),
                "time_ms": ms,
                "name": "Pipeline C (Segmentation Oracle)",
            }

        cnt = max(contours, key=cv2.contourArea)
        pts = cnt.reshape(-1, 2).astype(np.float64)
        cx, cy = pts.mean(axis=0)

        groups = partition_contour_sides(pts, cx, cy)
        corners_model = None
        if groups is not None:
            corners_model = fit_and_intersect_quad(groups, MODEL_IN, MODEL_IN)

        if corners_model is None:
            # Hull fallback
            hull = cv2.convexHull(cnt)
            approx = cv2.approxPolyDP(hull, 3.0, True)
            if len(approx) == 4:
                corners_model = order_corners(approx.reshape(4, 2))

        ms = (time.perf_counter() - t0) * 1000.0
        if corners_model is not None and validate_quad(corners_model, MODEL_IN, MODEL_IN, min_area_frac=0.08):
            sx, sy = orig_w / float(MODEL_IN), orig_h / float(MODEL_IN)
            corners_full = np.float32([(x * sx, y * sy) for x, y in corners_model])
            corners_full = refine_edges_gradient(img_bgr, corners_full)
            return {
                "status": "Detected",
                "conf": float(prob[comp.astype(bool)].mean()),
                "corners": corners_full,
                "time_ms": ms,
                "name": "Pipeline C (Segmentation Oracle)",
            }
        else:
            return {
                "status": "NotFound",
                "conf": 0.0,
                "corners": np.float32([[0.05*orig_w, 0.05*orig_h], [0.95*orig_w, 0.05*orig_h], [0.95*orig_w, 0.95*orig_h], [0.05*orig_w, 0.95*orig_h]]),
                "time_ms": ms,
                "name": "Pipeline C (Segmentation Oracle)",
            }


# =====================================================================
# Main Benchmark Runner
# =====================================================================

def evaluate_predictions(gt_pts, pred_pts, diag):
    """Compute corner errors and categorizations."""
    if pred_pts is None or gt_pts is None:
        return {
            "mean_err_pct": 99.9,
            "max_err_pct": 99.9,
            "corner_errs_pct": [99.9, 99.9, 99.9, 99.9],
            "wrong_object": True,
            "warp_usable": False,
            "category": "CompleteFailure",
        }

    # Corner Euclidean distances
    dists = np.linalg.norm(pred_pts - gt_pts, axis=1)
    errs_pct = dists / diag * 100.0
    mean_err = float(np.mean(errs_pct))
    max_err = float(np.max(errs_pct))

    wrong_object = bool(mean_err > 8.0)
    warp_usable = bool(mean_err <= 2.5 and max_err <= 4.0)

    if mean_err <= 1.5 and max_err <= 2.5:
        category = "DirectUsable"
    elif mean_err <= 4.0 and max_err <= 6.0:
        category = "MinorAdjust"
    else:
        category = "CompleteFailure"

    return {
        "mean_err_pct": round(mean_err, 2),
        "max_err_pct": round(max_err, 2),
        "corner_errs_pct": [round(float(x), 2) for x in errs_pct],
        "wrong_object": wrong_object,
        "warp_usable": warp_usable,
        "category": category,
    }


def draw_overlay_panel(img, corners, label, color):
    """Draw a panel with colored quad overlay and label for contact sheet."""
    panel = img.copy()
    if corners is not None:
        pts = np.int32(corners)
        cv2.polylines(panel, [pts], True, color, 4, cv2.LINE_AA)
        names = ["TL", "TR", "BR", "BL"]
        for p, name in zip(pts, names):
            cv2.circle(panel, tuple(p), 10, color, -1, cv2.LINE_AA)
            cv2.circle(panel, tuple(p), 12, (255, 255, 255), 2, cv2.LINE_AA)
    # Banner
    cv2.rectangle(panel, (0, 0), (panel.shape[1], 40), (20, 20, 20), -1)
    cv2.putText(panel, label, (12, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
    return panel


def run_benchmark(dataset_dir, model_path, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    oracle = SegmentationOracle(model_path)

    images = [
        f for f in sorted(os.listdir(dataset_dir))
        if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")) and not f.endswith(".overlay.png") and not f.endswith(".warped.jpg") and not f.endswith("_comparison.jpg") and not "_warp_" in f
    ]
    if not images:
        print(f"No images found in {dataset_dir}.")
        return

    print(f"=== Starting Benchmark on {len(images)} images ===")
    print(f"Dataset: {dataset_dir}")
    print(f"Oracle model: {model_path} (Available: {oracle.is_available()})")

    results = []

    for img_name in images:
        stem = os.path.splitext(img_name)[0]
        img_path = os.path.join(dataset_dir, img_name)
        gt_path = os.path.join(dataset_dir, f"{stem}.gt.json")
        img = cv2.imread(img_path)
        if img is None:
            continue
        h, w = img.shape[:2]
        diag = math.hypot(w, h)

        # Load Ground Truth
        gt_pts = None
        if os.path.exists(gt_path):
            with open(gt_path, "r", encoding="utf-8") as f:
                gdata = json.load(f)
                gt_pts = np.float32([gdata["TL"], gdata["TR"], gdata["BR"], gdata["BL"]])

        # Run 3 pipelines
        res_a = detect_pipeline_a_current(img)
        res_b = detect_pipeline_b_classical(img)
        res_c = oracle.detect(img)

        eval_a = evaluate_predictions(gt_pts, res_a["corners"], diag)
        eval_b = evaluate_predictions(gt_pts, res_b["corners"], diag)
        eval_c = evaluate_predictions(gt_pts, res_c["corners"], diag)

        # Perspective Warps (full-res)
        warp_a = warp_perspective_full(img, res_a["corners"]) if res_a["corners"] is not None else None
        warp_b = warp_perspective_full(img, res_b["corners"]) if res_b["corners"] is not None else None
        warp_c = warp_perspective_full(img, res_c["corners"]) if res_c["corners"] is not None else None

        # Save warps
        if warp_a is not None:
            cv2.imwrite(os.path.join(out_dir, f"{stem}_warp_A_current.jpg"), warp_a, [cv2.IMWRITE_JPEG_QUALITY, 92])
        if warp_b is not None:
            cv2.imwrite(os.path.join(out_dir, f"{stem}_warp_B_classical.jpg"), warp_b, [cv2.IMWRITE_JPEG_QUALITY, 92])
        if warp_c is not None:
            cv2.imwrite(os.path.join(out_dir, f"{stem}_warp_C_segmentation.jpg"), warp_c, [cv2.IMWRITE_JPEG_QUALITY, 92])

        # Generate Contact Sheet: [Original | Pipeline A | Pipeline B | Pipeline C | Ground Truth]
        thumb_h = 480
        thumb_scale = thumb_h / float(h)
        thumb_w = int(round(w * thumb_scale))

        p_orig = draw_overlay_panel(img, None, "Original", (200, 200, 200))
        p_a = draw_overlay_panel(img, res_a["corners"], f"A: Current ({eval_a['mean_err_pct']}%)", (0, 0, 255))
        p_b = draw_overlay_panel(img, res_b["corners"], f"B: Classical ({eval_b['mean_err_pct']}%)", (0, 200, 255))
        p_c = draw_overlay_panel(img, res_c["corners"], f"C: Seg Oracle ({eval_c['mean_err_pct']}%)", (0, 255, 0))
        p_gt = draw_overlay_panel(img, gt_pts, "Ground Truth", (255, 255, 255))

        panels = [
            cv2.resize(p, (thumb_w, thumb_h), interpolation=cv2.INTER_AREA)
            for p in [p_orig, p_a, p_b, p_c, p_gt]
        ]
        contact_sheet = np.hstack(panels)
        cv2.imwrite(os.path.join(out_dir, f"{stem}_comparison.jpg"), contact_sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])

        item_rec = {
            "image": stem,
            "shape": [w, h],
            "pipeline_a_current": {**res_a, **eval_a},
            "pipeline_b_classical": {**res_b, **eval_b},
            "pipeline_c_segmentation": {**res_c, **eval_c},
        }
        results.append(item_rec)
        print(f"[{stem}] A: {eval_a['mean_err_pct']}% | B: {eval_b['mean_err_pct']}% | C: {eval_c['mean_err_pct']}% (usable: A={eval_a['warp_usable']}, B={eval_b['warp_usable']}, C={eval_c['warp_usable']})")

    # Compute Summary Statistics
    summary = {}
    for p_key, p_name in [
        ("pipeline_a_current", "Pipeline A (Current Canny)"),
        ("pipeline_b_classical", "Pipeline B (Improved Classical)"),
        ("pipeline_c_segmentation", "Pipeline C (Segmentation Oracle)"),
    ]:
        valid_items = [r[p_key] for r in results if r[p_key]["status"] != "ModelUnavailable"]
        if not valid_items:
            continue
        total = len(valid_items)
        detected = sum(1 for r in valid_items if r["status"] in ("Detected", "LowConfidence"))
        direct_usable = sum(1 for r in valid_items if r["category"] == "DirectUsable")
        minor_adjust = sum(1 for r in valid_items if r["category"] == "MinorAdjust")
        complete_fail = sum(1 for r in valid_items if r["category"] == "CompleteFailure")
        wrong_obj = sum(1 for r in valid_items if r["wrong_object"])
        mean_errs = [r["mean_err_pct"] for r in valid_items]
        p95_err = float(np.percentile(mean_errs, 95))

        summary[p_name] = {
            "total": total,
            "detection_rate": f"{detected}/{total} ({detected/total*100:.1f}%)",
            "direct_usable": f"{direct_usable}/{total} ({direct_usable/total*100:.1f}%)",
            "minor_adjust": f"{minor_adjust}/{total} ({minor_adjust/total*100:.1f}%)",
            "complete_fail": f"{complete_fail}/{total} ({complete_fail/total*100:.1f}%)",
            "wrong_object_rate": f"{wrong_obj}/{total} ({wrong_obj/total*100:.1f}%)",
            "mean_corner_error_pct": round(float(np.mean(mean_errs)), 2),
            "p95_corner_error_pct": round(p95_err, 2),
            "mean_time_ms": round(float(np.mean([r["time_ms"] for r in valid_items])), 1),
        }

    output_payload = {
        "summary": summary,
        "results": to_serializable(results),
    }
    with open(os.path.join(out_dir, "results_decision_gate.json"), "w", encoding="utf-8") as f:
        json.dump(output_payload, f, indent=2, ensure_ascii=False)

    print("\n================== SUMMARY ==================")
    for k, v in summary.items():
        print(f"\n[{k}]")
        for sk, sv in v.items():
            print(f"  {sk}: {sv}")
    print(f"\nAll outputs saved to: {out_dir}")


if __name__ == "__main__":
    ds_dir = sys.argv[1] if len(sys.argv) > 1 else "tools/scanpoc/real-input"
    m_path = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\Yy\AppData\Local\Temp\opencode\scanpoc\model\fairscan-segmentation-model.tflite"
    o_dir = sys.argv[3] if len(sys.argv) > 3 else "tools/scanpoc/out"
    run_benchmark(ds_dir, m_path, o_dir)
