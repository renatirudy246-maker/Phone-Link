"""Phase 4B.1 Milestone 1 — synthetic fixture generator.

Renders 7 document-photo fixtures (A-G) with known ground-truth quads
so the segmentation-first pipeline can be scored against exact corners.

Usage: python make_fixtures.py <out_dir>
"""
import json
import math
import os
import random
import sys

import cv2
import numpy as np

W, H = 1280, 960


# ---------------------------------------------------------------- helpers

def rng(seed):
    return random.Random(seed)


def homography_from_quad(dst_pts, doc_w, doc_h):
    """Homography mapping a doc_w x doc_h rectangle to dst_pts (4 pts)."""
    src = np.float32([[0, 0], [doc_w, 0], [doc_w, doc_h], [0, doc_h]])
    return cv2.getPerspectiveTransform(src, np.float32(dst_pts))


def warp_content(canvas, content, Hmat):
    """Warp a content (BGR) plane via Hmat and paste onto canvas (canvas size)."""
    h, w = canvas.shape[:2]
    warped = cv2.warpPerspective(content, Hmat, (w, h), borderValue=(0, 0, 0))
    mask = cv2.warpPerspective(
        np.full(content.shape[:2], 255, np.uint8), Hmat, (w, h), borderValue=0
    )
    mask3 = cv2.merge([mask] * 3).astype(np.float32) / 255.0
    canvas[:] = canvas.astype(np.float32) * (1 - mask3) + warped.astype(np.float32) * mask3
    return canvas.astype(np.uint8)


def fake_text_lines(w, h, r, font_size=20, color=(40, 40, 50), hand=False, ink_jitter=0.0):
    """Render pseudo text (print or handwriting) on a white plane (pure numpy)."""
    img = np.full((h, w, 3), 255, np.uint8)
    glyph_h = max(4, font_size - 8)
    if hand:
        # wavy pencil strokes
        for _ in range(int(h / (glyph_h + 8))):
            y = r.randint(glyph_h + 4, h - glyph_h)
            x = r.randint(0, 20)
            length = r.randint(180, 520)
            xs = np.linspace(x, min(w - 10, x + length), 200)
            ys = y + 6 * np.sin(np.linspace(0, r.randint(4, 9) * np.pi, 200))
            c = tuple(max(0, min(255, v + r.randint(-int(ink_jitter), int(ink_jitter)))) for v in color)
            for x0, y0, x1, y1 in zip(xs[:-1], ys[:-1], xs[1:], ys[1:]):
                cv2.line(img, (int(x0), int(y0)), (int(x1), int(y1)), c, 1)
    else:
        # glyph blobs in rows
        y = glyph_h + 8
        while y < h - glyph_h:
            x = r.randint(0, 14)
            while x < w - 20:
                gw = r.randint(3, max(4, glyph_h // 2))
                gh = r.randint(max(2, glyph_h - 3), glyph_h)
                v = r.randint(-int(ink_jitter), int(ink_jitter))
                c = tuple(max(0, min(255, cc + v)) for cc in color)
                cv2.rectangle(img, (x, y - gh), (x + gw, y), c, -1)
                x += gw + r.randint(1, 3)
            y += glyph_h + r.randint(6, 16)
    return img


def wood_texture(w, h, r):
    base = np.array([156, 116, 74], np.uint8)  # brown
    img = np.zeros((h, w, 3), np.float32) + base.astype(np.float32)
    for _ in range(220):
        y = r.randint(0, h - 1)
        x0 = r.randint(0, w)
        shift = r.randint(-40, 40)
        lw = r.randint(1, 5)
        v = r.randint(-35, 35)
        img[max(0, y):y + lw, :] += v
        if r.random() < 0.4:
            for i in range(lw):
                row = y + i
                if 0 <= row < h:
                    off = int(shift * i / lw)
                    img[row, max(0, x0 + off):, :] += v * 0.6
    noise = np.random.default_rng(1).integers(0, 12, (h, w, 1)).astype(np.float32)
    img += noise
    return np.clip(img, 0, 255).astype(np.uint8)


def dark_table(w, h, r):
    img = np.full((h, w, 3), 26, np.uint8)
    img += np.random.default_rng(r.randrange(1 << 30)).integers(0, 10, (h, w, 1)).astype(np.uint8)
    # subtle fabric grain
    for _ in range(300):
        x = r.randint(0, w)
        y = r.randint(0, h)
        bh, bw = r.randint(1, 3), r.randint(20, 160)
        sl = img[max(0, y):y + bh, max(0, x):x + bw]
        img[max(0, y):y + bh, max(0, x):x + bw] = \
            np.clip(sl.astype(np.int16) + r.randint(-6, 6), 0, 255).astype(np.uint8)
    return np.clip(img, 0, 255).astype(np.uint8)


def light_table(w, h, r):
    img = np.full((h, w, 3), 196, np.uint8)
    img += np.random.default_rng(r.randrange(1 << 30)).integers(0, 10, (h, w, 1)).astype(np.uint8)
    for _ in range(150):
        row = r.randint(0, h - 1)
        img[row, :] = np.clip(img[row, :].astype(np.int16) - r.randint(2, 8), 0, 255).astype(np.uint8)
    return np.clip(img, 0, 255).astype(np.uint8)


def monitor_rectangles(canvas, r):
    """Background with a monitor + keyboard (rectangle distractors)."""
    # monitor
    mx, my = r.randint(40, 200), r.randint(40, 260)
    mw, mh = r.randint(260, 360), r.randint(200, 280)
    cv2.rectangle(canvas, (mx, my), (mx + mw, my + mh), (45, 45, 45), -1)
    cv2.rectangle(canvas, (mx + 12, my + 12), (mx + mw - 12, my + mh - 12), (120, 140, 190), -1)
    cv2.rectangle(canvas, (mx + mw // 2 - 40, my + mh), (mx + mw // 2 + 40, my + mh + 22), (60, 60, 60), -1)
    cv2.rectangle(canvas, (mx + mw // 2 - 80, my + mh + 22), (mx + mw // 2 + 80, my + mh + 30), (60, 60, 60), -1)
    # keyboard
    kx, ky = r.randint(40, 240), r.randint(560, 760)
    kw, kh = r.randint(360, 480), r.randint(90, 130)
    cv2.rectangle(canvas, (kx, ky), (kx + kw, ky + kh), (70, 70, 70), -1)
    for row in range(3):
        for col in range(12):
            cx = kx + 14 + col * (kw - 28) // 12
            cy = ky + 12 + row * (kh - 24) // 3
            cv2.rectangle(canvas, (cx, cy), (cx + (kw - 28) // 12 - 6, cy + (kh - 24) // 3 - 4), (30, 30, 30), -1)
    return canvas


def add_shadow(canvas, quad, r, strength=110, blur=25, dx=18, dy=26):
    """Soft cast shadow from quad polygon."""
    sh = np.zeros((H, W), np.uint8)
    cv2.fillConvexPoly(sh, np.int32(quad), 255)
    sh = cv2.GaussianBlur(sh, (0, 0), blur)
    M = np.float32([[1, 0, dx], [0, 1, dy]])
    sh = cv2.warpAffine(sh, M, (W, H))
    shadow = cv2.merge([sh] * 3).astype(np.float32) / 255.0
    canvas[:] = np.clip(
        canvas.astype(np.float32) * (1 - strength / 255.0 * shadow), 0, 255
    ).astype(np.uint8)
    return canvas


def paper_lighting(content, r, direction=(0.25, -0.4), strength=0.16):
    """Multiplicative directional lighting gradient on the paper plane."""
    h, w = content.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    g = (xx / w - 0.5) * direction[0] + (yy / h - 0.5) * direction[1]
    g = 1 + strength * (g - g.mean())
    return np.clip(content.astype(np.float32) * g[..., None], 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- fixtures

def fixture_a(out):
    """A: white paper + black table."""
    r = rng(101)
    canvas = dark_table(W, H, r)
    doc = fake_text_lines(1000, 1400, r, ink_jitter=4)
    doc = paper_lighting(doc, r)
    quad = np.float32([(180, 130), (1120, 100), (1100, 830), (200, 860)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_b(out):
    """B: book page + wood table."""
    r = rng(202)
    canvas = wood_texture(W, H, r)
    doc = fake_text_lines(1000, 1400, r, font_size=21, color=(52, 46, 38), ink_jitter=5)
    doc[:, :, :] = np.clip(doc.astype(np.float32) * 0.96, 0, 255).astype(np.uint8)  # cream paper
    doc = paper_lighting(doc, r)
    quad = np.float32([(150, 120), (1140, 90), (1120, 850), (170, 880)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=90)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_c(out):
    """C: white paper + light table."""
    r = rng(303)
    canvas = light_table(W, H, r)
    doc = fake_text_lines(1000, 1400, r, ink_jitter=3)
    doc = paper_lighting(doc, r, strength=0.05)
    quad = np.float32([(170, 150), (1130, 110), (1110, 840), (180, 870)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=55, blur=35)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_d(out):
    """D: with shadow across paper + background."""
    r = rng(404)
    canvas = dark_table(W, H, r)
    doc = fake_text_lines(1000, 1400, r, ink_jitter=4)
    doc = paper_lighting(doc, r)
    quad = np.float32([(160, 140), (1130, 110), (1105, 845), (175, 875)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=150, blur=40, dx=40, dy=60)
    # hard shadow band across lower-left of paper
    sh = np.zeros((H, W), np.uint8)
    band = np.int32([(120, 430), (800, 400), (800, 560), (120, 590)])
    cv2.fillConvexPoly(sh, band, 255)
    sh = cv2.GaussianBlur(sh, (0, 0), 18)
    sh3 = cv2.merge([sh] * 3).astype(np.float32) / 255.0
    canvas[:] = np.clip(canvas.astype(np.float32) * (1 - 0.45 * sh3), 0, 255).astype(np.uint8)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_e(out):
    """E: oblique shot, strong perspective (left near / right far)."""
    r = rng(505)
    canvas = dark_table(W, H, r)
    doc = fake_text_lines(1000, 1400, r, ink_jitter=4)
    doc = paper_lighting(doc, r)
    quad = np.float32([(100, 400), (1200, 150), (1150, 780), (160, 900)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=100, dx=20, dy=30)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_f(out):
    """F: handwritten page."""
    r = rng(606)
    canvas = light_table(W, H, r)
    doc = fake_text_lines(1000, 1400, r, font_size=26, color=(70, 66, 62), hand=True)
    doc = paper_lighting(doc, r, strength=0.06)
    quad = np.float32([(170, 130), (1130, 100), (1110, 850), (180, 880)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=60, blur=30)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


def fixture_g(out):
    """G: background with rectangle objects (monitor/keyboard)."""
    r = rng(707)
    canvas = dark_table(W, H, r)
    canvas = monitor_rectangles(canvas, r)
    doc = fake_text_lines(1000, 1400, r, ink_jitter=4)
    doc = paper_lighting(doc, r)
    quad = np.float32([(420, 130), (1220, 100), (1200, 830), (440, 860)])
    Hmat = homography_from_quad(quad, 1000, 1400)
    canvas = add_shadow(canvas, quad, r, strength=80)
    canvas = warp_content(canvas, doc, Hmat)
    return canvas, quad


FIXTURES = {
    "A_white_black_table": fixture_a,
    "B_book_wood": fixture_b,
    "C_white_light_table": fixture_c,
    "D_shadow": fixture_d,
    "E_oblique": fixture_e,
    "F_handwritten": fixture_f,
    "G_rect_background": fixture_g,
}


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "fixtures"
    os.makedirs(out_dir, exist_ok=True)
    for name, fn in FIXTURES.items():
        canvas, quad = fn(out_dir)
        cv2.imwrite(os.path.join(out_dir, f"{name}.jpg"), canvas,
                    [cv2.IMWRITE_JPEG_QUALITY, 92])
        gt = {"TL": quad[0].tolist(), "TR": quad[1].tolist(),
              "BR": quad[2].tolist(), "BL": quad[3].tolist()}
        with open(os.path.join(out_dir, f"{name}.gt.json"), "w") as f:
            json.dump(gt, f)
        print(f"{name}: {os.path.getsize(os.path.join(out_dir, f'{name}.jpg'))//1024} KB, "
              f"quad={gt}")


if __name__ == "__main__":
    main()