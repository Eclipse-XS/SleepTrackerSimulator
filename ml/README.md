# Rest movement model

This directory contains the reproducible generator, training, evaluation and
TensorFlow Lite export pipeline for Lab 6.

Contract: float32 input `[1, 20, 3]` in XYZ order at approximately 20 Hz.
Preprocessing subtracts the mean of each channel within the window, clips each
centered value to `[-4, 4]` m/s² and divides by `4`. Output `[1, 3]` contains
softmax probabilities for `STILL`, `MICRO_MOVEMENT`, and
`PRONOUNCED_MOVEMENT`.

The training data is synthetic and split by independently seeded source
sessions. Reported metrics validate only this controlled generator. The model
does not infer sleep stages and is not a medical device.

Run `python ml/train_and_export.py` in an environment with TensorFlow 2.20 and
scikit-learn 1.7.2. The script writes the model to Android assets and the exact
metrics to `ml/model_metrics.json`.
