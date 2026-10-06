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

class FirebaseRemoteControlRepository(
    database: FirebaseDatabase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL),
    private val authRepository: AuthRepository = FirebaseAnonymousAuthRepository(),
) : RemoteControlRepository {
    private val users = database.reference.child("users")

    override val events: Flow<RemoteControlEvent> = callbackFlow {
        val uid = try {
            authRepository.ensureAuthenticated()
        } catch (error: Exception) {
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
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(RemoteControlEvent.Error(error.message))
            }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }
}
