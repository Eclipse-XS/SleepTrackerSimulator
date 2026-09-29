# SleepTrackerSimulator

Android application for educational monitoring of movement activity during sleep using accelerometer data.

This project is a simulator for coursework purposes. It does not detect medical sleep stages and must not be used for medical assessment.

## Current functionality

- accelerometer data acquisition;
- X, Y, and Z value visualization;
- acceleration magnitude calculation;
- `STILL` / `MOVEMENT` classification;
- graphical position and movement indicator;
- Android Emulator Virtual Sensors support;
- deterministic mock BLE device discovery and connection lifecycle;
- simulated command, status notification, and acknowledgement exchange;
- StateFlow-driven sensor and BLE UI state.

## Tech stack

- Kotlin
- Jetpack Compose
- Android `SensorManager`
- Coroutines and Flow
- ViewModel and StateFlow
