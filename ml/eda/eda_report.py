"""Exploratory Data Analysis and Statistical Characterization Engine.

Analyzes synthetic time-series distributions, state transitions, physiological
heart rate dynamics, sensor placement attenuation, and multimodal correlations.
Generates publication-quality charts and markdown reports.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import matplotlib
matplotlib.use("Agg")  # Non-interactive backend
import matplotlib.pyplot as plt
import numpy as np
import polars as pl

ROOT = Path(__file__).resolve().parents[2]
FIGURES_DIR = ROOT / "docs" / "datascience" / "figures"
DB_PATH = ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"
REPORT_PATH = ROOT / "docs" / "datascience" / "EXPLORATORY_DATA_ANALYSIS.md"


def run_eda(db_path: Path | str = DB_PATH, output_dir: Path | str = FIGURES_DIR) -> dict[str, any]:
    """Generates comprehensive EDA statistics and figures."""
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))

    stats: dict[str, any] = {}

    # 1. Class Distribution
    df_classes = pl.read_database(
        """
        SELECT ground_truth_label, COUNT(*) AS window_count,
               ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM feature_windows), 2) AS pct
        FROM feature_windows
        GROUP BY ground_truth_label
        ORDER BY window_count DESC;
        """,
        conn,
    )
    stats["class_distribution"] = df_classes.to_dicts()

    plt.figure(figsize=(7, 4), dpi=150)
    colors = ["#2B7A78", "#3AAFA9", "#DEF2F1"]
    labels = df_classes["ground_truth_label"].to_list()
    counts = df_classes["window_count"].to_list()
    bars = plt.bar(labels, counts, color=["#17252A", "#2B7A78", "#3AAFA9"], edgecolor="#111", width=0.55)
    plt.title("Synthetic Activity Class Distribution (N=64,780 Windows)", fontsize=11, fontweight="bold")
    plt.ylabel("Window Count (1.0s / 20 samples)", fontsize=10)
    plt.grid(axis="y", linestyle="--", alpha=0.4)
    for bar in bars:
        h = bar.get_height()
        plt.text(bar.get_x() + bar.get_width() / 2.0, h + 500, f"{h:,} ({h/sum(counts)*100:.1f}%)",
                 ha="center", va="bottom", fontsize=9, fontweight="bold")
    plt.tight_layout()
    fig1_path = out_dir / "01_class_distribution.png"
    plt.savefig(fig1_path)
    plt.close()

    # 2. Waveform Examples across States
    # Query sample raw accelerometer windows for each state
    fig, axes = plt.subplots(3, 1, figsize=(10, 6), sharex=True, dpi=150)
    for idx, (st, title) in enumerate([
        ("REST", "REST: Minimal Movement with Stationary Gravity Vector (~9.81 m/s²)"),
        ("RESTLESS", "RESTLESS: Periodic Limb Movement & Positional Adjustment (~2.5 Hz)"),
        ("ACTIVE", "ACTIVE: High-Amplitude Vigorous Movement Burst (~4.5 Hz + Spikes)"),
    ]):
        # Get a window id
        w_id = conn.execute(
            "SELECT window_id, start_utc_millis, end_utc_millis FROM feature_windows WHERE ground_truth_label = ? LIMIT 1 OFFSET 20;",
            (st,),
        ).fetchone()
        if w_id:
            samples = conn.execute(
                "SELECT x, y, z FROM accelerometer_samples WHERE utc_millis >= ? AND utc_millis <= ? LIMIT 20;",
                (w_id[1], w_id[2]),
            ).fetchall()
            arr = np.array(samples, dtype=np.float32)
            t_pts = np.linspace(0.0, 1.0, len(arr))
            axes[idx].plot(t_pts, arr[:, 0], label="X (m/s²)", color="#D9534F", lw=1.5)
            axes[idx].plot(t_pts, arr[:, 1], label="Y (m/s²)", color="#5CB85C", lw=1.5)
            axes[idx].plot(t_pts, arr[:, 2], label="Z (m/s²)", color="#428BCA", lw=1.5)
            axes[idx].set_title(title, fontsize=10, fontweight="bold")
            axes[idx].set_ylabel("Accel (m/s²)", fontsize=9)
            axes[idx].grid(True, linestyle=":", alpha=0.5)
            if idx == 0:
                axes[idx].legend(loc="upper right", ncol=3, fontsize=8)
    axes[2].set_xlabel("Time inside 1-second Window (seconds)", fontsize=9)
    plt.tight_layout()
    fig2_path = out_dir / "02_waveform_examples.png"
    plt.savefig(fig2_path)
    plt.close()

    # 3. Heart Rate Trajectory & Inertia
    # Fetch 300 seconds of continuous session data
    hr_data = conn.execute(
        """
        SELECT utc_millis, bpm FROM heart_rate_samples
        WHERE session_id = 'SESS-SMA-0001'
        ORDER BY utc_millis ASC LIMIT 300;
        """
    ).fetchall()
    gt_data = conn.execute(
        """
        SELECT start_utc_millis, end_utc_millis, state_label FROM ground_truth_intervals
        WHERE session_id = 'SESS-SMA-0001'
        ORDER BY start_utc_millis ASC;
        """
    ).fetchall()

    if hr_data and gt_data:
        hr_arr = np.array(hr_data)
        t_sec = (hr_arr[:, 0] - hr_arr[0, 0]) / 1000.0
        bpm = hr_arr[:, 1]

        plt.figure(figsize=(10, 4.5), dpi=150)
        # Shade latent state intervals
        state_colors = {"REST": "#E8F8F5", "RESTLESS": "#FEF9E7", "ACTIVE": "#FDEDEC"}
        t0 = hr_arr[0, 0]
        for g in gt_data:
            g_start = (g[0] - t0) / 1000.0
            g_end = (g[1] - t0) / 1000.0
            if g_end >= 0 and g_start <= t_sec[-1]:
                plt.axvspan(max(0, g_start), min(t_sec[-1], g_end), color=state_colors.get(g[2], "#fff"), alpha=0.7)
                plt.text((g_start + g_end) / 2.0, 102, g[2], ha="center", fontsize=8, fontweight="bold", alpha=0.7)

        plt.plot(t_sec, bpm, color="#C0392B", lw=1.8, label="BLE Heart Rate (1 Hz BPM)")
        plt.title("Continuous Physiological HR Dynamics: State Arousals & Exponential Recovery", fontsize=11, fontweight="bold")
        plt.xlabel("Elapsed Session Time (seconds)", fontsize=10)
        plt.ylabel("Heart Rate (BPM)", fontsize=10)
        plt.ylim(45, 110)
        plt.grid(True, linestyle="--", alpha=0.4)
        plt.legend(loc="upper right", fontsize=9)
        plt.tight_layout()
        fig3_path = out_dir / "03_hr_inertia_trajectory.png"
        plt.savefig(fig3_path)
        plt.close()

    # 4. State Transition Matrix
    gt_all = conn.execute(
        "SELECT session_id, state_id FROM ground_truth_intervals ORDER BY session_id, start_sec ASC;"
    ).fetchall()
    trans_matrix = np.zeros((3, 3), dtype=np.int32)
    for i in range(len(gt_all) - 1):
        if gt_all[i][0] == gt_all[i + 1][0]:
            from_st = gt_all[i][1]
            to_st = gt_all[i + 1][1]
            trans_matrix[from_st, to_st] += 1

    row_sums = trans_matrix.sum(axis=1, keepdims=True)
    prob_matrix = np.divide(trans_matrix, row_sums, where=row_sums != 0)

    fig, ax = plt.subplots(figsize=(6, 5), dpi=150)
    cax = ax.matshow(prob_matrix, cmap="Blues", vmin=0.0, vmax=1.0)
    fig.colorbar(cax)
    class_names = ["REST", "RESTLESS", "ACTIVE"]
    ax.set_xticks(range(3))
    ax.set_yticks(range(3))
    ax.set_xticklabels(class_names, fontsize=10, fontweight="bold")
    ax.set_yticklabels(class_names, fontsize=10, fontweight="bold")
    plt.title("Latent State Transition Probability Matrix", fontsize=11, fontweight="bold", pad=20)
    plt.xlabel("Transition To State", fontsize=10)
    plt.ylabel("Transition From State", fontsize=10)

    for i in range(3):
        for j in range(3):
            val = prob_matrix[i, j]
            count = trans_matrix[i, j]
            ax.text(j, i, f"{val:.2f}\n(n={count})", ha="center", va="center",
                    color="white" if val > 0.5 else "black", fontsize=9, fontweight="bold")
    plt.tight_layout()
    fig4_path = out_dir / "04_transition_matrix.png"
    plt.savefig(fig4_path)
    plt.close()

    # 5. Placement Mechanical Attenuation
    df_placement = pl.read_database(
        """
        SELECT s.device_placement, w.ground_truth_label,
               ROUND(AVG(w.motion_rms), 4) AS mean_rms,
               ROUND(AVG(w.variance), 4) AS mean_var
        FROM feature_windows w
        JOIN sessions s ON w.session_id = s.session_id
        GROUP BY s.device_placement, w.ground_truth_label
        ORDER BY s.device_placement, w.ground_truth_label;
        """,
        conn,
    )

    placements = ["MATTRESS", "BODY_CHEST", "NIGHTSTAND"]
    states = ["REST", "RESTLESS", "ACTIVE"]
    rms_grid = np.zeros((len(placements), len(states)))

    for r in df_placement.to_dicts():
        p_idx = placements.index(r["device_placement"])
        s_idx = states.index(r["ground_truth_label"])
        rms_grid[p_idx, s_idx] = r["mean_rms"]

    fig, ax = plt.subplots(figsize=(7, 4.5), dpi=150)
    x_pos = np.arange(len(states))
    w = 0.25
    ax.bar(x_pos - w, rms_grid[0], width=w, label="MATTRESS (Baseline)", color="#2B7A78", edgecolor="#111")
    ax.bar(x_pos, rms_grid[1], width=w, label="BODY_CHEST (Direct Couple)", color="#17252A", edgecolor="#111")
    ax.bar(x_pos + w, rms_grid[2], width=w, label="NIGHTSTAND (Decoupled)", color="#E07A5F", edgecolor="#111")
    ax.set_xticks(x_pos)
    ax.set_xticklabels(states, fontsize=10, fontweight="bold")
    ax.set_title("Physical Placement Attenuation: Mattress vs Nightstand vs Chest", fontsize=11, fontweight="bold")
    ax.set_ylabel("Dynamic Motion RMS (m/s²)", fontsize=10)
    ax.legend(fontsize=9)
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    fig5_path = out_dir / "05_placement_attenuation.png"
    plt.savefig(fig5_path)
    plt.close()

    # 6. Multimodal Correlation: Motion RMS vs Mean HR
    df_corr = pl.read_database(
        """
        SELECT motion_rms, mean_hr, ground_truth_label
        FROM feature_windows
        WHERE hr_available = 1 AND mean_hr IS NOT NULL
        ORDER BY RANDOM() LIMIT 2000;
        """,
        conn,
    )
    plt.figure(figsize=(7, 5), dpi=150)
    color_map = {"REST": "#2B7A78", "RESTLESS": "#F4A261", "ACTIVE": "#E76F51"}
    for st in states:
        sub_df = df_corr.filter(pl.col("ground_truth_label") == st)
        plt.scatter(
            sub_df["motion_rms"].to_numpy(),
            sub_df["mean_hr"].to_numpy(),
            c=color_map[st],
            label=st,
            alpha=0.45,
            s=16,
            edgecolors="none",
        )
    plt.title("Multimodal State Distribution: Dynamic Motion RMS vs Mean HR", fontsize=11, fontweight="bold")
    plt.xlabel("Dynamic Accelerometer RMS (m/s²)", fontsize=10)
    plt.ylabel("Heart Rate (BPM)", fontsize=10)
    plt.grid(True, linestyle="--", alpha=0.4)
    plt.legend(title="Activity State", fontsize=9)
    plt.tight_layout()
    fig6_path = out_dir / "06_multimodal_correlation.png"
    plt.savefig(fig6_path)
    plt.close()

    conn.close()
    return stats


if __name__ == "__main__":
    s = run_eda()
    print("EDA Figure Generation Complete! Stats:", s)
