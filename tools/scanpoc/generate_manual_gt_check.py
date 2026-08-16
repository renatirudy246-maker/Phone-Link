"""Generate visual overlays for pure manual Ground Truth verification.
Outputs to: tools/scanpoc/out/manual-gt-check/
"""
import json
import os
import cv2
import numpy as np


src_dir = "tools/scanpoc/real-input"
out_dir = "tools/scanpoc/out/manual-gt-check"
os.makedirs(out_dir, exist_ok=True)


def draw_manual_gt_overlay(img, gt_data, name):
    h, w = img.shape[:2]
    vis = img.copy()
    if gt_data.get("documentPresent") is False:
        cv2.rectangle(vis, (w // 4, h // 3), (w * 3 // 4, h * 2 // 3), (0, 0, 180), -1)
        cv2.putText(vis, "NO DOCUMENT", (w // 4 + 40, h // 2), cv2.FONT_HERSHEY_SIMPLEX, 1.8, (255, 255, 255), 4, cv2.LINE_AA)
    else:
        pts = np.float32([gt_data["TL"], gt_data["TR"], gt_data["BR"], gt_data["BL"]])
        cv2.polylines(vis, [np.int32(pts)], True, (0, 255, 255), 6, cv2.LINE_AA)
        names = ["TL", "TR", "BR", "BL"]
        colors = [(0, 0, 255), (0, 255, 255), (0, 255, 0), (255, 128, 0)]
        for p, n, c in zip(pts, names, colors):
            ip = (int(round(p[0])), int(round(p[1])))
            cv2.circle(vis, ip, 14, (0, 0, 0), -1, cv2.LINE_AA)
            cv2.circle(vis, ip, 12, c, -1, cv2.LINE_AA)
            cv2.circle(vis, ip, 14, (255, 255, 255), 2, cv2.LINE_AA)
            cv2.putText(vis, f"{n} ({ip[0]},{ip[1]})", (ip[0] + 18, ip[1] - 10), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 4, cv2.LINE_AA)
            cv2.putText(vis, f"{n} ({ip[0]},{ip[1]})", (ip[0] + 18, ip[1] - 10), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2, cv2.LINE_AA)

    # Header banner
    cv2.rectangle(vis, (0, 0), (w, 55), (20, 20, 20), -1)
    doc_type = "NO_DOC" if gt_data.get("documentPresent") is False else "DOCUMENT"
    cv2.putText(vis, f"Manual GT: {name} [{doc_type}] ({w}x{h})", (15, 38), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 2, cv2.LINE_AA)
    return vis


def main():
    for i in range(1, 13):
        stem = f"R{i:02d}"
        img_path = os.path.join(src_dir, f"{stem}.jpg")
        gt_path = os.path.join(src_dir, f"{stem}.gt.json")
        img = cv2.imread(img_path)
        if img is None:
            continue
        if not os.path.exists(gt_path):
            print(f"[{stem}] Warning: GT file not found: {gt_path}")
            continue
        with open(gt_path, "r", encoding="utf-8") as f:
            gt_data = json.load(f)

        overlay = draw_manual_gt_overlay(img, gt_data, stem)
        out_file = os.path.join(out_dir, f"{stem}.gt-overlay.png")
        cv2.imwrite(out_file, overlay)
        print(f"[{stem}] Generated manual GT check overlay -> {out_file}")

    print(f"\nAll manual GT check overlays saved to: {out_dir}")


if __name__ == "__main__":
    main()
