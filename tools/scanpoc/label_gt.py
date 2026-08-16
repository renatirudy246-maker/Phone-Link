"""Ground Truth Labeling Tool for Real Scanner Dataset.

Allows interactive labeling of 4 document corners (TL, TR, BR, BL) on real photos,
or marking as "No Document" (e.g. for R12).
Saves <image_name>.gt.json alongside the image.

Features:
  - Generous 60px canvas margin around the entire image so top/edge corners can be dragged freely.
  - Dedicated top status banner that never obscures the image.
  - Large click/drag hit target (35px radius).

Usage:
  py -3.10 tools/scanpoc/label_gt.py [directory_or_image_path]

Controls:
  Left Click  : Place next corner (TL -> TR -> BR -> BL) or drag existing corner
  Right Click : Delete nearest corner point
  'r'         : Reset / clear all points to re-click
  'd' / '0'   : Mark as NO DOCUMENT ({"documentPresent": false}) and save
  's' / Enter : Save GT JSON and move to next image
  'n' / Space : Skip to next image
  'q' / Esc   : Quit
"""
import json
import os
import sys
import cv2
import numpy as np


CORNER_NAMES = ["TL", "TR", "BR", "BL"]
CORNER_COLORS = [
    (0, 0, 255),    # TL: Red
    (0, 255, 255),  # TR: Yellow
    (0, 255, 0),    # BR: Green
    (255, 128, 0),  # BL: Orange/Blue
]

PAD = 70
BANNER_H = 45


def load_existing_gt(gt_path):
    if not os.path.exists(gt_path):
        return None, []
    try:
        with open(gt_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if data.get("documentPresent") is False or data.get("document_present") is False:
            return False, []
        if all(k in data for k in ["TL", "TR", "BR", "BL"]):
            return True, [data["TL"], data["TR"], data["BR"], data["BL"]]
        elif all(k in data for k in ["tl", "tr", "br", "bl"]):
            return True, [data["tl"], data["tr"], data["br"], data["bl"]]
    except Exception as e:
        print(f"Warning: could not read existing GT from {gt_path}: {e}")
    return None, []


def save_gt_document(gt_path, points):
    assert len(points) == 4
    data = {
        "documentPresent": True,
        "TL": [round(float(points[0][0]), 1), round(float(points[0][1]), 1)],
        "TR": [round(float(points[1][0]), 1), round(float(points[1][1]), 1)],
        "BR": [round(float(points[2][0]), 1), round(float(points[2][1]), 1)],
        "BL": [round(float(points[3][0]), 1), round(float(points[3][1]), 1)],
    }
    with open(gt_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"Saved GT (Document) -> {gt_path}")


def save_gt_no_document(gt_path):
    data = {
        "documentPresent": False,
    }
    with open(gt_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"Saved GT (NO DOCUMENT) -> {gt_path}")


def label_image(img_path):
    gt_path = os.path.splitext(img_path)[0] + ".gt.json"
    img = cv2.imread(img_path)
    if img is None:
        print(f"Error: unable to read {img_path}")
        return True

    orig_h, orig_w = img.shape[:2]
    # Max display dimensions for image itself
    max_disp_w, max_disp_h = 1200, 800
    scale = min(max_disp_w / orig_w, max_disp_h / orig_h, 1.0)
    disp_w, disp_h = int(orig_w * scale), int(orig_h * scale)

    canvas_w = disp_w + 2 * PAD
    canvas_h = disp_h + 2 * PAD + BANNER_H

    # Points stored in canvas coordinates: [cx, cy]
    # To image coords: img_x = (cx - PAD) / scale, img_y = (cy - PAD - BANNER_H) / scale
    points = []
    doc_present, existing = load_existing_gt(gt_path)
    is_no_doc = (doc_present is False)

    if doc_present is True and len(existing) == 4:
        points = [
            [int(round(p[0] * scale + PAD)), int(round(p[1] * scale + PAD + BANNER_H))]
            for p in existing
        ]

    active_drag_idx = [-1]

    def mouse_callback(event, x, y, flags, param):
        nonlocal points, is_no_doc
        if event == cv2.EVENT_LBUTTONDOWN:
            is_no_doc = False
            # Check if clicked near an existing point to start dragging
            for idx, pt in enumerate(points):
                if np.hypot(pt[0] - x, pt[1] - y) < 32:
                    active_drag_idx[0] = idx
                    return
            # Otherwise, add point if < 4
            if len(points) < 4:
                # Clamp within canvas
                points.append([x, y])
        elif event == cv2.EVENT_MOUSEMOVE:
            if active_drag_idx[0] >= 0:
                points[active_drag_idx[0]] = [x, y]
        elif event == cv2.EVENT_LBUTTONUP:
            active_drag_idx[0] = -1
        elif event == cv2.EVENT_RBUTTONDOWN:
            # Delete nearest point
            if points:
                dists = [np.hypot(pt[0] - x, pt[1] - y) for pt in points]
                min_idx = int(np.argmin(dists))
                if dists[min_idx] < 45:
                    points.pop(min_idx)

    win_name = f"GT Labeling: {os.path.basename(img_path)} ({orig_w}x{orig_h})"
    cv2.namedWindow(win_name, cv2.WINDOW_AUTOSIZE)
    cv2.setMouseCallback(win_name, mouse_callback)

    resized_img = cv2.resize(img, (disp_w, disp_h), interpolation=cv2.INTER_AREA)

    while True:
        # Create full canvas with dark background
        canvas = np.full((canvas_h, canvas_w, 3), 35, dtype=np.uint8)

        # Place image inside canvas
        img_y0 = PAD + BANNER_H
        img_x0 = PAD
        canvas[img_y0:img_y0 + disp_h, img_x0:img_x0 + disp_w] = resized_img

        # Draw a thin white border around image boundary to make edges clear
        cv2.rectangle(canvas, (img_x0 - 1, img_y0 - 1), (img_x0 + disp_w, img_y0 + disp_h), (80, 80, 80), 1)

        if is_no_doc:
            # Show big banner NO DOCUMENT
            cv2.rectangle(canvas, (canvas_w // 4, canvas_h // 3), (canvas_w * 3 // 4, canvas_h * 2 // 3), (0, 0, 180), -1)
            cv2.putText(canvas, "NO DOCUMENT", (canvas_w // 4 + 40, canvas_h // 2), cv2.FONT_HERSHEY_SIMPLEX, 1.4, (255, 255, 255), 3, cv2.LINE_AA)
            cv2.putText(canvas, "Press Enter / S to save NO_DOC", (canvas_w // 4 + 30, canvas_h // 2 + 50), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (220, 220, 220), 2, cv2.LINE_AA)
        else:
            # Draw polygon if 4 points
            if len(points) == 4:
                pts_arr = np.array(points, np.int32).reshape((-1, 1, 2))
                cv2.polylines(canvas, [pts_arr], True, (0, 255, 255), 2, cv2.LINE_AA)

            # Draw points and labels
            for idx, (px, py) in enumerate(points):
                name = CORNER_NAMES[idx] if idx < 4 else f"P{idx}"
                color = CORNER_COLORS[idx] if idx < 4 else (255, 255, 255)
                # Outer glow
                cv2.circle(canvas, (px, py), 12, (0, 0, 0), -1, cv2.LINE_AA)
                cv2.circle(canvas, (px, py), 10, color, -1, cv2.LINE_AA)
                cv2.circle(canvas, (px, py), 12, (255, 255, 255), 2, cv2.LINE_AA)
                cv2.putText(canvas, name, (px + 14, py - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (0, 0, 0), 3, cv2.LINE_AA)
                cv2.putText(canvas, name, (px + 14, py - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2, cv2.LINE_AA)

        # Dedicated top instruction banner (height = BANNER_H)
        cv2.rectangle(canvas, (0, 0), (canvas_w, BANNER_H), (18, 18, 18), -1)
        if is_no_doc:
            header = "[NO DOCUMENT] Press 'S' or Enter to save | Press 'R' to reset"
        elif len(points) < 4:
            header = f"[{len(points)}/4 points] Click {CORNER_NAMES[len(points)]} | 'R' to Reset | 'D' for NO_DOC"
        else:
            header = "[4 points ready] Drag handles to fine-tune. Press 'S' or Enter to save | 'R' to reset"
        cv2.putText(canvas, header, (15, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)

        cv2.imshow(win_name, canvas)
        key = cv2.waitKey(20) & 0xFF

        if key in (27, ord('q'), ord('Q')):
            cv2.destroyAllWindows()
            return False
        elif key in (13, 10, ord('s'), ord('S')):
            if is_no_doc:
                save_gt_no_document(gt_path)
                cv2.destroyAllWindows()
                return True
            elif len(points) == 4:
                # Convert canvas coordinates back to original image coordinates
                orig_points = []
                for px, py in points:
                    ix = (px - PAD) / scale
                    iy = (py - PAD - BANNER_H) / scale
                    # Clamp strictly within [0, orig_w], [0, orig_h]
                    ix = max(0.0, min(float(orig_w), float(ix)))
                    iy = max(0.0, min(float(orig_h), float(iy)))
                    orig_points.append([ix, iy])
                save_gt_document(gt_path, orig_points)
                cv2.destroyAllWindows()
                return True
            else:
                print(f"Cannot save: need 4 points or 'd' for no document, currently have {len(points)}.")
        elif key in (ord('d'), ord('D'), ord('0'), ord('x'), ord('X')):
            is_no_doc = True
            points = []
        elif key in (ord('r'), ord('R')):
            points = []
            is_no_doc = False
        elif key in (ord('n'), ord('N'), 32):  # Space or 'n' = skip
            cv2.destroyAllWindows()
            return True


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "tools/scanpoc/real-input"
    if os.path.isfile(target):
        images = [target]
    elif os.path.isdir(target):
        images = [
            os.path.join(target, f)
            for f in sorted(os.listdir(target))
            if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")) and not f.endswith(".overlay.png") and not f.endswith(".mask.png") and not f.endswith("_comparison.jpg") and not "_warp_" in f
        ]
    else:
        print(f"Path not found: {target}")
        return

    if not images:
        print(f"No images found in {target}.")
        return

    print(f"=== GT Labeling Tool ===")
    print(f"Found {len(images)} images to label.")
    print("Tip: Press 'R' to clear previous points and click 4 new corners anytime.")
    print("     Canvas has 70px outer margin, you can freely drag any corner handle!")
    for img_path in images:
        cont = label_image(img_path)
        if not cont:
            print("Labeling aborted by user.")
            break
    print("Labeling session completed.")


if __name__ == "__main__":
    main()
