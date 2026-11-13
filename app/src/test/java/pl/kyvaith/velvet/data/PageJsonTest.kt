package pl.kyvaith.velvet.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageJsonTest {
    @Test fun readsNestedObjectWithoutTreatingBracesInsideStringsAsStructure() {
        val html = """<script>window.netflix.reactContext = {"models":{"text":"a } brace, a { brace and a \"quote\"","rows":[{"id":7}]} }; window.after = {"ignore":true};</script>"""
        val result = PageJson.assignment(html, "netflix.reactContext")!!
        assertEquals("a } brace, a { brace and a \"quote\"", result.getJSONObject("models").getString("text"))
        assertEquals(7, result.getJSONObject("models").getJSONArray("rows").getJSONObject(0).getInt("id"))
        assertFalse(result.has("ignore"))
    }

    @Test fun readsJsonParseWithTwoLayersOfQuoteAndBackslashEscaping() {
        val expected = JSONObject().put("title", "Zażółć \"gęślą\" / C:\\film").put("description", "line 1\nline 2")
        val html = "<script>netflix.reactContext = JSON.parse(${JSONObject.quote(expected.toString())});</script>"
        val result = PageJson.assignment(html, "netflix.reactContext")!!
        assertEquals(expected.getString("title"), result.getString("title"))
        assertEquals(expected.getString("description"), result.getString("description"))
    }

    @Test fun supportsSingleQuotedJavascriptUnicodeHexAndEscapedApostrophe() {
        val html = """window.netflix.reactContext = JSON.parse('{"caption":"\x5a\u0061żółć","quote":"it\'s"}');"""
        val result = PageJson.assignment(html, "netflix.reactContext")!!
        assertEquals("Zażółć", result.getString("caption"))
        assertEquals("it's", result.getString("quote"))
    }

    @Test fun rejectsMalformedAndExecutableExpressionsWithoutEvaluation() {
        listOf(
            "netflix.reactContext = {\"nested\":{",
            "netflix.reactContext = undefined;",
            "netflix.reactContext = function() { return {}; }();",
            "netflix.reactContext = JSON.parse('unterminated);",
        ).forEach { assertNull(it, PageJson.assignment(it, "netflix.reactContext")) }
    }

    @Test fun distinguishesTheExactAssignmentFromLongerJavascriptIdentifiers() {
        val html = """notnetflix.reactContext = {"wrong":true}; window.netflix.reactContext = {"correct":true};"""
        val result = PageJson.assignment(html, "netflix.reactContext")!!
        assertTrue("A suffix inside another identifier is not the requested assignment", result.optBoolean("correct"))
        assertFalse(result.has("wrong"))
    }

    @Test fun returnsNullForMissingAssignment() {
        assertNull(PageJson.assignment("<html><body>Sign in</body></html>", "netflix.reactContext"))
    }
}
