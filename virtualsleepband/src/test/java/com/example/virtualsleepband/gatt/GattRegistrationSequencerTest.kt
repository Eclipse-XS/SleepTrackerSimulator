package com.example.virtualsleepband.gatt

import org.junit.Assert.assertEquals
import org.junit.Test

class GattRegistrationSequencerTest {
    @Test fun servicesMustRegisterInOrderBeforeAdvertising() {
        val sequencer = GattRegistrationSequencer()
        sequencer.start()
        assertEquals(RegistrationAction.ADD_BATTERY, sequencer.onServiceAdded(true, VirtualGattServer.UUID_HEART_RATE_SERVICE))
        assertEquals(RegistrationAction.START_ADVERTISING, sequencer.onServiceAdded(true, VirtualGattServer.UUID_BATTERY_SERVICE))
        assertEquals(RegistrationAction.IGNORE, sequencer.onServiceAdded(true, VirtualGattServer.UUID_BATTERY_SERVICE))
    }

    @Test fun failureOrOutOfOrderCallbackPreventsAdvertising() {
        val sequencer = GattRegistrationSequencer()
        sequencer.start()
        assertEquals(RegistrationAction.FAIL, sequencer.onServiceAdded(true, VirtualGattServer.UUID_BATTERY_SERVICE))
        assertEquals(RegistrationAction.IGNORE, sequencer.onServiceAdded(true, VirtualGattServer.UUID_HEART_RATE_SERVICE))
    }

    @Test fun stoppedSequencerIgnoresStaleCallbacks() {
        val sequencer = GattRegistrationSequencer()
        sequencer.start()
        sequencer.stop()
        assertEquals(RegistrationAction.IGNORE, sequencer.onServiceAdded(true, VirtualGattServer.UUID_HEART_RATE_SERVICE))
    }
}
