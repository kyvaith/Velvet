package pl.kyvaith.velvet.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetflixGraphTest {
    @Test fun followsApolloReferencesAndArgumentQualifiedFields() {
        val cache = JSONObject("""{
          "ROOT_QUERY":{"current":{"__ref":"Movie:801"}},
          "Movie:801":{"title({\"locale\":\"pl\"})":"Polski tytuł","titleArtwork":{"url":"https://images.example/art.jpg"}}
        }""")
        val graph = NetflixGraph(cache)
        assertEquals("Polski tytuł", graph.field(graph.field(cache.getJSONObject("ROOT_QUERY"), "current"), "title"))
        assertNull(graph.field(cache.getJSONObject("Movie:801"), "tit"))
    }

    @Test(timeout = 1000) fun cyclicAndMissingReferencesTerminateWithoutReturningAnEntity() {
        val cache = JSONObject("""{"a":{"__ref":"b"},"b":{"__ref":"a"},"missing":{"__ref":"absent"}}""")
        val graph = NetflixGraph(cache)
        assertNull(graph.resolve(cache.get("a")))
        assertNull(graph.resolve(cache.get("missing")))
        assertTrue(graph.allNodes().size <= 1)
    }

    @Test fun falcorReferencesCanResolveAtomsContainingData() {
        val cache = JSONObject("""{"videos":{"801":{"title":{"${'$'}type":"atom","value":"Film"}}},"selected":{"${'$'}type":"ref","value":["videos",801]}}""")
        assertEquals("Film", NetflixGraph(cache).field(cache.get("selected"), "title"))
    }

    @Test fun numericFalcorReferenceSegmentsTraverseJsonArrays() {
        val cache = JSONObject("""{"profiles":[{"name":"Tomek"}],"selected":{"${'$'}type":"ref","value":["profiles",0]}}""")
        assertEquals("Tomek", NetflixGraph(cache).field(cache.get("selected"), "name"))
    }

    @Test fun connectionsResolveNodesAndPreserveServerOrder() {
        val cache = JSONObject("""{"one":{"videoId":1},"two":{"videoId":2},"connection":{"edges":[{"node":{"__ref":"two"}},{"node":{"__ref":"one"}}]}}""")
        val graph = NetflixGraph(cache)
        assertEquals(listOf(2L, 1L), graph.edges(cache.get("connection")).map { graph.field(it, "videoId").number() })
    }

    @Test fun numericFalcorEdgesSortNumericallyAndIgnoreMetadata() {
        val graph = NetflixGraph(JSONObject())
        val connection = JSONObject("""{"10":{"id":10},"2":{"id":2},"length":11,"summary":{}}""")
        assertEquals(listOf(2L, 10L), graph.edges(connection).map { graph.field(it, "id").number() })
    }

    @Test fun errorAtomsAndNullsDoNotBecomeCatalogEntries() {
        val graph = NetflixGraph(JSONObject())
        assertNull(graph.resolve(JSONObject("""{"${'$'}type":"error","value":{"message":"Denied"}}""")))
        assertNull(graph.resolve(JSONObject.NULL))
    }

    @Test fun imageSearchRejectsCleartextUrlsAndResolvesNestedArtwork() {
        val cache = JSONObject("""{"image":{"url":"https://cdn.example/poster.webp"}}""")
        val candidate = JSONObject().put("insecure", JSONObject().put("url", "http://cdn.example/old.jpg"))
            .put("images", JSONArray().put(JSONObject().put("__ref", "image")))
        assertEquals("https://cdn.example/poster.webp", NetflixGraph(cache).image(candidate))
    }
    @Test fun materializedValueAtomsUnwrapWithoutMistakingDomainObjectsForAtoms() {
        val graph = NetflixGraph(JSONObject())
        assertEquals("Film", graph.resolve(JSONObject("""{"value":"Film"}""")))
        val advisory = JSONObject("""{"value":"16+","rating":"TV-MA"}""")
        assertEquals("16+", graph.field(advisory, "value"))
        assertEquals("TV-MA", graph.field(advisory, "rating"))
    }

    @Test fun connectionsWithNodesInsteadOfEdgesRemainOrdered() {
        val connection = JSONObject("""{"nodes":[{"videoId":3},{"videoId":1}]}""")
        val graph = NetflixGraph(JSONObject())
        assertEquals(listOf(3L, 1L), graph.edges(connection).map { graph.field(it, "videoId").number() })
    }
    @Test fun missingApolloReferencesAreCountedWithoutTheirValues() {
        val cache = JSONObject("""{"known":{"title":"Film"},"root":[{"__ref":"known"},{"__ref":"missing"},{"__ref":"absent"}]}""")
        assertEquals(2, NetflixGraph(cache).unresolvedReferenceCount())
    }
}
