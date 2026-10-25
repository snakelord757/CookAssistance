package com.cookassistance.server

import kotlin.test.*
import kotlinx.serialization.json.*

class DecisionTest {
    @Test fun completionIncludesRequiredGenerationControls() {
        val wire = json.parseToJsonElement(json.encodeToString(Completion("deepseek-flash", listOf(Message("user", "question"))))).jsonObject
        assertEquals("json_object", wire.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(600, wire.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(0.0, wire.getValue("temperature").jsonPrimitive.double)
    }
    @Test fun unrelatedNeverReturnsModelText() {
        assertEquals(Reply(ignored = true), validatedDecision("""{"related":false,"answer":"unrelated secret text"}"""))
    }
    @Test fun malformedAndBlankRelatedFailClosed() {
        assertFailsWith<InvalidUpstream> { validatedDecision("not json") }
        assertFailsWith<InvalidUpstream> { validatedDecision("""{"related":true,"answer":""}""") }
    }
    @Test fun relatedAnswerIsPreserved() {
        assertEquals("Нарежьте лук.", validatedDecision("""{"related":true,"answer":"Нарежьте лук."}""").answer)
    }
}
