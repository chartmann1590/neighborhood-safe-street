package com.neighborhood.safestreet.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreCommunityRepository {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val localReports = mutableListOf<Incident>()

    suspend fun ensureAuthenticated() {
        try {
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        } catch (e: Exception) {
            Log.w("FirestoreRepo", "Anonymous auth failed or offline: ${e.message}")
        }
    }

    suspend fun fetchActiveCommunityReports(): List<Incident> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<Incident>()

        // Add any locally submitted reports that haven't expired
        synchronized(localReports) {
            localReports.removeAll { it.isExpired }
            results.addAll(localReports)
        }

        try {
            ensureAuthenticated()
            val snapshot = firestore.collection("communityReports")
                .orderBy("createdAtEpochMs", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            for (doc in snapshot.documents) {
                val expiresAt = doc.getLong("expiresAtEpochMs") ?: (now + 24 * 3600 * 1000L)
                val flags = doc.getLong("communityFlags")?.toInt() ?: 0

                // Filter expired or heavily flagged reports
                if (expiresAt <= now || flags >= 3) continue

                val categoryStr = doc.getString("category") ?: "other_safety"
                val category = IncidentCategory.fromCode(categoryStr)
                val confirmations = doc.getLong("communityConfirmations")?.toInt() ?: 0

                val provenance = if (confirmations >= 2) {
                    ProvenanceType.COMMUNITY_CONFIRMED
                } else {
                    ProvenanceType.COMMUNITY_UNVERIFIED
                }

                results.add(
                    Incident(
                        id = doc.id,
                        category = category,
                        subcategory = doc.getString("subcategory"),
                        title = doc.getString("title") ?: "Community Observation",
                        description = doc.getString("note"),
                        occurredAtEpochMs = doc.getLong("createdAtEpochMs") ?: now,
                        sourceUpdatedAtEpochMs = doc.getLong("createdAtEpochMs") ?: now,
                        receivedAtEpochMs = doc.getLong("createdAtEpochMs") ?: now,
                        expiresAtEpochMs = expiresAt,
                        latitude = doc.getDouble("latitude") ?: 0.0,
                        longitude = doc.getDouble("longitude") ?: 0.0,
                        displayAddress = doc.getString("displayAddress") ?: "Nearby Community",
                        sourceId = "community_reports",
                        agency = "Community Observation",
                        provenance = provenance,
                        communityConfirmations = confirmations,
                        communityFlags = flags,
                        licenseInfo = "Community Submitted (Public Awareness)",
                        isHighPriority = confirmations >= 2
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("FirestoreRepo", "Firestore fetch error, using local buffer: ${e.message}")
        }

        return results.distinctBy { it.id }
    }

    suspend fun submitReport(
        category: IncidentCategory,
        latitude: Double,
        longitude: Double,
        note: String
    ): Incident {
        ensureAuthenticated()
        val now = System.currentTimeMillis()
        val expiresAt = now + (24 * 60 * 60 * 1000L) // Exact immutable 24 hours

        // Quantize coordinates (~100m) for privacy protection
        val quantizedLat = Math.round(latitude * 1000.0) / 1000.0
        val quantizedLon = Math.round(longitude * 1000.0) / 1000.0

        val sanitizedNote = note.take(500).trim()
        val id = "comm_" + UUID.randomUUID().toString().take(12)
        val uid = auth.currentUser?.uid ?: "anon_user"

        val incident = Incident(
            id = id,
            category = category,
            title = "${category.displayName} (Observed)",
            description = sanitizedNote,
            occurredAtEpochMs = now,
            sourceUpdatedAtEpochMs = now,
            receivedAtEpochMs = now,
            expiresAtEpochMs = expiresAt,
            latitude = quantizedLat,
            longitude = quantizedLon,
            displayAddress = "Approx. near Lat: $quantizedLat, Lon: $quantizedLon",
            sourceId = "community_reports",
            agency = "Community Observation",
            provenance = ProvenanceType.COMMUNITY_UNVERIFIED,
            communityConfirmations = 0,
            communityFlags = 0,
            licenseInfo = "Community Submitted (Public Awareness)",
            isHighPriority = false
        )

        // Store locally immediately for responsive UI
        synchronized(localReports) {
            localReports.add(0, incident)
        }

        try {
            val docData = hashMapOf(
                "id" to id,
                "authorUid" to uid,
                "category" to category.code,
                "title" to incident.title,
                "note" to sanitizedNote,
                "latitude" to quantizedLat,
                "longitude" to quantizedLon,
                "displayAddress" to incident.displayAddress,
                "createdAtEpochMs" to now,
                "expiresAtEpochMs" to expiresAt,
                "communityConfirmations" to 0,
                "communityFlags" to 0,
                "verification" to hashMapOf("state" to "community_unverified"),
                "source" to hashMapOf("kind" to "community")
            )
            firestore.collection("communityReports").document(id).set(docData).await()
        } catch (e: Exception) {
            Log.w("FirestoreRepo", "Failed to write to remote Firestore: ${e.message}")
        }

        return incident
    }

    suspend fun confirmReport(reportId: String): Boolean {
        ensureAuthenticated()
        val uid = auth.currentUser?.uid ?: return false

        // Update local buffer if present
        synchronized(localReports) {
            val idx = localReports.indexOfFirst { it.id == reportId }
            if (idx != -1) {
                val item = localReports[idx]
                val newCount = item.communityConfirmations + 1
                localReports[idx] = item.copy(
                    communityConfirmations = newCount,
                    provenance = if (newCount >= 2) ProvenanceType.COMMUNITY_CONFIRMED else item.provenance
                )
            }
        }

        try {
            val docRef = firestore.collection("communityReports").document(reportId)
            val confRef = docRef.collection("confirmations").document(uid)
            confRef.set(hashMapOf("confirmedAt" to System.currentTimeMillis())).await()
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val current = snapshot.getLong("communityConfirmations") ?: 0
                transaction.update(docRef, "communityConfirmations", current + 1)
            }.await()
            return true
        } catch (e: Exception) {
            Log.w("FirestoreRepo", "Confirm failed: ${e.message}")
            return true
        }
    }

    suspend fun flagReport(reportId: String, reason: String): Boolean {
        ensureAuthenticated()
        val uid = auth.currentUser?.uid ?: return false

        synchronized(localReports) {
            val idx = localReports.indexOfFirst { it.id == reportId }
            if (idx != -1) {
                val item = localReports[idx]
                val newFlags = item.communityFlags + 1
                if (newFlags >= 3) {
                    localReports.removeAt(idx)
                } else {
                    localReports[idx] = item.copy(communityFlags = newFlags)
                }
            }
        }

        try {
            val docRef = firestore.collection("communityReports").document(reportId)
            val flagRef = docRef.collection("flags").document(uid)
            flagRef.set(hashMapOf("reason" to reason, "flaggedAt" to System.currentTimeMillis())).await()
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val current = snapshot.getLong("communityFlags") ?: 0
                val newFlags = current + 1
                if (newFlags >= 3) {
                    transaction.update(docRef, "moderationState", "removed")
                }
                transaction.update(docRef, "communityFlags", newFlags)
            }.await()
            return true
        } catch (e: Exception) {
            Log.w("FirestoreRepo", "Flag failed: ${e.message}")
            return true
        }
    }
}
