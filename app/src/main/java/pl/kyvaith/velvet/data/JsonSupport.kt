// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.entries(): List<Pair<String, Any>> = keys().asSequence().mapNotNull { key ->
    opt(key)?.takeUnless { it === JSONObject.NULL }?.let { key to it }
}.toList()
internal fun JSONArray.values(): List<Any> = (0 until length()).mapNotNull { opt(it)?.takeUnless { v -> v === JSONObject.NULL } }
internal fun JSONObject.at(vararg path: String): Any? {
    var value: Any? = this
    for (part in path) value = when (val current = value) {
        is JSONObject -> current.opt(part)
        is JSONArray -> part.toIntOrNull()?.let(current::opt)
        else -> null
    }
    return value?.takeUnless { it === JSONObject.NULL }
}
internal fun Any?.text(): String = when (this) {
    null, JSONObject.NULL -> ""
    is String -> this
    is Number, is Boolean -> toString()
    else -> ""
}
internal fun Any?.number(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: 0
    else -> 0
}
internal fun Any?.obj(): JSONObject = this as? JSONObject ?: JSONObject()
internal fun Any?.array(): List<Any> = (this as? JSONArray)?.values() ?: emptyList()
internal fun json(vararg entries: Pair<String, Any?>): JSONObject = JSONObject().apply {
    entries.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
internal fun arr(vararg items: Any?): JSONArray = JSONArray().apply { items.forEach { put(it ?: JSONObject.NULL) } }

/** Parses only JSON and quoted JavaScript string literals. Never evaluates page code. */
object PageJson {
    fun assignment(html: String, name: String): JSONObject? {
        val pattern = Regex("(?<![A-Za-z0-9_$.])(?:window\\.)?" + Regex.escape(name) + "\\s*=\\s*")
        val match = pattern.find(html) ?: return null
        return runCatching { valueAt(html, match.range.last + 1) }.getOrNull()
    }

    /** Some Netflix pages hydrate GraphQL in more than one script tag. Keep every valid patch. */
    fun assignments(html: String, name: String): List<JSONObject> {
        val pattern = Regex("(?<![A-Za-z0-9_$.])(?:window\\.)?" + Regex.escape(name) + "\\s*=\\s*")
        return pattern.findAll(html).mapNotNull { match ->
            runCatching { valueAt(html, match.range.last + 1) }.getOrNull()
        }.toList()
    }

    private fun valueAt(source: String, offset: Int): JSONObject {
        var cursor = offset
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        if (source.startsWith("JSON.parse(", cursor)) {
            cursor += "JSON.parse(".length
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            return JSONObject(quoted(source, cursor).first)
        }
        require(cursor < source.length && source[cursor] == '{') { "Expected JSON object" }
        var depth = 0
        val normalized = StringBuilder()
        while (cursor < source.length) {
            val char = source[cursor]
            if(char == '"' || char == '\'') {
                val (decoded,next)=quoted(source,cursor)
                normalized.append(JSONObject.quote(decoded))
                cursor=next
                continue
            }
            cursor++
            normalized.append(char)
            when (char) {
                '{' -> depth++
                '}' -> if (--depth == 0) return JSONObject(normalized.toString())
            }
        }
        error("Unterminated JSON object")
    }

    internal fun quoted(source: String, start: Int): Pair<String, Int> {
        val quote = source.getOrNull(start)
        require(quote == '\'' || quote == '"')
        val out = StringBuilder()
        var i = start + 1
        while (i < source.length) {
            val ch = source[i++]
            if (ch == quote) return out.toString() to i
            if (ch != '\\') { out.append(ch); continue }
            require(i < source.length)
            when (val escaped = source[i++]) {
                '\\', '\'', '"', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000B')
                '\n' -> Unit
                '\r' -> if (source.getOrNull(i) == '\n') i++
                'x', 'u' -> {
                    val count = if (escaped == 'x') 2 else 4
                    require(i + count <= source.length)
                    out.append(source.substring(i, i + count).toInt(16).toChar())
                    i += count
                }
                else -> out.append(escaped)
            }
        }
        error("Unterminated JavaScript string")
    }
}

/** Resolves Apollo cache references and Falcor atoms/refs, with bounded cycle handling. */
class NetflixGraph(private val cache: JSONObject) {
    fun resolve(value: Any?, hops: Int = 0): Any? {
        if (hops > 24 || value == null || value === JSONObject.NULL) return null
        if (value !is JSONObject) return value
        value.optString("__ref").takeIf { it.isNotBlank() }?.let { return resolve(cache.opt(it), hops + 1) }
        return when (value.optString("\$type")) {
            "atom" -> resolve(value.opt("value"), hops + 1)
            "ref" -> {
                var result: Any? = cache
                value.optJSONArray("value")?.values()?.forEach { part ->
                    result = when (val current = resolve(result, hops + 1)) {
                        is JSONObject -> current.opt(part.text())
                        is JSONArray -> part.text().toIntOrNull()?.let(current::opt)
                        else -> null
                    }
                }
                resolve(result, hops + 1)
            }
            "error" -> null
            else -> {
                // Netflix also returns materialized Falcor atoms as {"value": ...}, without $type.
                // Do not unwrap ordinary domain objects that merely have a field named value.
                val keys = value.keys().asSequence().toList()
                if (value.has("value") && keys.all { it in setOf("value", "\$expires", "\$size", "\$timestamp") })
                    resolve(value.opt("value"), hops + 1)
                else value
            }
        }
    }

    fun field(value: Any?, name: String): Any? {
        val node = resolve(value) as? JSONObject ?: return null
        val direct = node.opt(name)?.takeUnless { it === JSONObject.NULL }
        return resolve(direct ?: node.entries().firstOrNull { (key, _) -> key.startsWith("$name(") }?.second)
    }

    fun edges(connection: Any?): List<Any> {
        val items = field(connection, "edges") ?: field(connection, "nodes") ?: resolve(connection)
        return when (items) {
            is JSONArray -> items.values()
            is JSONObject -> items.entries().filter { it.first.toIntOrNull() != null }.sortedBy { it.first.toInt() }.map { it.second }
            else -> emptyList()
        }.mapNotNull(::resolve).map { field(it, "node") ?: it }
    }

    fun allNodes(): List<JSONObject> {
        val nodes = ArrayList<JSONObject>()
        val seen = HashSet<JSONObject>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 32 || nodes.size > 20000) return
            when (val resolved = resolve(value)) {
                is JSONObject -> if (seen.add(resolved)) {
                    nodes += resolved
                    resolved.entries().forEach { walk(it.second, depth + 1) }
                }
                is JSONArray -> resolved.values().forEach { walk(it, depth + 1) }
            }
        }
        walk(cache, 0)
        return nodes
    }

    /** Counts missing Apollo targets without exposing cache keys, IDs or their values. */
    fun unresolvedReferenceCount(): Int {
        var missing = 0
        var visited = 0
        val seen = HashSet<JSONObject>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 32 || visited >= 20000) return
            when (value) {
                is JSONObject -> if (seen.add(value)) {
                    visited++
                    val ref = value.optString("__ref")
                    if (ref.isNotBlank() && (!cache.has(ref) || cache.isNull(ref))) missing++
                    value.entries().forEach { walk(it.second, depth+1) }
                }
                is JSONArray -> value.values().forEach { walk(it, depth+1) }
            }
        }
        walk(cache, 0)
        return missing
    }

    fun image(value: Any?, depth: Int = 0): String? {
        if (depth > 8) return null
        return when (val node = resolve(value)) {
            is JSONObject -> {
                val url = field(node, "url").text()
                if (url.startsWith("https://")) url
                else node.entries().asSequence().mapNotNull { image(it.second, depth + 1) }.firstOrNull()
            }
            is JSONArray -> node.values().asSequence().mapNotNull { image(it, depth + 1) }.firstOrNull()
            else -> null
        }
    }
}
