"""Time synchronization and multimodal windowing between accelerometer and heart rate."""

from __future__ import annotations

import polars as pl


class SensorSynchronizer:
    """Synchronizes high-frequency accelerometer samples with lower-frequency BLE heart rate."""

    @staticmethod
    def create_synchronized_windows(
        accel_df: pl.DataFrame,
        hr_df: pl.DataFrame,
        window_size: int = 20,
        max_hr_staleness_ms: int = 10_000,
    ) -> list[dict[str, any]]:
        """Segments accelerometer into fixed-size windows and aligns corresponding heart rate measurements."""
        if accel_df.is_empty():
            return []

        windows: list[dict[str, any]] = []

        # Group by session_id
        sessions = accel_df["session_id"].unique().to_list()
        has_hr_data = not hr_df.is_empty() and "session_id" in hr_df.columns and "utc_millis" in hr_df.columns

        for session_id in sessions:
            session_accel = accel_df.filter(pl.col("session_id") == session_id).sort("elapsed_nanos")
            session_hr = (
                hr_df.filter(pl.col("session_id") == session_id).sort("utc_millis")
                if has_hr_data
                else pl.DataFrame()
            )

            n_samples = len(session_accel)
            n_windows = n_samples // window_size

            for i in range(n_windows):
                w_accel = session_accel.slice(i * window_size, window_size)
                start_utc = int(w_accel["utc_millis"][0])
                end_utc = int(w_accel["utc_millis"][-1])
                start_elapsed = int(w_accel["elapsed_nanos"][0])
                end_elapsed = int(w_accel["elapsed_nanos"][-1])

                # Match HR samples within [start_utc - max_hr_staleness_ms, end_utc + 1000]
                matching_hr = (
                    session_hr.filter(
                        (pl.col("utc_millis") >= (start_utc - max_hr_staleness_ms)) &
                        (pl.col("utc_millis") <= (end_utc + 1_000))
                    )
                    if has_hr_data and len(session_hr) > 0
                    else pl.DataFrame()
                )

                if len(matching_hr) > 0:
                    mean_bpm = float(matching_hr["bpm"].mean())
                    hr_available = True
                    hr_source = str(matching_hr["source_kind"][0])
                else:
                    mean_bpm = None
                    hr_available = False
                    hr_source = "NONE"

                window_dict = {
                    "window_id": f"{session_id}-w{i:05d}",
                    "session_id": session_id,
                    "start_utc_millis": start_utc,
                    "end_utc_millis": end_utc,
                    "start_elapsed_nanos": start_elapsed,
                    "end_elapsed_nanos": end_elapsed,
                    "accel_x": w_accel["x"].to_list(),
                    "accel_y": w_accel["y"].to_list(),
                    "accel_z": w_accel["z"].to_list(),
                    "mean_hr": mean_bpm,
                    "hr_available": hr_available,
                    "hr_source": hr_source,
                    "provenance": str(w_accel["source_kind"][0]),
                }
                windows.append(window_dict)

        return windows
