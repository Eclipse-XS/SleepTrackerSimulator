# 03 — Sensor and ML pipeline; online versus offline

**Android runtime:** 20 Hz is the default requested accelerometer rate, not a guarantee. The coordinator creates 20-sample windows with stride 20. Model tensor shape is `[1,20,3]`; Python's scalar feature dictionaries are not its direct input.

```mermaid
flowchart LR
  subgraph AND["Android online runtime"]
    ACC["Phone XYZ samples - default 20 Hz"]
    HR["BLE or synthetic WebSocket HR"]
    VAL["Clock anchor / validation / provenance"]
    WIN["20 XYZ samples - stride 20"]
    FEAT["Physical and quality scalar features"]
    SELECT["ModelSelectionEngine"]
    MULTI["Multimodal TFLite: XYZ + HR availability"]
    SINGLE["Accelerometer TFLite: XYZ"]
    FALL["Rule-based fallback / uncertain"]
    ROOM[("Room v8")]
    ACC --> VAL
    HR --> VAL
    VAL --> WIN
    WIN --> FEAT --> ROOM
    WIN --> SELECT
    HR --> SELECT
    SELECT --> MULTI
    SELECT --> SINGLE
    SELECT --> FALL
    MULTI --> ROOM
    SINGLE --> ROOM
    FALL --> ROOM
  end
  subgraph OFF["Python offline data and ML"]
    EXP["Android export / synthetic generation"]
    CLEAN["Ingest / clean / align / validate"]
    PAR["Features, Parquet, DuckDB"]
    TRAIN["Training, evaluation, promotion"]
    EXP --> CLEAN --> PAR --> TRAIN
  end
  ROOM -.->|Export| EXP
  TRAIN -.->|Verified TFLite artifact for next build| SELECT
```

Grounding: `RecordingService.kt`, `SessionCoordinatorImpl.kt`, `SignalProcessingPipeline.kt`, `ModelSelectionEngine.kt`, `pipelines/features/feature_extractor.py`, `ml/artifacts/deployment_manifest.json`. Fresh HR maximum age is 10 s. A full window is nominally one second at 20 Hz, with approximately 950 ms between first and last sample timestamps.
