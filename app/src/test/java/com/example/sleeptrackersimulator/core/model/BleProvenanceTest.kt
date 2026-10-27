package com.example.sleeptrackersimulator.core.model

import org.junit.Assert.assertFalse
import org.junit.Test

class BleProvenanceTest {
    @Test fun unverifiedBleIsNotAcceptedAsRealData() {
        assertFalse(SourceKind.BLE_UNVERIFIED.isRealData)
        assertFalse(SourceKind.SIMULATED.isRealData)
    }
}
