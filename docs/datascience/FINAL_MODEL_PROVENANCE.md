# SleepSense 2.0: Final Model Provenance & Artifact Registry

**Document ID:** SS2-ML-PROV-001  
**Version:** 2.1.0  
**Effective Date:** 2026-11-02  
**Status:** AUDITED & STAGED  

---

## 1. Staged Model Artifact Registry

All trained neural networks intended for edge deployment are staged in `ml/artifacts/candidates/` with associated cryptographic checksums and training provenance before promotion into Android assets.

```
ml/artifacts/
├── candidates/
│   ├── candidate_manifest.json
│   ├── multimodal_classifier_candidate.tflite
│   └── activity_classifier_candidate.tflite
├── backups/
│   └── <timestamp>/
│       ├── backup_manifest.json
│       ├── multimodal_classifier.tflite
│       └── activity_classifier.tflite
└── promotions.jsonl
```

---

## 2. Model Cryptographic Specifications

### 2.1 Multimodal Late-Fusion Classifier (Dual-Input)
- **Staged File:** `ml/artifacts/candidates/multimodal_classifier_candidate.tflite`
- **Model ID:** `multimodal-tflite-v2.1`
- **Version:** `2.1.0`
- **File Size:** `17,064 bytes` (16.7 KB)
- **SHA-256 Checksum:** `e7c6b8c41fa4a2e454b8f7f9c0b3fa1fcdcce8fafd6fed9a2f86b365429aa8f0`
- **Quantization:** Float32 FlatBuffer
- **Input Tensors:**
  1. `accelerometer_input`: Shape `[1, 20, 3]`, DType `float32` (1-second window at 20 Hz, tri-axial m/s²)
  2. `heart_rate_input`: Shape `[1, 2]`, DType `float32` (`[norm_bpm, hr_fresh_mask]`)
- **Output Tensor:**
  - `probabilities`: Shape `[1, 3]`, DType `float32` (Softmax over `[REST, RESTLESS, ACTIVE]`)
- **Latency (CPU microbenchmark):** $\sim 12.5\text{ ms}$

### 2.2 Dense Accelerometer Classifier (Single-Input Fallback)
- **Staged File:** `ml/artifacts/candidates/activity_classifier_candidate.tflite`
- **Model ID:** `activity-dense-tflite-v2.1`
- **Version:** `2.1.0`
- **File Size:** `14,032 bytes` (13.7 KB)
- **SHA-256 Checksum:** `e16278e0d18b710a68bb04fc700091f16791dcb4b01e16491f37ade0ed03b85b`
- **Quantization:** Float32 FlatBuffer
- **Input Tensor:**
  - `accelerometer_window`: Shape `[1, 20, 3]`, DType `float32`
- **Output Tensor:**
  - `probabilities`: Shape `[1, 3]`, DType `float32`
- **Latency (CPU microbenchmark):** $\sim 1.9\text{ ms}$

---

## 3. Signal & Feature Normalization Contracts

### 3.1 Accelerometer Signal Contract
- **Nominal Sampling Rate:** 20 Hz ($\pm 10\%$ acceptable jitter).
- **Units:** $\text{m/s}^2$ including gravity ($\approx 9.81\text{ m/s}^2$).
- **Tensor Format:** Array of 20 3D vectors `[[x_0, y_0, z_0], ..., [x_19, y_19, z_19]]`.
- **Pre-processing:** Raw acceleration vectors are normalized internally within the network layers or passed directly without requiring manual zero-centering on device.

### 3.2 Heart Rate Feature Contract
- **Raw Input:** Heart Rate BPM from BLE GATT Heart Rate Service (UUID `0x180D`).
- **Causality Contract:** $t_{\text{hr}} \le w_{\text{end}}$. Samples with $t_{\text{hr}} > w_{\text{end}}$ are strictly forbidden.
- **Freshness Contract:** $\text{Age}_{\text{ms}} = w_{\text{end}} - t_{\text{hr}}$.
  - If $0 \le \text{Age}_{\text{ms}} \le 10,000\text{ ms}$:
    $$\text{norm\_bpm} = \frac{\text{BPM} - 60.0}{40.0}, \quad \text{mask} = 1.0$$
  - If $\text{Age}_{\text{ms}} > 10,000\text{ ms}$ or HR unavailable:
    $$\text{norm\_bpm} = 0.0, \quad \text{mask} = 0.0$$

---

## 4. Controlled Promotion & Rollback Protocol

### 4.1 CLI Promotion Command
To promote a validated candidate model to the Android application assets:
```powershell
.\ml\.venv\Scripts\python.exe -m pipelines.cli.main promote `
    --candidate multimodal_classifier_candidate.tflite `
    --target multimodal_classifier.tflite `
    --notes "Release 2.1.0 multimodal production candidate"
```

### 4.2 Automated Safety Safeguards
1. **Candidate Verification Gate:** The `ModelPromoter` verifies that the candidate exists, is loadable by the TFLite runtime, executes a test inference pass, and that its SHA-256 matches `candidate_manifest.json`.
2. **Pre-Promotion Backup:** All existing `.tflite` files in `app/src/main/assets/` are copied to a timestamped directory in `ml/artifacts/backups/<YYYYMMDD_HHMMSS>/` with a `backup_manifest.json` recording original hashes.
3. **Atomic Replacement:** The candidate file is copied into `app/src/main/assets/`.
4. **Audit Logging:** An indelible JSON-line receipt is appended to `ml/artifacts/promotions.jsonl`.
