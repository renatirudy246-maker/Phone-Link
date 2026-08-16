# Scanner Model and Dataset License Audit

This document records the intellectual property, copyright, licensing terms, and distribution rights for all candidate document detection/segmentation models and training datasets considered for Phone-Link.

**Policy Rule**: Do not infer dataset rights from the software repository license alone. Every dataset and model weight must be verified for commercial redistribution and APK bundling compliance.

---

## 1. Candidate Models & Pretrained Weights

### 1.1 DocQuadNet-256 (MakeACopy)
- **Source URL / Repository**: [https://github.com/egdels/makeacopy](https://github.com/egdels/makeacopy)
- **Author / Owner**: Christian Kierdorf (MakeACopy project)
- **Repository Code License**: Apache License 2.0
- **Exported Model Weight License**: **Apache License 2.0**
  - Explicit declaration in upstream `NOTICE`: *"The exported ONNX inference model is an independently created work and is licensed under the Apache License 2.0."*
- **Model Path in Upstream**: `app/src/main/assets/docquad/docquadnet256_trained_opset17.ort`
- **Model Architecture**: MobileNetV3 + FPN + 4-Corner Heatmaps `[1, 4, 64, 64]` + Mask Logits `[1, 1, 64, 64]`
- **Input Size**: `[1, 3, 256, 256]` (RGB float `[0.0, 1.0]`, Letterbox)
- **SHA-256 Checksum**: `aaef348eb81709d26f7e8974401795b141d70ba88bc69792c779fbae102eadaa`
- **Redistribution Rights**: Permitted under Apache 2.0 (requires preserving copyright notice and NOTICE file).
- **Commercial Use**: Permitted without royalty.
- **Derived Weights Restrictions**: None beyond standard Apache 2.0 terms.
- **Inference Runtime**: ONNX Runtime (MIT License).
- **Decision**: **ACCEPT** (Eligible for production bundling and fine-tuning).

---

### 1.2 FairScan Segmentation Model (Oracle Baseline)
- **Source URL / Repository**: [https://github.com/dkhamsing/fairscan](https://github.com/dkhamsing/fairscan)
- **Author / Owner**: FairScan project contributors
- **Repository Code License**: GNU General Public License v3.0 (GPL-3.0)
- **Model Weights License**: GPL-3.0 (TFLite weights derived under GPL project)
- **Redistribution Rights**: Requires entire linking application to be open-sourced under GPLv3.
- **Commercial / Permissive Bundling**: Incompatible with standard Apache-2.0 / MIT distribution.
- **Decision**: **REJECT for production** (Retained strictly as local research-only evaluation oracle).

---

## 2. Training & Pretraining Datasets Audit

### 2.1 UVDoc Dataset
- **Source URL / Repository**: [https://github.com/tanguymagne/UVDoc-Dataset](https://github.com/tanguymagne/UVDoc-Dataset)
- **Owner / Reference**: Floor Verhoeven et al., *"Neural Grid-based Document Unwarping"* (SIGGRAPH Asia 2023)
- **Repository Code License**: MIT License
- **Dataset License**: MIT License
- **Redistribution Rights**: Permitted under MIT terms with attribution.
- **Model Weight Restrictions**: None.
- **Commercial Use**: Permitted.
- **Decision**: **ACCEPT** (Valid source for pretraining document geometry).

---

### 2.2 SmartDoc Dataset
- **Reference**: Jean-Christophe Burie et al., *"ICDAR 2015 Competition on Smartphone Document Capture and OCR (SmartDoc)"*
- **Source URL**: [https://l3i.univ-larochelle.fr/icdar2015smartdoc/](https://l3i.univ-larochelle.fr/icdar2015smartdoc/)
- **Dataset License**: Creative Commons Attribution 4.0 International (CC BY 4.0)
- **Redistribution Rights**: Permitted with appropriate credit.
- **Model Weight Restrictions**: CC BY 4.0 allows commercial use and creation of derivative works (including trained weights) provided attribution is included in application notices.
- **Commercial Use**: Permitted with attribution.
- **Decision**: **ACCEPT** (Valid source for smartphone document capture fine-tuning).

---

### 2.3 CORD (Consolidated Receipt Dataset)
- **Source URL / Repository**: [https://github.com/clovaai/cord](https://github.com/clovaai/cord)
- **Owner / Reference**: Jaewook Kim et al. (Naver Clova AI), ICDAR 2019
- **Dataset License**: Creative Commons Attribution 4.0 International (CC BY 4.0)
- **Redistribution Rights**: Permitted with attribution.
- **Model Weight Restrictions**: CC BY 4.0 does not restrict model weights trained on the data.
- **Commercial Use**: Permitted with attribution.
- **Decision**: **ACCEPT** (Valid for receipt and small-slip document scenarios).

---

### 2.4 DTD (Describable Textures Dataset)
- **Source URL**: [https://www.robots.ox.ac.uk/~vgg/data/dtd/](https://www.robots.ox.ac.uk/~vgg/data/dtd/)
- **Owner / Reference**: M. Cimpoi et al., *"Describing Textures in the Wild"* (CVPR 2014)
- **Dataset License**: No explicit permissive redistribution license in original tarball.
- **Usage Policy**: Internal synthetic background replacement during training only. Never ship or redistribute raw DTD image assets.
- **Decision**: **NEEDS REVIEW / CAUTION** (Do not redistribute images; for training data diversification, prefer CC0/CC-BY texture repositories).

---

### 2.5 Doc3D Dataset
- **Source URL / Repository**: [https://github.com/cvlab-stonybrook/Doc3D](https://github.com/cvlab-stonybrook/Doc3D)
- **Owner / Reference**: Sagnik Das et al., Stony Brook University (ICCV 2019)
- **Dataset License**: Academic / Non-Commercial Research License.
- **Redistribution Rights**: Restricted.
- **Commercial Use**: Prohibited for commercial products without separate licensing.
- **Decision**: **REJECT for Phone-Link production training** (Non-commercial clause prevents clean Apache-2.0 / MIT distribution).

---

### 2.6 MS-COCO / OpenImages (for Negative Background Samples)
- **Source URL**: [https://cocodataset.org/](https://cocodataset.org/) / [https://storage.googleapis.com/openimages/web/index.html](https://storage.googleapis.com/openimages/web/index.html)
- **Dataset License**: Creative Commons Attribution 4.0 (CC BY 4.0) / Apache 2.0 annotations; image flickr licenses filtered to CC-BY / CC0 / Public Domain.
- **Redistribution Rights**: Permitted with attribution.
- **Usage in Phone-Link**: Negative background samples (empty desks, keyboards, monitors, rooms) to train document-rejection capability.
- **Decision**: **ACCEPT** (Permitted when filtered to CC-BY / CC0 images).

---

## 3. Summary Matrix

| Asset / Dataset | License | Production Bundling | Training Use | Decision |
| :--- | :--- | :---: | :---: | :---: |
| **DocQuadNet-256 (ONNX/ORT)** | Apache-2.0 | ✅ Permitted | ✅ Permitted | **ACCEPT** |
| **FairScan Model** | GPL-3.0 | ❌ Prohibited | ❌ Prohibited | **REJECT (Research Oracle only)** |
| **UVDoc Dataset** | MIT | ✅ Permitted | ✅ Permitted | **ACCEPT** |
| **SmartDoc Dataset** | CC BY 4.0 | ✅ (with attribution) | ✅ Permitted | **ACCEPT** |
| **CORD Dataset** | CC BY 4.0 | ✅ (with attribution) | ✅ Permitted | **ACCEPT** |
| **Doc3D Dataset** | Non-Commercial | ❌ Restricted | ❌ Restricted | **REJECT** |
| **COCO / OpenImages (CC-BY)** | CC BY 4.0 | ✅ (with attribution) | ✅ Permitted | **ACCEPT** |
