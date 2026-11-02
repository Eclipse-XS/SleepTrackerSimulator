import sqlite3
from pathlib import Path

db_path = Path("build/device_artifacts/sleep_tracker.db")
conn = sqlite3.connect(str(db_path))
cursor = conn.cursor()

def dump_table(name, limit=3):
    cursor.execute(f"PRAGMA table_info({name})")
    cols = [c[1] for c in cursor.fetchall()]
    print(f"\n--- Table: {name} (Columns: {cols}) ---")
    count = cursor.execute(f"SELECT count(*) FROM {name}").fetchone()[0]
    print(f"Total rows: {count}")
    rows = cursor.execute(f"SELECT * FROM {name} ORDER BY rowid DESC LIMIT {limit}").fetchall()
    for r in rows:
        print("  Row:", dict(zip(cols, r)))

dump_table("recording_sessions")
dump_table("sensor_samples")
dump_table("feature_windows")
dump_table("predictions")
dump_table("cloud_outbox")
