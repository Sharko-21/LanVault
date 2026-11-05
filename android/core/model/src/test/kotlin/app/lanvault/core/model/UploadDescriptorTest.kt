package app.lanvault.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadDescriptorTest {
    @Test
    fun sharedWireFixtures() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/uploads.json")).bufferedReader().use {
                it.readText()
            }
        val fixtures = Json.parseToJsonElement(text).jsonObject
        val base = fixtures.getValue("base").jsonObject
        for (case in fixtures.getValue("cases").jsonArray) {
            val entry = case.jsonObject
            val payload = JsonObject(base + entry.getValue("patch").jsonObject)
            val valid = runCatching {
                Json.decodeFromJsonElement(UploadDescriptor.serializer(), payload).validatedSize()
            }
                .isSuccess
            assertEquals(
                entry.getValue("name").jsonPrimitive.content,
                entry.getValue("valid").jsonPrimitive.boolean,
                valid,
            )
        }
    }

    @Test
    fun preservesLargeByteCount() {
        assertEquals(9007199254740993L, parsePositiveByteCount("9007199254740993"))
    }
}
