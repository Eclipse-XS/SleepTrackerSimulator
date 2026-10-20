package com.example.sleeptrackersimulator.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticEventTest {
    @Test
    fun `format contains stable structured fields and sanitizes line breaks`() {
        val message = DiagnosticEvent(
            operation = "firebase upload",
            result = DiagnosticResult.FAILED,
            source = "REMOTE",
            correlationId = "op-12",
            details = mapOf("reason" to "permission\ndenied", "pendingCount" to 2),
        ).format()

        assertEquals(
            "operation=firebase_upload result=FAILED source=REMOTE correlationId=op-12 " +
                "pendingCount=2 reason=permission_denied",
            message,
        )
        assertFalse(message.contains('\n'))
    }
}
