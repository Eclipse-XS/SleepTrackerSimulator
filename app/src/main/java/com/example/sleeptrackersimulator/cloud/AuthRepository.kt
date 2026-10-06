package com.example.sleeptrackersimulator.cloud

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.tasks.await

interface AuthRepository {
    suspend fun ensureAuthenticated(): String
    val currentUid: String?
}

class FirebaseAnonymousAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {
    override val currentUid: String? get() = auth.currentUser?.uid

    override suspend fun ensureAuthenticated(): String {
        auth.currentUser?.uid?.let { return it }
        return try {
            requireNotNull(auth.signInAnonymously().await().user?.uid) { "Anonymous authentication returned no user" }
        } catch (error: FirebaseNetworkException) {
            throw CloudOperationException(CloudErrorType.NETWORK, "Authentication network failure", error)
        } catch (error: FirebaseAuthException) {
            throw CloudOperationException(CloudErrorType.AUTH, "Anonymous authentication failed", error)
        } catch (error: FirebaseException) {
            throw CloudOperationException(CloudErrorType.NETWORK, "Authentication transport failure", error)
        }
    }
}
