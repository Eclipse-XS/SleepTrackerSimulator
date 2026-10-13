package com.example.sleeptrackersimulator.cloud

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.tasks.await
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

interface AuthRepository {
    suspend fun ensureAuthenticated(): String
    val currentUid: String?
}

class FirebaseAnonymousAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {
    override val currentUid: String? get() = auth.currentUser?.uid

    override suspend fun ensureAuthenticated(): String {
        auth.currentUser?.uid?.let {
            DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.SKIPPED, details = mapOf("reason" to "existing_session"))
            return it
        }
        DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.STARTED)
        return try {
            requireNotNull(auth.signInAnonymously().await().user?.uid) { "Anonymous authentication returned no user" }.also {
                DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.SUCCESS)
            }
        } catch (error: FirebaseNetworkException) {
            DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.FAILED, details = mapOf("reason" to "network", "exceptionType" to error.javaClass.simpleName))
            throw CloudOperationException(CloudErrorType.NETWORK, "Authentication network failure", error)
        } catch (error: FirebaseAuthException) {
            DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.FAILED, details = mapOf("reason" to "auth", "exceptionType" to error.javaClass.simpleName))
            throw CloudOperationException(CloudErrorType.AUTH, "Anonymous authentication failed", error)
        } catch (error: FirebaseException) {
            DiagnosticLog.event(DiagnosticTags.CLOUD, "anonymousAuthentication", DiagnosticResult.FAILED, details = mapOf("reason" to "transport", "exceptionType" to error.javaClass.simpleName))
            throw CloudOperationException(CloudErrorType.NETWORK, "Authentication transport failure", error)
        }
    }
}
