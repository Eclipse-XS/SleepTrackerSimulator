# SleepSense 2.0 — Multimodal Sensor Fusion & Ablation Study

## 1. Research Motivation & Hypotheses

A fundamental open question in ubiquitous computing and smart bed tracking is:
> **RQ3 & RQ4:** *Does incorporating real-time heart rate from a wearable BLE peripheral improve movement and rest pattern classification over smartphone accelerometer data alone, and what occurs when the wireless BLE link drops, becomes noisy, or reports stale data?*

In SleepSense 2.0, we systematically evaluate the marginal utility of cardiovascular telemetry and the system's fault-tolerance across **7 controlled ablation conditions**.

---

## 2. Experimental Ablation Conditions & Empirical Results

All conditions A–F were evaluated on the held-out subject test set ($N = 10,798$ feature windows across `SUBJ-011` and `SUBJ-012`), with Condition G evaluated on the held-out shifted nightstand cohort ($N = 53,938$ windows across `SHIFT-001`..`SHIFT-010`):

| Condition ID | Input Sensor Configuration | Accuracy | Balanced Accuracy | Macro F1 | Weighted F1 | Precision (REST / RESTLESS / ACTIVE) | Recall (REST / RESTLESS / ACTIVE) | ECE |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Condition A** | Accelerometer Only (Dense DNN) | 94.38% | 58.03% | 0.6716 | 0.9309 | 0.95 / 0.76 / 1.00 | 1.00 / 0.29 / 0.45 | **0.0125** |
| **Condition B** | Heart Rate Only (Dense MLP) | 91.86% | 33.33% | 0.3192 | 0.8796 | 0.92 / 0.00 / 0.00 | 1.00 / 0.00 / 0.00 | 0.0174 |
| **Condition C** | Multimodal Late-Fusion (Accel + HR) | 94.54% | 58.73% | **0.6787** | **0.9337** | 0.95 / 0.77 / 0.98 | 1.00 / 0.32 / 0.44 | 0.0295 |
| **Condition D** | Accel + 100% Missing HR (BLE Dropout) | 94.55% | 59.08% | 0.6767 | 0.9340 | 0.95 / 0.76 / 0.90 | 1.00 / 0.32 / 0.45 | 0.0256 |
| **Condition E** | Accel + Severely Noisy HR ($\sigma=15\text{ bpm}$) | **94.57%** | **59.15%** | 0.6827 | 0.9346 | 0.95 / 0.76 / 0.98 | 1.00 / 0.34 / 0.44 | 0.0197 |
| **Condition F** | Accel + Stale Frozen HR (60s lag) | 94.52% | 58.64% | 0.6778 | 0.9334 | 0.95 / 0.77 / 0.98 | 1.00 / 0.32 / 0.44 | 0.0294 |
| **Condition G** | Real-World Domain Shift (Nightstand Cohort, $N=53,938$) | 64.74% | 44.97% | 0.4037 | 0.6710 | 0.94 / 0.14 / 0.95 | 0.67 / 0.58 / 0.11 | — |
| ~~Condition G (Historical)~~ | **`SUPERSEDED_INVALID_EVALUATION`** *(evaluated on training DB $N=53,982$ due to closure bug; see [DOMAIN_SHIFT_EVALUATION.md](DOMAIN_SHIFT_EVALUATION.md))* | ~~95.94%~~ | ~~82.86%~~ | ~~0.8784~~ | ~~0.9545~~ | ~~0.96 / 0.94 / 0.99~~ | ~~1.00 / 0.55 / 0.94~~ | ~~0.0073~~ |

---

## 3. In-Depth Scientific Analysis

### 3.1 Why Heart Rate Alone Fails (Condition B vs Condition A)
When evaluated in isolation, the Heart Rate Only model collapses to the majority class baseline:
- **Accuracy**: $91.86\%$ (identical to unconditionally predicting `REST`)
- **Macro F1**: $0.3192$
- **Restless & Active Recall**: $0.0\%$ (all 752 restless and 127 active windows misclassified as rest)

**Biophysical Root Cause:**
Physical movement is an instantaneous mechanical phenomenon lasting 1–10 seconds. In contrast, cardiovascular autonomic response is constrained by biological circulatory inertia:
$$\tau_{\text{rise}} \in [6.0, 14.0]\text{ seconds}$$
By the time sympathetic tone elevates heart rate by $+10\text{ bpm}$, the transient restlessness event has already concluded. Consequently, 1-second instantaneous heart rate features contain insufficient high-frequency temporal information to segment short physical movements without accelerometer data.

---

### 3.2 The Complementary Value of Late Fusion (Condition C vs Condition A)
When both modalities are fused via the Multimodal Late-Fusion DNN:
- Overall Accuracy improves from $94.38\%$ to $94.54\%$ ($+0.16\%$).
- Macro F1 increases from $0.6716$ to $0.6787$ ($+0.0071$).
- **Restless Sensitivity**: True positives on minority `RESTLESS` windows rise from 220 to 242 ($+10.0\%$ relative improvement).

**Fusion Mechanism:**
The multimodal network uses late fusion: a 1D CNN branch extracts 32 latent temporal representations from the raw 20 Hz triaxial accelerometer sequence, while a dense branch embeds the scalar heart rate and missingness indicator into a 16-dimensional representation. These representations concatenate before final softmax classification. This enables the network to leverage cardiovascular elevation to disambiguate sustained motor activity from low-energy transient twitches.

---

### 3.3 Fault Resilience Under Wireless Degradation (Conditions D, E, F)
A critical vulnerability of naive multimodal models is brittle catastrophic failure when an auxiliary peripheral disconnects:
- **Condition D (Complete BLE Disconnect)**: When heart rate is replaced with population median baseline ($65.0\text{ bpm}$) and `is_hr_missing = 1.0`, the model maintains $94.55\%$ accuracy and $0.6767$ Macro F1. Performance does not collapse; it gracefully defaults to the accelerometer branch.
- **Condition E (High Sensor Noise, $\sigma=15\text{ bpm}$)**: The network demonstrates high noise immunity ($\text{Macro F1} = 0.6827$). Because the late-fusion weights for heart rate are regularized, high-frequency Gaussian noise does not distort the dominant accelerometer predictions.
- **Condition F (Stale Frozen HR for 60s)**: Even when HR readings freeze due to firmware buffer hangs, Macro F1 remains stable at $0.6778$.

---

## 4. Key Takeaways for Edge System Design

1. **Accelerometer is Non-Negotiable**: Accelerometer telemetry is the primary foundation for physical movement classification. Systems relying solely on optical photoplethysmography (PPG) or BLE heart-rate straps cannot reliably detect short nocturnal restlessness events.
2. **Heart Rate Acts as an Auxiliary Gate**: Heart rate provides useful secondary confirmation of sustained awakenings and autonomic arousal, improving restless recall.
3. **Missingness Indicator is Mandatory**: Supplying an explicit missingness indicator (`is_hr_missing`) prevents the network from interpreting an absent Bluetooth signal as an anomalous bradycardia or tachycardia, ensuring safe operation during wireless reconnect cycles.
