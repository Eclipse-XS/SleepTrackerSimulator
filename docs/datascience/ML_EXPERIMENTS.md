# SleepSense 2.0 — Machine Learning Experiments, Benchmark Suite & Evaluation

## 1. Experimental Methodology & Rigor

All modeling experiments are evaluated on the standard benchmark dataset (`data/synthetic/sleepsense_synthetic.sqlite`, $N=64,780$ windows across 36 sessions and 12 subjects).

### 1.1 Leakage-Safe Subject-Independent Partitioning
To prevent data leakage across correlated temporal time series, the dataset is split strictly by virtual subject identity:
- **Training Set (8 Subjects)**: `SUBJ-001` through `SUBJ-008` (43,185 windows, 66.7%)
- **Validation Set (2 Subjects)**: `SUBJ-009`, `SUBJ-010` (10,797 windows, 16.7%)
- **Held-Out Test Set (2 Subjects)**: `SUBJ-011`, `SUBJ-012` (10,798 windows, 16.7%)

> [!IMPORTANT]
> Feature scaling (`StandardScaler`) was fit **exclusively** on the training split and transformed onto validation and test splits. Test subjects have unique baseline heart rates, Euler angles, and placement noise floors that never appeared during training.

### 1.2 Evaluation Metrics
Given the 89.8% nocturnal class imbalance, overall accuracy is complemented by:
1. **Macro-Averaged F1-Score**: $\frac{1}{3} \sum_{c} F1_c$ (weights all movement states equally).
2. **Balanced Accuracy**: Macro-averaged recall across classes.
3. **Per-Class Precision & Recall**: Tracking sensitivity to transient `RESTLESS` and `ACTIVE` episodes.
4. **Expected Calibration Error (ECE)**: Quantifying whether predicted softmax confidence matches empirical posterior frequency across 10 reliability bins.
5. **Inference Latency**: Single-window execution time measured in microseconds ($\mu\text{s}$).
6. **Model Size**: Parameter footprint in kilobytes (KB).

---

## 2. Comprehensive Model Benchmark Comparison Matrix

Evaluation performed on held-out test set ($N = 10,798$ feature windows):

| Model Architecture | Accuracy | Balanced Accuracy | Macro F1 | Weighted F1 | Macro Precision | Macro Recall | ECE | Inference Latency ($\mu\text{s}$) | Footprint (KB) |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Majority Class Baseline** | 91.86% | 33.33% | 0.3192 | 0.8796 | 0.3062 | 0.3333 | 0.0814 | **0.1** | **0.0** |
| **Physical Rule Heuristic** | 93.80% | 55.10% | 0.6345 | 0.9213 | 0.8689 | 0.5510 | 0.0402 | 2.2 | **0.0** |
| **Logistic Regression** | 93.78% | 57.11% | 0.6555 | 0.9249 | 0.8567 | 0.5711 | 0.1308 | 346.8 | 2.4 |
| **Random Forest (100 Trees)** | 94.22% | 60.06% | **0.6857** | 0.9331 | 0.8693 | **0.6006** | 0.1533 | 26,299.8 | 165.0 |
| **Dense Accel DNN** | 94.38% | 58.03% | 0.6716 | 0.9309 | **0.9016** | 0.5803 | **0.0125** | 4,506.9 | 13.7 |
| **1D Temporal CNN** | 94.48% | 58.72% | 0.6786 | 0.9329 | 0.8991 | 0.5872 | 0.0138 | 7,756.3 | 14.4 |
| **Multimodal Late-Fusion DNN** | **94.54%** | **58.73%** | 0.6787 | **0.9337** | 0.8996 | 0.5873 | 0.0295 | 6,695.2 | 16.7 |

---

## 3. Confusion Matrices & Per-Class Performance

Rows indicate Ground Truth, Columns indicate Model Prediction ($N=10,798$):

### 3.1 Majority Class Baseline
$$\begin{bmatrix}
9919 & 0 & 0 \\
752 & 0 & 0 \\
127 & 0 & 0
\end{bmatrix}$$
- Demonstrates the failure mode of uncalibrated accuracy: $91.86\%$ accuracy despite predicting 0% of restless and active movements ($\text{Macro F1} = 0.3192$).

### 3.2 Physical Rule Heuristic (RMS & Variance Thresholds)
$$\begin{bmatrix}
9912 & 6 & 1 \\
592 & 160 & 0 \\
2 & 69 & 56
\end{bmatrix}$$
- **Precision**: REST: 94.3%, RESTLESS: 68.1%, ACTIVE: 98.2%
- **Recall**: REST: 99.9%, RESTLESS: 21.3%, ACTIVE: 44.1%
- Excellent precision on active movement, but high false negatives on subtle restlessness.

### 3.3 Logistic Regression (Tabular Features)
$$\begin{bmatrix}
9866 & 53 & 0 \\
549 & 203 & 0 \\
0 & 70 & 57
\end{bmatrix}$$
- **Precision**: REST: 94.7%, RESTLESS: 62.3%, ACTIVE: 100.0%
- **Recall**: REST: 99.5%, RESTLESS: 27.0%, ACTIVE: 44.9%

### 3.4 Random Forest (100 Trees, Depth 12)
$$\begin{bmatrix}
9846 & 73 & 0 \\
481 & 271 & 0 \\
0 & 70 & 57
\end{bmatrix}$$
- **Precision**: REST: 95.3%, RESTLESS: 65.5%, ACTIVE: 100.0%
- **Recall**: REST: 99.3%, RESTLESS: 36.0%, ACTIVE: 44.9%
- Strongest tabular learner on subtle restlessness (271 true positives), but high latency ($26.3\text{ ms}$).

### 3.5 Dense Accelerometer DNN (MLP with Dynamic Centering)
$$\begin{bmatrix}
9914 & 5 & 0 \\
532 & 220 & 0 \\
4 & 66 & 57
\end{bmatrix}$$
- **Precision**: REST: 94.9%, RESTLESS: 75.6%, ACTIVE: 100.0%
- **Recall**: REST: 99.9%, RESTLESS: 29.3%, ACTIVE: 44.9%
- Superior calibration ($\text{ECE} = 0.0125$) and low false-positive rate on restlessness (only 5 false positives vs RF's 73).

### 3.6 1D Temporal CNN (Raw 20 Hz Waveform Input)
$$\begin{bmatrix}
9909 & 10 & 0 \\
516 & 236 & 0 \\
0 & 70 & 57
\end{bmatrix}$$
- **Precision**: REST: 95.1%, RESTLESS: 74.7%, ACTIVE: 100.0%
- **Recall**: REST: 99.9%, RESTLESS: 31.4%, ACTIVE: 44.9%
- Learned temporal filters capture transient wave packets directly without manual feature extraction.

### 3.7 Multimodal Late-Fusion DNN (Raw Waveform + Heart Rate)
$$\begin{bmatrix}
9910 & 8 & 1 \\
510 & 242 & 0 \\
5 & 66 & 56
\end{bmatrix}$$
- **Precision**: REST: 95.1%, RESTLESS: 76.6%, ACTIVE: 98.2%
- **Recall**: REST: 99.9%, RESTLESS: 32.2%, ACTIVE: 44.1%
- Best overall accuracy ($94.54\%$) and best balance between precision ($76.6\%$) and recall ($32.2\%$) on the minority `RESTLESS` class.

---

## 4. Engineering Trade-Off Analysis

```mermaid
quadrantChart
    title Model Trade-Off: Macro F1 vs Inference Latency
    x-axis Low Latency (Fast) --> High Latency (Slow)
    y-axis Low Macro F1 --> High Macro F1
    quadrant-1 Unsuitable: High Latency & High Performance
    quadrant-2 Optimal: Low Latency & High Performance
    quadrant-3 Sub-optimal: Low Latency & Low Performance
    quadrant-4 Inefficient: High Latency & Low Performance
    "Majority Baseline": [0.01, 0.32]
    "Rule Heuristic": [0.05, 0.63]
    "Logistic Regression": [0.15, 0.65]
    "Dense Accel DNN": [0.28, 0.67]
    "1D CNN": [0.38, 0.68]
    "Multimodal Late-Fusion": [0.35, 0.68]
    "Random Forest": [0.95, 0.69]
```

### Key Insights:
1. **The Edge Latency Dilemma**: Random Forest achieves the highest tabular Macro F1 ($0.6857$), but its inference latency is $26,299.8\mu\text{s}$ ($26.3\text{ ms}$) with a $165\text{ KB}$ memory footprint. In an Android foreground recording service running once per second, evaluating 100 decision trees creates unnecessary CPU and battery drain.
2. **Neural Calibration Advantage**: Both the Dense DNN ($\text{ECE} = 0.0125$) and 1D CNN ($\text{ECE} = 0.0138$) exhibit order-of-magnitude superior probability calibration compared to Random Forest ($\text{ECE} = 0.1533$) and Logistic Regression ($\text{ECE} = 0.1308$). For downstream smart actuator triggers, well-calibrated posterior probabilities prevent false alarms.
3. **On-Device Selection Recommendation**: The **Dense Accelerometer DNN** ($13.7\text{ KB}$, $4.5\text{ ms}$) is recommended for resource-constrained or battery-saver deployments; the **Multimodal Late-Fusion DNN** ($16.7\text{ KB}$, $6.7\text{ ms}$) is recommended when a BLE heart-rate monitor is connected.
