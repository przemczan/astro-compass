package com.astroguider.alignment

import com.astroguider.astro.Quaternion
import com.astroguider.astro.Vector3
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ALIGNMENT_KEY = "alignment_model"

// Persistence DTOs kept separate from the domain types (Vector3/Quaternion/AlignmentModel stay
// framework-free) -- persistence is a concern of this class alone.
@Serializable
private data class PersistedVector3(val x: Double, val y: Double, val z: Double)

@Serializable
private data class PersistedQuaternion(val w: Double, val x: Double, val y: Double, val z: Double)

@Serializable
private data class PersistedPoint(
    val sky: PersistedVector3,
    val sensor: PersistedVector3,
    val capturedAtEpochMillis: Long,
    val targetId: String,
    val source: AlignmentSource,
)

@Serializable
private data class PersistedModel(
    val rotation: PersistedQuaternion,
    val points: List<PersistedPoint>,
    val rmsResidualDegrees: Double,
    val computedAtEpochMillis: Long,
)

/** Persists the current [AlignmentModel] so alignment survives an app restart. */
class AlignmentStore(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(model: AlignmentModel) {
        settings.putString(ALIGNMENT_KEY, json.encodeToString(model.toPersisted()))
    }

    fun load(): AlignmentModel? {
        val raw = settings.getStringOrNull(ALIGNMENT_KEY) ?: return null
        return runCatching { json.decodeFromString<PersistedModel>(raw).toDomain() }.getOrNull()
    }

    fun clear() {
        settings.remove(ALIGNMENT_KEY)
    }

    private fun Vector3.toPersisted() = PersistedVector3(x, y, z)
    private fun PersistedVector3.toDomain() = Vector3(x, y, z)

    private fun AlignmentPoint.toPersisted() = PersistedPoint(
        sky = skyDirection.toPersisted(),
        sensor = sensorDirection.toPersisted(),
        capturedAtEpochMillis = capturedAtEpochMillis,
        targetId = targetId,
        source = source,
    )

    private fun PersistedPoint.toDomain() = AlignmentPoint(
        skyDirection = sky.toDomain(),
        sensorDirection = sensor.toDomain(),
        capturedAtEpochMillis = capturedAtEpochMillis,
        targetId = targetId,
        source = source,
    )

    private fun AlignmentModel.toPersisted() = PersistedModel(
        rotation = PersistedQuaternion(sensorToSky.w, sensorToSky.x, sensorToSky.y, sensorToSky.z),
        points = points.map { it.toPersisted() },
        rmsResidualDegrees = rmsResidualDegrees,
        computedAtEpochMillis = computedAtEpochMillis,
    )

    private fun PersistedModel.toDomain() = AlignmentModel(
        sensorToSky = Quaternion(rotation.w, rotation.x, rotation.y, rotation.z),
        points = points.map { it.toDomain() },
        rmsResidualDegrees = rmsResidualDegrees,
        computedAtEpochMillis = computedAtEpochMillis,
    )
}
