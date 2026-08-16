"""Phase 4B.1 Milestone 1 — Offline Fixture Scanner (PoC).

Segmentation-first pipeline (per Phase 4B.1 spec, NOT FairScan's code):
  image -> LiteRT segmentation (256x256) -> mask cleanup -> connected
  components -> document contour -> boundary partition (4 sides) ->
  RANSAC line fit -> line intersections -> validate -> scale to full res
  -> gradient-band edge refinement -> perspective warp -> auto enhance.

Model: fairscan-segmentation-model.tflite v1.2.0 (GPLv3, reference/PoC only).

Usage: python scan_poc.py <fixtures_dir> <model.tflite> <out_dir>
"""
import json
import os
import sys
import time

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL_IN = 256
MODEL_THRESHOLD = 0.5
MIN_AREA_FRAC = 0.02
MAX_AREA_FRAC = 0.96  # almost whole frame -> low confidence, still allowed
LINE_RANSAC_TRIALS = 200
LINE_RANSAC_THRESH = 2.5  # px in mask space


# ------------------------------------------------------------------ mask

def preprocess(img_bgr):
    small = cv2.resize(img_bgr, (MODEL_IN, MODEL_IN), interpolation=cv2.INTER_AREA)
    x = small.astype(np.float32)
    x = (x - 127.5) / 127.5
    return x[np.newaxis, ...]


def infer(interp, x):
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    logits = interp.get_tensor(out["index"])[0, :, :, 0]  # (256,256) float
    return 1.0 / (1.0 + np.exp(-logits))


def clean_mask(prob):
    """threshold -> close -> hole fill -> keep document component."""
    m = (prob >= MODEL_THRESHOLD).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, k)
    # hole fill via flood fill from borders
    h, w = m.shape
    ff = m.copy()
    cv2.floodFill(ff, None, (0, 0), 255)
    ff = cv2.bitwise_not(ff)
    m = cv2.bitwise_or(m, ff)
    # connected components
    n, labels, stats, _ = cv2.connectedComponentsWithStats(m, 8)
    if n <= 1:
        return None, 0.0
    areas = stats[1:, cv2.CC_STAT_AREA]
    idx = 1 + int(np.argmax(areas))
    area = stats[idx, cv2.CC_STAT_AREA]
    comp = (labels == idx).astype(np.uint8) * 255
    return comp, area / float(w * h)


# ------------------------------------------------------------- boundary

def partition_sides(contour_pts, cx, cy):
    """Split contour points into 4 side clusters.

    For a convex quad the contour radius (distance to centroid) peaks at the
    four corners -> find corner directions by radius histogram, then split the
    contour by the midpoints between consecutive corner directions.
    """
    pts = contour_pts.reshape(-1, 2).astype(np.float64)
    ang = np.degrees(np.arctan2(pts[:, 1] - cy, pts[:, 0] - cx)) % 360.0
    rad = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
    # radius histogram, 5 deg bins, wrapped smoothing
    bins = 72
    hist = np.zeros(bins)
    for a, r in zip(ang, rad):
        hist[int(a / 5) % bins] += r
    hist = np.convolve(np.concatenate([hist, hist, hist]), np.ones(5) / 5, "same")[bins:2 * bins]
    # local maxima -> candidate corners
    peaks = []
    for i in range(bins):
        left = hist[(i - 1) % bins]
        right = hist[(i + 1) % bins]
        if hist[i] >= left and hist[i] >= right:
            peaks.append(i)
    if len(peaks) >= 4:
        # keep top-4 with >= 40 deg separation (greedy)
        order = sorted(peaks, key=lambda i: hist[i], reverse=True)
        keep = []
        for p in order:
            ok = all(min(abs(p - q), bins - abs(p - q)) * 5 >= 40 for q in keep)
            if ok:
                keep.append(p)
            if len(keep) == 4:
                break
        if len(keep) == 4:
            corners = sorted(keep)
            # edges lie BETWEEN consecutive corner directions -> cut AT corners:
            # arc[k] = [corner_k, corner_{k+1}) mod 360
            cuts = [c * 5 for c in corners]
            a_rep = np.tile(ang, (4, 1))
            cuts_arr = np.array(cuts + [cuts[0] + 360])
            sel = np.zeros((4, len(ang)), bool)
            for k in range(4):
                lo = cuts_arr[k]
                hi = cuts_arr[k + 1]
                if k == 3:
                    sel[k] = (a_rep[k] >= lo) | (a_rep[k] < hi - 360)
                else:
                    sel[k] = (a_rep[k] >= lo) & (a_rep[k] < hi)
            groups = [pts[sel[k]] for k in range(4)]
            groups = [g for g in groups if len(g) >= 4]
            return groups
    # fallback: hull + approxPolyDP partition
    hull = cv2.convexHull(contour_pts)
    approx = cv2.approxPolyDP(hull, 2.0, True)
    if len(approx) == 4:
        # split contour by nearest hull corner index (walk along contour)
        corners4 = approx.reshape(4, 2).astype(np.float64)
        nearest = [int(np.argmin(np.hypot(pts[:, 0] - c[0], pts[:, 1] - c[1]))) for c in corners4]
        nearest = sorted(nearest)
        idx = np.concatenate([nearest, [nearest[0] + len(pts)]])
        groups = []
        for k in range(4):
            g = np.concatenate([pts[idx[k]:], pts[:idx[(k + 1) % 4]]]) if idx[k] > idx[(k + 1) % 4] \
                else pts[idx[k]:idx[(k + 1) % 4]]
            groups.append(g)
        return [g for g in groups if len(g) >= 4]
    return []


def ransac_line(pts, trials=LINE_RANSAC_TRIALS, thresh=LINE_RANSAC_THRESH):
    """Robust line fit: (a, b, c) with a*x + b*y + c = 0, unit normal."""
    rng = np.random.default_rng(0)
    best = None
    for _ in range(trials):
        i, j = rng.choice(len(pts), 2, replace=False)
        p, q = pts[i], pts[j]
        dx, dy = q - p
        if abs(dx) < 1e-6 and abs(dy) < 1e-6:
            continue
        a, b = dy, -dx
        n = np.hypot(a, b)
        a, b = a / n, b / n
        c = -(a * p[0] + b * p[1])
        d = np.abs(a * pts[:, 0] + b * pts[:, 1] + c)
        inl = d < thresh
        k = int(inl.sum())
        if best is None or k > best[0]:
            best = (k, d[inl].mean() if k else 0.0, (a, b, c))
    if best is None:
        return None
    k, res, (a, b, c) = best
    inl = np.abs(a * pts[:, 0] + b * pts[:, 1] + c) < thresh
    # least-squares refit on inliers
    if inl.sum() >= 2:
        xs, ys = pts[inl][:, 0], pts[inl][:, 1]
        fit = np.asarray(cv2.fitLine(np.float32(pts[inl]), cv2.DIST_L2, 0, 0.01, 0.01)).ravel()
        vx, vy, px, py = float(fit[0]), float(fit[1]), float(fit[2]), float(fit[3])
        a, b = float(vy), float(-vx)
        n = np.hypot(a, b)
        a, b = a / n, b / n
        c = -(a * float(px) + b * float(py))
    return {"line": (a, b, c), "inliers": int(inl.sum()), "total": len(pts),
            "residual": res}


def intersect(l1, l2):
    a1, b1, c1 = l1["line"]
    a2, b2, c2 = l2["line"]
    det = a1 * b2 - a2 * b1
    if abs(det) < 1e-9:
        return None
    return np.array([(b1 * c2 - b2 * c1) / det, (a2 * c1 - a1 * c2) / det])


def order_corners(corners):
    """Label corners TL, TR, BR, BL.

    The top edge is the edge with the smallest mean y (its two endpoints are
    the top corners); TL/TR split by x, then BL/BR by x. Uniquely determines
    the cyclic order TL->TR->BR->BL for any convex quad.
    """
    c = np.array(corners, np.float64)
    # find the edge with smallest mean y -> top edge
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
    return c[[ti, tj, br, bl]]  # TL, TR, BR, BL


def validate_quad(q, img_w, img_h):
    """clockwise, convex, no self-intersection, min area, plausible angles."""
    if q is None or len(q) != 4:
        return False
    q = np.asarray(q, np.float64)
    if np.min(np.linalg.norm(np.roll(q, -1, 0) - q, axis=1)) < 1e-6:
        return False
    area = abs(cv2.contourArea(np.int32(q)))
    if area < MIN_AREA_FRAC * img_w * img_h:
        return False
    # convex: all cross products same sign
    edges = np.roll(q, -1, 0) - q
    cr = edges[:, 0] * np.roll(edges, -1, 0)[:, 1] - edges[:, 1] * np.roll(edges, -1, 0)[:, 0]
    if not (np.all(cr > 0) or np.all(cr < 0)):
        return False
    # corner angles plausible (45..135 deg)
    for i in range(4):
        v1 = q[i] - q[(i - 1) % 4]
        v2 = q[(i + 1) % 4] - q[i]
        c = np.clip(np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9), -1, 1)
        deg = np.degrees(np.arccos(c))
        if deg < 45 or deg > 135:
            return False
    return True


# ------------------------------------------------------------- geometry

def mask_to_quad(comp, img_w, img_h, prob):
    """Mask -> contour -> 4 side clusters -> RANSAC lines -> corners."""
    contours, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None, None
    contour = max(contours, key=cv2.contourArea)
    pts = contour.reshape(-1, 2).astype(np.float64)
    cx, cy = pts.mean(axis=0)
    groups = partition_sides(contour, cx, cy)
    if len(groups) != 4:
        return None, None
    lines = [ransac_line(g) for g in groups]
    if any(l is None for l in lines):
        return None, None
    # order lines: assign TL..BL clockwise by line midpoint angle
    mids = []
    for l in lines:
        a, b, c = l["line"]
        # closest point to centroid on the line
        t = -(a * cx + b * cy + c)
        mids.append(np.array([cx + a * t, cy + b * t]))
    mids = np.array(mids)
    ang = np.arctan2(mids[:, 1] - cy, mids[:, 0] - cx)
    # side identity from angle: BR(-45..45), BL(45..135), TL(135..225), TR(225..315)
    side_of = []
    for a in np.degrees(ang):
        a %= 360
        if -45 <= a < 45:
            side_of.append("BR")
        elif 45 <= a < 135:
            side_of.append("BL")
        elif 135 <= a < 225:
            side_of.append("TL")
        else:
            side_of.append("TR")
    if sorted(side_of) != ["BL", "BR", "TL", "TR"]:
        return None, None
    def get(s):
        return lines[side_of.index(s)]
    corners = [
        intersect(get("TL"), get("TR")),
        intersect(get("TR"), get("BR")),
        intersect(get("BR"), get("BL")),
        intersect(get("BL"), get("TL")),
    ]
    if any(c is None for c in corners):
        return None, None
    corners = order_corners(corners)
    if not validate_quad(corners, MODEL_IN, MODEL_IN):
        return None, None
    sx, sy = img_w / MODEL_IN, img_h / MODEL_IN
    corners = np.float32([(x * sx, y * sy) for x, y in corners])
    return corners, {"lines": lines, "side_of": side_of}


def confidence(prob, comp, corners, lines, img):
    """Weighted combination per Phase 4B.1 spec (values 0..1)."""
    comp_f = comp.astype(bool)
    model_conf = float(prob[comp_f].mean())
    area_frac = float(comp_f.sum()) / comp.size
    area_score = max(0.0, 1.0 - abs(area_frac - 0.35) / 0.35)
    # continuity: fraction of contour points inliers of the 4 lines
    contours, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if contours:
        pts = max(contours, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
        side_of = lines["side_of"]
        inl_total = 0
        for l, s in zip(lines["lines"], side_of):
            a, b, c = l["line"]
            d = np.abs(a * pts[:, 0] + b * pts[:, 1] + c)
            # count only points whose angle matches this side's cluster
            mask = np.abs(a * pts[:, 0] + b * pts[:, 1] + c) < LINE_RANSAC_THRESH
            inl_total += int(mask.sum())
        continuity = inl_total / len(pts)
    else:
        continuity = 0.0
    res = np.mean([l["residual"] for l in lines["lines"]]) if lines["lines"] else 1.0
    line_score = max(0.0, 1.0 - res / LINE_RANSAC_THRESH)
    # edge support: gradient near predicted edges in full-res image
    edge_score = edge_support(img, corners)
    quad_ok = 1.0
    conf = (0.30 * model_conf + 0.15 * area_score + 0.10 * continuity +
            0.20 * line_score + 0.15 * edge_score + 0.10 * quad_ok)
    return conf, {"model": model_conf, "area_frac": area_frac, "area_score": area_score,
                  "continuity": continuity, "line_score": line_score,
                  "edge_support": edge_score}


def edge_support(img, corners, band_px=8):
    """Fraction of band samples along predicted edges with strong gradient."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    mag = np.hypot(gx, gy)
    h, w = img.shape[:2]
    hits, total = 0, 0
    for i in range(4):
        p1, p2 = corners[i], corners[(i + 1) % 4]
        length = int(np.linalg.norm(p2 - p1))
        if length == 0:
            continue
        t = np.linspace(0, 1, length)
        xs = np.clip((p1[0] + (p2[0] - p1[0]) * t).astype(int), 0, w - 1)
        ys = np.clip((p1[1] + (p2[1] - p1[1]) * t).astype(int), 0, h - 1)
        vals = mag[ys, xs]
        hits += int((vals > 60).sum())
        total += length
    return hits / max(total, 1)


def refine_edges(img, corners, band_px=10, search=24):
    """Gradient-band refinement: for each edge, collect strong-gradient
    samples in a narrow band around the predicted line, refit robustly,
    recompute intersections."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    mag = np.hypot(gx, gy)
    h, w = img.shape[:2]
    lines = []
    for i in range(4):
        p1, p2 = corners[i], corners[(i + 1) % 4]
        length = int(np.linalg.norm(p2 - p1))
        if length == 0:
            return corners
        t = np.linspace(0, 1, length)
        xc = np.clip((p1[0] + (p2[0] - p1[0]) * t).astype(int), 0, w - 1)
        yc = np.clip((p1[1] + (p2[1] - p1[1]) * t).astype(int), 0, h - 1)
        # band: perpendicular offsets around the edge
        nx, ny = -(p2[1] - p1[1]), (p2[0] - p1[0])
        n = np.hypot(nx, ny)
        nx, ny = nx / n, ny / n
        samples = []
        for off in range(-search, search + 1):
            xs = np.clip((xc + nx * off).astype(int), 0, w - 1)
            ys = np.clip((yc + ny * off).astype(int), 0, h - 1)
            sel = mag[ys, xs] > 50
            if sel.sum() > 0:
                samples.append(np.stack([xs[sel], ys[sel]], axis=1))
        if not samples:
            return corners
        sp = np.concatenate(samples, axis=0).astype(np.float64)
        # pick the gradient line closest to prediction among top candidates
        best = None
        for _ in range(60):
            fit = np.asarray(cv2.fitLine(np.float32(sp), cv2.DIST_HUBER, 0, 0.01, 0.01)).ravel()
            vx, vy, x0, y0 = float(fit[0]), float(fit[1]), float(fit[2]), float(fit[3])
            a_, b_ = vy, -vx
            norm = np.hypot(a_, b_)
            a_, b_ = a_ / norm, b_ / norm
            c_ = -(a_ * x0 + b_ * y0)
            d = np.abs(a_ * sp[:, 0] + b_ * sp[:, 1] + c_)
            inl = d < band_px
            if best is None or inl.sum() > best[1]:
                best = ((a_, b_, c_), int(inl.sum()))
        lines.append({"line": best[0], "inliers": best[1], "total": len(sp),
                      "residual": 0.0})
    # corner identity: TL..BR known from input order
    c = [intersect(lines[3], lines[0]), intersect(lines[0], lines[1]),
         intersect(lines[1], lines[2]), intersect(lines[2], lines[3])]
    if any(x is None for x in c):
        return corners
    c = np.float32(c)
    if validate_quad(c, w, h):
        return c
    return corners


# ------------------------------------------------------------------ warp

def warp_document(img, corners):
    """Perspective transform to max(top,bottom) x max(left,right)."""
    tl, tr, br, bl = corners
    top_w = np.linalg.norm(tr - tl)
    bot_w = np.linalg.norm(br - bl)
    left_h = np.linalg.norm(bl - tl)
    right_h = np.linalg.norm(br - tr)
    ow = int(round(max(top_w, bot_w)))
    oh = int(round(max(left_h, right_h)))
    dst = np.float32([[0, 0], [ow - 1, 0], [ow - 1, oh - 1], [0, oh - 1]])
    M = cv2.getPerspectiveTransform(np.float32(corners), dst)
    return cv2.warpPerspective(img, M, (ow, oh))


def enhance_auto(img):
    """Illumination normalization + mild CLAHE + mild sharpen."""
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    lf = l.astype(np.float32)
    illum = cv2.GaussianBlur(lf, (0, 0), max(10, min(l.shape) // 8))
    target = np.percentile(illum, 85)
    lf = lf * (target / np.maximum(illum, 1))
    lf = np.clip(lf, 0, 255).astype(np.uint8)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    lf = clahe.apply(lf)
    lab = cv2.merge([lf, a, b])
    out = cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)
    blur = cv2.GaussianBlur(out, (0, 0), 1.0)
    out = cv2.addWeighted(out, 1.25, blur, -0.25, 0)
    return out


# ------------------------------------------------------------------ main

def main():
    fixtures_dir, model_path, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out_dir, exist_ok=True)
    interp = Interpreter(model_path=model_path, num_threads=2)
    interp.allocate_tensors()

    results = []
    for name in sorted(os.listdir(fixtures_dir)):
        if not name.endswith(".jpg"):
            continue
        stem = name[:-4]
        gt = json.load(open(os.path.join(fixtures_dir, stem + ".gt.json")))
        gt_pts = np.float32([gt["TL"], gt["TR"], gt["BR"], gt["BL"]])
        img = cv2.imread(os.path.join(fixtures_dir, name))
        h, w = img.shape[:2]
        diag = np.hypot(w, h)

        t0 = time.perf_counter()
        prob = infer(interp, preprocess(img))
        infer_ms = (time.perf_counter() - t0) * 1000

        comp, area_frac = clean_mask(prob)
        status = "no-document"
        corners = None
        conf = 0.0
        if comp is not None:
            corners, lines = mask_to_quad(comp, w, h, prob)
            if corners is not None:
                corners = refine_edges(img, corners)
                conf, parts = confidence(prob, comp, corners, lines, img)
                status = "ok"

        # outputs
        cv2.imwrite(os.path.join(out_dir, f"{stem}.mask.png"),
                    comp if comp is not None else np.zeros((256, 256), np.uint8))
        overlay = img.copy()
        cv2.polylines(overlay, [np.int32(gt_pts)], True, (0, 0, 255), 3)
        if corners is not None:
            cv2.polylines(overlay, [np.int32(corners)], True, (0, 255, 0), 3)
            for p in np.int32(corners):
                cv2.circle(overlay, tuple(p), 7, (0, 255, 0), -1)
        cv2.imwrite(os.path.join(out_dir, f"{stem}.overlay.png"), overlay)
        if corners is not None:
            warped = warp_document(img, corners)
            cv2.imwrite(os.path.join(out_dir, f"{stem}.warped.jpg"), warped,
                        [cv2.IMWRITE_JPEG_QUALITY, 90])
            warped_auto = enhance_auto(warped)
            cv2.imwrite(os.path.join(out_dir, f"{stem}.warped_auto.jpg"), warped_auto,
                        [cv2.IMWRITE_JPEG_QUALITY, 90])

        # metrics
        errs = None
        if corners is not None:
            d = np.linalg.norm(corners - gt_pts, axis=1) / diag * 100.0
            errs = d
            rec = {"fixture": stem, "status": status, "infer_ms": round(infer_ms, 1),
                   "conf": round(conf, 3),
                   "err_mean_pct": round(float(d.mean()), 3),
                   "err_max_pct": round(float(d.max()), 3),
                   "err_per_corner": [round(float(x), 3) for x in d],
                   "area_frac": round(area_frac, 3),
                   "conf_parts": {k: round(v, 3) for k, v in parts.items()}}
        else:
            rec = {"fixture": stem, "status": status, "infer_ms": round(infer_ms, 1),
                   "conf": 0.0, "err_mean_pct": None, "err_max_pct": None,
                   "err_per_corner": None, "area_frac": round(area_frac, 3)}
        results.append(rec)
        print(json.dumps(rec, ensure_ascii=False))

    oks = [r for r in results if r["status"] == "ok"]
    print("\n=== summary ===")
    print(f"ok: {len(oks)}/{len(results)}")
    if oks:
        means = [r["err_mean_pct"] for r in oks]
        maxes = [r["err_max_pct"] for r in oks]
        all_corners = [e for r in oks for e in r["err_per_corner"]]
        all_corners.sort()
        p95 = all_corners[int(0.95 * (len(all_corners) - 1))]
        print(f"mean corner err: {np.mean(means):.3f}% diag")
        print(f"95th percentile (all corners): {p95:.3f}% diag")
        print(f"max corner err: {np.max(maxes):.3f}% diag")
        print(f"mean inference: {np.mean([r['infer_ms'] for r in oks]):.1f} ms")
    json.dump(results, open(os.path.join(out_dir, "results.json"), "w"), indent=2)


if __name__ == "__main__":
    main()