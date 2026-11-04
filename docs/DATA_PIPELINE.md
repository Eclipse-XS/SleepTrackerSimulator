# Sensor data and Python pipeline

## Android runtime

`RecordingService` requests accelerometer measurements (20 Hz by default), records monotonic timestamps and sends them to `SessionCoordinatorImpl`. Validation rejects non-finite accelerometer samples and out-of-range values. Accepted phone and HR samples are separately persisted to Room v8 with source provenance.

`SlidingWindowSegmenter(windowSize=20, stride=20)` builds non-overlapping windows. Android's `FeatureExtractor` derives scalar motion/quality statistics for stored `feature_windows`, while the TFLite classifiers receive raw XYZ tensors `[1,20,3]`. The multimodal model separately consumes normalized BPM and availability `[1,2]` if HR is fresh and valid (maximum age 10 s). **Scalar feature columns are not the deployed model input tensor.**

## Offline Python

The offline path has distinct responsibilities:

- `pipelines/ingestion/android_export_reader.py` — Android export ingestion.
- `pipelines/transformation/cleaning.py` and `synchronizer.py` — validation-ready cleaning, alignment and windows.
- `pipelines/features/feature_extractor.py` — analytical features and the versioned Android feature-parity contract.
- `pipelines/validation/schemas.py` — schema validation.
- `pipelines/datasets/dataset_builder.py` — validated Parquet dataset publication.
- `pipelines/analytics/duckdb_analytics.py` — SQL analytics.
- `ml/data/window_builder.py`, `ml/training/`, `ml/export/` — training data, experiments and TFLite deployment checks.

Python output dictionaries include bookkeeping, coverage and quality metadata. Their number of fields is **not** the number of inputs to the on-device neural network. See the [Android/Python contract](datascience/ANDROID_PYTHON_DATA_CONTRACT.md) and [ML model card](MODEL_CARD.md).

## Limits

Training and shifted evaluation use generated/synthetic datasets; real-world performance, activity labels and source provenance require independent validation. A missing HR measurement must not be imputed and presented as observed physiology.
