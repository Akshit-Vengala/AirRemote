package com.airremote.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RemoteMessageTest {

    @Test
    fun `gyro message round-trips`() {
        val original: RemoteMessage = GyroMessage(dx = 0.4, dy = -0.2)
        val json = ProtocolJson.encodeToString(original)
        assertEquals(original, ProtocolJson.decodeFromString<RemoteMessage>(json))
    }

    @Test
    fun `click message encodes to expected JSON`() {
        val msg: RemoteMessage = ClickMessage
        assertEquals(
            """{"type":"click"}""",
            ProtocolJson.encodeToString(msg),
        )
    }

    @Test
    fun `text message round-trips`() {
        val original: RemoteMessage = TextMessage("hello world")
        val json = ProtocolJson.encodeToString(original)
        assertEquals(original, ProtocolJson.decodeFromString<RemoteMessage>(json))
    }
}
