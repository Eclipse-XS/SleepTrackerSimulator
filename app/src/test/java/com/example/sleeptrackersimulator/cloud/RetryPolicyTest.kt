package com.example.sleeptrackersimulator.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {
    @Test fun `transient failure retries with ordered backoff and succeeds`() = runTest {
        val delays = mutableListOf<Long>(); var attempts = 0
        val result = RetryPolicy(listOf(500, 1000, 2000)) { delays += it }.execute {
            attempts++; if (attempts < 2) throw CloudOperationException(CloudErrorType.SERVER, "temporary")
            "ok"
        }
        assertEquals("ok", result); assertEquals(2, attempts); assertEquals(listOf(500L), delays)
    }
    @Test fun `maximum attempts are bounded`() = runTest {
        var attempts = 0
        try { RetryPolicy(listOf(1, 2)) {}.execute<Unit> { attempts++; throw CloudOperationException(CloudErrorType.NETWORK, "offline") }; fail() }
        catch (_: CloudOperationException) { assertEquals(3, attempts) }
    }
    @Test fun `permission failure is not retried`() = runTest {
        var attempts = 0
        try { RetryPolicy(listOf(1, 2)) {}.execute<Unit> { attempts++; throw CloudOperationException(CloudErrorType.PERMISSION, "denied") }; fail() }
        catch (_: CloudOperationException) { assertEquals(1, attempts) }
    }
    @Test(expected = CancellationException::class) fun `cancellation propagates`() = runTest {
        RetryPolicy(listOf(1)) {}.execute<Unit> { throw CancellationException("cancel") }
    }
}
