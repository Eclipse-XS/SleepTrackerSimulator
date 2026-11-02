package android.os;

import android.media.AudioAttributes;

public class TestVibrator extends Vibrator {
    public boolean hasVibratorValue = true;
    public int vibrateCallCount = 0;
    public int cancelCallCount = 0;
    public VibrationEffect lastVibrationEffect = null;

    public TestVibrator() {
        super();
    }

    @Override
    public boolean hasVibrator() {
        return hasVibratorValue;
    }

    @Override
    public boolean hasAmplitudeControl() {
        return false;
    }

    @Override
    public void vibrate(long milliseconds) {
        vibrateCallCount++;
    }

    @Override
    public void vibrate(long[] pattern, int repeat) {
        vibrateCallCount++;
    }

    @Override
    public void vibrate(VibrationEffect vibe) {
        vibrateCallCount++;
        lastVibrationEffect = vibe;
    }

    @Override
    public void vibrate(VibrationEffect vibe, VibrationAttributes attributes) {
        vibrateCallCount++;
        lastVibrationEffect = vibe;
    }

    @Override
    public void vibrate(VibrationEffect vibe, AudioAttributes attributes) {
        vibrateCallCount++;
        lastVibrationEffect = vibe;
    }

    @Override
    public void cancel() {
        cancelCallCount++;
    }
}
