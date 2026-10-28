"""DuckDB analytics engine for querying SleepSense Parquet datasets."""

from __future__ import annotations

from pathlib import Path

import duckdb
import polars as pl


class SleepDataAnalytics:
    """Provides analytical and data-quality queries using DuckDB on Parquet files."""

    def __init__(self, dataset_path: Path | str):
        self.dataset_path = Path(dataset_path)
        self.con = duckdb.connect(database=":memory:")
        self._register_views()

    def _register_views(self) -> None:
        """Registers the parquet files as a view in DuckDB."""
        parquet_glob = str(self.dataset_path / "**/*.parquet").replace("\\", "/")
        self.con.sql(f"""
            CREATE OR REPLACE VIEW windows AS
            SELECT * FROM read_parquet('{parquet_glob}', hive_partitioning = true);
        """)

    def get_session_summaries(self) -> pl.DataFrame:
        """Returns per-session summary metrics including duration, window count, motion, and HR."""
        query = """
            SELECT
                session_id,
                COUNT(*) AS total_windows,
                MIN(start_utc_millis) AS start_utc,
                MAX(end_utc_millis) AS end_utc,
                ROUND((MAX(end_utc_millis) - MIN(start_utc_millis)) / 60000.0, 2) AS duration_minutes,
                ROUND(AVG(motion_rms), 4) AS avg_motion_rms,
                ROUND(AVG(jerk), 4) AS avg_jerk,
                ROUND(AVG(variance), 4) AS avg_variance,
                ROUND(AVG(mean_hr) FILTER (WHERE hr_available), 1) AS avg_heart_rate,
                ROUND(MIN(mean_hr) FILTER (WHERE hr_available), 1) AS min_heart_rate,
                ROUND(MAX(mean_hr) FILTER (WHERE hr_available), 1) AS max_heart_rate,
                ROUND(100.0 * COUNT(CASE WHEN hr_available THEN 1 END) / COUNT(*), 1) AS hr_coverage_pct
            FROM windows
            GROUP BY session_id
            ORDER BY start_utc DESC;
        """
        return self.con.sql(query).pl()

    def get_class_distributions(self) -> pl.DataFrame:
        """Calculates label distribution and percentages across the entire dataset and per session."""
        query = """
            WITH counts AS (
                SELECT
                    session_id,
                    label,
                    COUNT(*) AS window_count
                FROM windows
                GROUP BY session_id, label
            ),
            totals AS (
                SELECT
                    session_id,
                    SUM(window_count) AS session_total
                FROM counts
                GROUP BY session_id
            )
            SELECT
                c.session_id,
                c.label,
                c.window_count,
                ROUND(100.0 * c.window_count / t.session_total, 2) AS percentage
            FROM counts c
            JOIN totals t ON c.session_id = t.session_id
            ORDER BY c.session_id, c.label;
        """
        return self.con.sql(query).pl()

    def get_multimodal_correlation(self) -> pl.DataFrame:
        """Computes Pearson correlation between motion RMS and heart rate for sessions with HR data."""
        query = """
            SELECT
                session_id,
                COUNT(*) AS multimodal_samples,
                ROUND(CORR(motion_rms, mean_hr), 4) AS corr_motion_hr,
                ROUND(AVG(motion_rms), 4) AS avg_motion_rms,
                ROUND(AVG(mean_hr), 1) AS avg_mean_hr
            FROM windows
            WHERE hr_available AND mean_hr IS NOT NULL
            GROUP BY session_id
            HAVING COUNT(*) >= 5
            ORDER BY session_id;
        """
        return self.con.sql(query).pl()

    def get_rest_quality_scores(self) -> pl.DataFrame:
        """Computes rest efficiency and restlessness score per session."""
        query = """
            SELECT
                session_id,
                COUNT(*) AS total_windows,
                COUNT(CASE WHEN label = 'REST' THEN 1 END) AS rest_windows,
                COUNT(CASE WHEN label = 'RESTLESS' THEN 1 END) AS restless_windows,
                COUNT(CASE WHEN label = 'ACTIVE' THEN 1 END) AS active_windows,
                ROUND(100.0 * COUNT(CASE WHEN label = 'REST' THEN 1 END) / COUNT(*), 1) AS rest_fraction_pct,
                ROUND(100.0 * COUNT(CASE WHEN label = 'RESTLESS' THEN 1 END) / COUNT(*), 1) AS restlessness_pct,
                ROUND(GREATEST(0.0, LEAST(100.0,
                    100.0 * COUNT(CASE WHEN label = 'REST' THEN 1 END) / COUNT(*) -
                    (100.0 * COUNT(CASE WHEN label = 'ACTIVE' THEN 1 END) / COUNT(*) * 2.0)
                )), 1) AS sleep_quality_score
            FROM windows
            GROUP BY session_id
            ORDER BY session_id;
        """
        return self.con.sql(query).pl()
