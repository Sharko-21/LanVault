package app.lanvault.core.transport

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TransferCapacityTest {
    @Test
    fun oldDesktopCapacityAndUnknownCapabilityRemainCompatible() {
        assertEquals(2, advertisedTransferCapacity(buildJsonObject {}))
        assertEquals(2, advertisedTransferCapacity(info(JsonPrimitive(2))))
        assertEquals(2, advertisedTransferCapacity(info(JsonPrimitive("invalid"))))
    }

    @Test
    fun newCapacityIsBoundedToFourWorkers() {
        assertEquals(4, advertisedTransferCapacity(info(JsonPrimitive(4))))
        assertEquals(4, advertisedTransferCapacity(info(JsonPrimitive(100))))
        assertEquals(1, advertisedTransferCapacity(info(JsonPrimitive(1))))
        assertEquals(1, advertisedTransferCapacity(info(JsonPrimitive(0))))
    }

    private fun info(value: JsonElement) = buildJsonObject {
        put("capabilities", buildJsonObject { put("maxConcurrentUploads", value) })
    }
}
