"""Ground Truth Labeling Tool for Real Scanner Dataset.

Allows interactive labeling of 4 document corners (TL, TR, BR, BL) on real photos.
Saves <image_name>.gt.json alongside the image.

Usage:
  py -3.10 tools/scanpoc/label_gt.py [directory_or_image_path]

Controls:
  Left Click  : Place next corner (TL -> TR -> BR -> BL) or drag existing corner
  Right Click : Delete nearest corner point
  'r'         : Reset / clear all points
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


def load_existing_gt(gt_path):
    if not os.path.exists(gt_path):
        return []
    try:
        with open(gt_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if all(k in data for k in ["TL", "TR", "BR", "BL"]):
            return [data["TL"], data["TR"], data["BR"], data["BL"]]
        elif all(k in data for k in ["tl", "tr", "br", "bl"]):
            return [data["tl"], data["tr"], data["br"], data["bl"]]
    except Exception as e:
        print(f"Warning: could not read existing GT from {gt_path}: {e}")
    return []


def save_gt(gt_path, points):
    assert len(points) == 4
    data = {
        "TL": [float(points[0][0]), float(points[0][1])],
        "TR": [float(points[1][0]), float(points[1][1])],
        "BR": [float(points[2][0]), float(points[2][1])],
        "BL": [float(points[3][0]), float(points[3][1])],
    }
    with open(gt_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"Saved GT -> {gt_path}")


def label_image(img_path):
    gt_path = os.path.splitext(img_path)[0] + ".gt.json"
    img = cv2.imread(img_path)
    if img is None:
        print(f"Error: unable to read {img_path}")
        return True

    orig_h, orig_w = img.shape[:2]
    # Max display dimensions
    max_disp_w, max_disp_h = 1280, 880
    scale = min(max_disp_w / orig_w, max_disp_h / orig_h, 1.0)
    disp_w, disp_h = int(orig_w * scale), int(orig_h * scale)

    points = []
    existing = load_existing_gt(gt_path)
    if len(existing) == 4:
        points = [[int(round(p[0] * scale)), int(round(p[1] * scale))] for p in existing]

    active_drag_idx = [-1]

    def mouse_callback(event, x, y, flags, param):
        nonlocal points
        if event == cv2.EVENT_LBUTTONDOWN:
            # Check if clicked near an existing point to start dragging
            for idx, pt in enumerate(points):
                if np.hypot(pt[0] - x, pt[1] - y) < 18:
                    active_drag_idx[0] = idx
                    return
            # Otherwise, add point if < 4
            if len(points) < 4:
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
                if dists[min_idx] < 30:
                    points.pop(min_idx)

    win_name = f"GT Labeling: {os.path.basename(img_path)} ({orig_w}x{orig_h})"
    cv2.namedWindow(win_name, cv2.WINDOW_AUTOSIZE)
    cv2.setMouseCallback(win_name, mouse_callback)

    while True:
        disp = cv2.resize(img, (disp_w, disp_h), interpolation=cv2.INTER_AREA)

        # Draw polygon if 4 points
        if len(points) == 4:
            pts_arr = np.array(points, np.int32).reshape((-1, 1, 2))
            cv2.polylines(disp, [pts_arr], True, (0, 255, 0), 2, cv2.LINE_AA)

        # Draw points and labels
        for idx, (px, py) in enumerate(points):
            name = CORNER_NAMES[idx] if idx < 4 else f"P{idx}"
            color = CORNER_COLORS[idx] if idx < 4 else (255, 255, 255)
            cv2.circle(disp, (px, py), 8, color, -1, cv2.LINE_AA)
            cv2.circle(disp, (px, py), 10, (255, 255, 255), 2, cv2.LINE_AA)
            cv2.putText(disp, name, (px + 12, py - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 3, cv2.LINE_AA)
            cv2.putText(disp, name, (px + 12, py - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2, cv2.LINE_AA)

        # Instructions banner
        header = f"[{len(points)}/4 points] "
        if len(points) < 4:
            header += f"Click to place {CORNER_NAMES[len(points)]}"
        else:
            header += "4 points ready. Press 'S' or Enter to save."
        cv2.rectangle(disp, (0, 0), (disp_w, 36), (20, 20, 20), -1)
        cv2.putText(disp, header, (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (255, 255, 255), 1, cv2.LINE_AA)

        cv2.imshow(win_name, disp)
        key = cv2.waitKey(20) & 0xFF

        if key in (27, ord('q'), ord('Q')):
            cv2.destroyAllWindows()
            return False
        elif key in (13, 10, ord('s'), ord('S')):
            if len(points) == 4:
                # Convert from disp scale back to original pixel coordinates
                orig_points = [[p[0] / scale, p[1] / scale] for p in points]
                save_gt(gt_path, orig_points)
                cv2.destroyAllWindows()
                return True
            else:
                print(f"Cannot save: need 4 points, but currently have {len(points)}.")
        elif key in (ord('r'), ord('R')):
            points = []
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
            if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")) and not f.endswith(".overlay.png") and not f.endswith(".mask.png")
        ]
    else:
        print(f"Path not found: {target}")
        return

    if not images:
        print(f"No images found in {target}.")
        print("Please place MEIZU 21 photos (R01.jpg - R12.jpg) into tools/scanpoc/real-input/")
        return

    print(f"Found {len(images)} images to label.")
    for img_path in images:
        cont = label_image(img_path)
        if not cont:
            print("Labeling aborted by user.")
            break
    print("Done.")


if __name__ == "__main__":
    main()
