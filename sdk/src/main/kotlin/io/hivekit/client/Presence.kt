package io.hivekit.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = PresenceStatusSerializer::class)
enum class PresenceStatus { CONNECTED, DISCONNECTED, UNKNOWN }
internal object PresenceStatusSerializer : KSerializer<PresenceStatus> {
    override val descriptor = PrimitiveSerialDescriptor("PresenceStatus", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): PresenceStatus = when (decoder.decodeString()) {
        "con", "connected" -> PresenceStatus.CONNECTED
        "dis", "disconnected" -> PresenceStatus.DISCONNECTED
        else -> PresenceStatus.UNKNOWN
    }
    override fun serialize(encoder: Encoder, value: PresenceStatus) =
        encoder.encodeString(when (value) {
            PresenceStatus.CONNECTED -> "con"; PresenceStatus.DISCONNECTED -> "dis"; PresenceStatus.UNKNOWN -> ""
        })
}
data class PresenceEvent(val idPattern: String, val connectionStatus: PresenceStatus)
