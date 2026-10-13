package com.example.sleeptrackersimulator.actuator

import com.example.sleeptrackersimulator.cloud.CLOUD_DEVICE_ID
import com.example.sleeptrackersimulator.cloud.FIREBASE_DATABASE_URL
import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.FirebaseAnonymousAuthRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

class FirebaseRemoteControlRepository(
    database: FirebaseDatabase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL),
    private val authRepository: AuthRepository = FirebaseAnonymousAuthRepository(),
) : RemoteControlRepository {
    private val users = database.reference.child("users")

    override val events: Flow<RemoteControlEvent> = callbackFlow {
        DiagnosticLog.event(DiagnosticTags.CLOUD, "remoteListener", DiagnosticResult.STARTED)
        val uid = try {
            authRepository.ensureAuthenticated()
        } catch (error: Exception) {
            DiagnosticLog.event(DiagnosticTags.CLOUD, "remoteListener", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName))
            trySend(RemoteControlEvent.Error(error.message ?: "Firebase authentication failed"))
            close()
            return@callbackFlow
        }
        val reference = users.child(uid).child("control").child(CLOUD_DEVICE_ID)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val controls = snapshot.getValue(RemoteControlState::class.java)
                    ?: RemoteControlState()
                trySend(RemoteControlEvent.Updated(controls))
                DiagnosticLog.event(DiagnosticTags.CLOUD, "remoteControlEvent", DiagnosticResult.SUCCESS, source = "FIREBASE", details = mapOf("vibration" to controls.vibrationEnabled, "flashlight" to controls.flashlightEnabled))
            }

            override fun onCancelled(error: DatabaseError) {
                DiagnosticLog.event(
                    DiagnosticTags.CLOUD,
                    "remoteListener",
                    DiagnosticResult.FAILED,
                    details = mapOf("reason" to error.code, "errorType" to error.toException().javaClass.simpleName),
                )
                trySend(RemoteControlEvent.Error(error.message))
                close(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        DiagnosticLog.event(DiagnosticTags.CLOUD, "remoteListener", DiagnosticResult.SUCCESS)
        awaitClose {
            reference.removeEventListener(listener)
            DiagnosticLog.event(DiagnosticTags.CLOUD, "remoteListenerDisconnect", DiagnosticResult.SUCCESS)
        }
    }
}
