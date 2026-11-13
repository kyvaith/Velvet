package pl.kyvaith.velvet.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetflixCatalogParserTest {
    @Test fun carouselUsesNetflixIdsContextualArtworkAndDeduplicatesRepeatedEdges() {
        val cache = JSONObject("""{
          "ROOT_QUERY":{"sections":{"edges":[{"node":{"__ref":"Section:1"}}]}},
          "Section:1":{"__typename":"PinotCarouselSection","_id":"row-1","displayString":"Dla Ciebie",
            "entities({\"count\":20})":{"edges":[{"node":{"__ref":"Entity:801"}},{"node":{"__ref":"Entity:801"}},{"node":{"__ref":"Game:7"}}]}},
          "Entity:801":{"displayString":"Film &amp; przyjaciele","unifiedEntity":{"__ref":"Movie:801"},"contextualArtwork":{"artwork":{"url":"https://cdn.example/context.webp"}}},
          "Movie:801":{"__typename":"Movie","videoId":801,"title":"Nazwa bazowa","releaseYear":2024,"runtimeSec":7200,
            "contextualSynopsis":{"text":"Opis filmu"},"bookmark":{"position":120},"contentAdvisory":{"rating":"16+"},
            "isInMyList":true,"storyArt":{"url":"https://cdn.example/hero.webp"}},
          "Game:7":{"__typename":"Game","videoId":7,"title":"To nie jest film"}
        }""")
        val rows = CatalogGraph(cache).rows()
        assertEquals(1, rows.size)
        assertEquals("row-1", rows.single().id)
        val title = rows.single().items.single()
        assertEquals("801", title.id)
        assertEquals("Film & przyjaciele", title.title)
        assertEquals("Opis filmu", title.synopsis)
        assertEquals("https://cdn.example/context.webp", title.artworkUrl)
        assertEquals("https://cdn.example/hero.webp", title.heroUrl)
        assertEquals("2024", title.year)
        assertEquals("16+", title.maturity)
        assertEquals(7200L, title.runtimeSeconds)
        assertEquals(120L, title.resumeSeconds)
        assertTrue(title.isInMyList)
        assertFalse(title.isSeries)
    }

    @Test fun malformedEntitiesAndNonVideoSectionsDoNotAppearAsWatchableTitles() {
        val graph = CatalogGraph(JSONObject())
        assertNull(graph.title(JSONObject("""{"__typename":"Movie","videoId":"not-an-id","title":"Bad"}""")))
        assertNull(graph.title(JSONObject("""{"__typename":"Movie","videoId":42,"title":""}""")))
        assertNull(graph.title(JSONObject("""{"__typename":"Person","videoId":42,"title":"Actor"}""")))
        assertTrue(CatalogGraph(JSONObject("""{"__typename":"PinotCarouselSection","_id":"empty","displayString":"Pusta","entities":{"edges":[]}}""")).rows().isEmpty())
    }

    @Test fun falcorRowsFollowReferencesAndKeepNumericOrder() {
        val cache = JSONObject("""{
          "videos":{
            "8":{"summary":{"${'$'}type":"atom","value":{"id":8,"type":"movie","name":"Pierwszy"}},"title":"Pierwszy","queue":{"inQueue":true}},
            "9":{"summary":{"id":9,"type":"show","name":"Drugi"},"title":"Drugi"}
          },
          "lists":{"row":{"componentSummary":{"id":"saved","displayName":"Moja lista"},
            "10":{"reference":{"${'$'}type":"ref","value":["videos","9"]}},
            "2":{"reference":{"${'$'}type":"ref","value":["videos","8"]}}}}
        }""")
        val row = CatalogGraph(cache).falcorRows().single()
        assertEquals("Moja lista", row.title)
        assertEquals(listOf("8", "9"), row.items.map { it.id })
        assertTrue(row.items.first().isInMyList)
        assertTrue(row.items.last().isSeries)
    }

    @Test fun seasonsAndEpisodesStayAssociatedWithTheRequestedShowAndSeason() {
        val cache = JSONObject("""{
          "Show:100":{"videoId":100,"seasons":{"edges":[{"node":{"__ref":"Season:201"}}]}},
          "Show:999":{"videoId":999,"seasons":{"edges":[{"node":{"videoId":998,"title":"Nie ten sezon"}}]}},
          "Season:201":{"videoId":201,"number":2,"title":"Sezon drugi","episodes({\"count\":50})":{"edges":[{"node":{"__ref":"Episode:301"}}]}},
          "Episode:301":{"videoId":301,"number":3,"title":"Trzeci odcinek","runtimeSec":2700,"contextualSynopsis":{"text":"Opis odcinka"},"artwork":{"url":"https://cdn.example/episode.jpg"}}
        }""")
        val graph = CatalogGraph(cache)
        val season = graph.seasons("100").single()
        assertEquals("201", season.id)
        assertEquals(2, season.number)
        val episode = graph.episodes(season.id).single()
        assertEquals("301", episode.id)
        assertEquals(2, episode.seasonNumber)
        assertEquals(3, episode.episodeNumber)
        assertEquals(2700L, episode.durationSeconds)
        assertEquals("https://cdn.example/episode.jpg", episode.artworkUrl)
        assertTrue(graph.episodes("not-present").isEmpty())
    }

    @Test fun continueWatchingUsesCurrentEpisodeIdentityAndItsOwnBookmark() {
        val cache = JSONObject("""{
          "Show:100":{"__typename":"Show","videoId":100,"title":"Serial","currentEpisode":{"__ref":"Episode:315"},
            "seasons":{"edges":[{"node":{"videoId":201,"episodes":{"edges":[{"node":{"videoId":301}}]}}}]}},
          "Episode:315":{"__typename":"Episode","videoId":315,"title":"Właściwy odcinek","runtimeSec":2700,"bookmark":{"position":825}},
          "Show:200":{"__typename":"Show","videoId":200,"title":"Inny serial","currentEpisode":{"videoId":402,"bookmark":{"position":91}}}
        }""")
        val current = CatalogGraph(cache).currentEpisodes()
        assertEquals("315", current.getValue("100").id)
        assertEquals(825L, current.getValue("100").resumeSeconds)
        assertEquals(2700L, current.getValue("100").runtimeSeconds)
        assertEquals("402", current.getValue("200").id)
        assertEquals(91L, current.getValue("200").resumeSeconds)
        assertNotEquals("301", current.getValue("100").id)
    }

    @Test fun bookmarkWithoutAnEpisodeIdDoesNotInventAnEpisodeForContinueWatching() {
        val cache = JSONObject("""{
          "Show:100":{"__typename":"Show","videoId":100,"title":"Serial","currentEpisode":{"bookmark":{"position":825}}}
        }""")
        assertTrue(CatalogGraph(cache).currentEpisodes().isEmpty())
    }

    @Test fun falcorProfilesResolveNamesAvatarsKidsAndPinProtection() {
        val falcor = JSONObject("""{
          "profilesList":{"1":{"${'$'}type":"ref","value":["profiles","child"]},"0":{"${'$'}type":"ref","value":["profiles","owner"]}},
          "profiles":{
            "owner":{"summary":{"guid":"owner","profileName":"Tom &amp; Jerry","isProfileLockEnabled":true},"avatar":{"images":{"byWidth":{"320":{"url":"https://cdn.example/owner.png"}}}}},
            "child":{"summary":{"guid":"child","profileName":"Dziecko &#x1F600;","isKids":true}}
          }
        }""")
        val profiles = NetflixCatalogParser.profiles(falcor, JSONObject())
        assertEquals(listOf("owner", "child"), profiles.map { it.id })
        assertEquals("Tom & Jerry", profiles[0].name)
        assertEquals("https://cdn.example/owner.png", profiles[0].avatarUrl)
        assertTrue(profiles[0].isLocked)
        assertTrue(profiles[1].isKids)
        assertEquals("Dziecko 😀", profiles[1].name)
    }

    @Test fun graphqlProfilesAreAFallbackWhenFalcorProfilesAreMissing() {
        val graph = JSONObject("""{
          "ROOT_QUERY":{"viewer":{"profile":{"__ref":"Profile:p"}}},
          "Profile:p":{"__typename":"Profile","profileGuid":"p","name":"Profil","isLocked":true,"avatar":{"url":"https://cdn.example/p.png"}}
        }""")
        val profile = NetflixCatalogParser.profiles(JSONObject(), graph).single()
        assertEquals("p", profile.id)
        assertTrue(profile.isLocked)
    }

    @Test fun bootstrapRejectsUnauthenticatedAndInactiveAccounts() {
        val previous = SessionData("NetflixId=test; SecureNetflixId=test", "test-agent")
        val missing = assertThrows(NetflixApiException::class.java) { NetflixCatalogParser.captured(JSONObject(), previous) }
        assertEquals(401, missing.statusCode)
        val data = capturedMember()
        data.getJSONObject("reactContext").getJSONObject("models").getJSONObject("userInfo").getJSONObject("data")
            .put("membershipStatus", "FORMER_MEMBER")
        assertThrows(NetflixApiException::class.java) { NetflixCatalogParser.captured(data, previous) }
    }

    @Test fun bootstrapImportsTheActualSessionAndRejectsUntrustedApiEndpoint() {
        val previous = SessionData("NetflixId=test; SecureNetflixId=test", "test-agent", profileGuid = "old")
        val data = capturedMember()
        val imported = NetflixCatalogParser.captured(data, previous)
        assertEquals("active-profile", imported.session.profileGuid)
        assertEquals("test-auth", imported.session.authUrl)
        assertEquals(previous.cookieHeader, imported.session.cookieHeader)
        assertFalse(imported.session.toString().contains("test-auth"))
        data.getJSONObject("reactContext").getJSONObject("models")
            .put("services", JSONObject("""{"data":{"memberapi":"https://www.netflix.com.evil.example/api"}}"""))
        assertThrows(NetflixApiException::class.java) { NetflixCatalogParser.captured(data, previous) }
    }

    @Test fun pagedFalcorRowsKeepItemSummaryNextToReference() {
        val cache = JSONObject("""{
          "lists":{"row":{"componentSummary":{"value":{"id":"r","displayName":"Polecane"}},
            "page":{"0":{"0":{"itemSummary":{"value":{"title":"Film z listy","boxArt":{"url":"https://cdn.example/poster.jpg"}}},
              "reference":{"${'$'}type":"ref","value":["videos","801"]}}}}}},
          "videos":{"801":{"summary":{"value":{"id":801,"type":"movie"}}}}
        }""")
        val row = CatalogGraph(cache).falcorRows().single()
        assertEquals("Polecane", row.title)
        assertEquals("801", row.items.single().id)
        assertEquals("Film z listy", row.items.single().title)
        assertEquals("https://cdn.example/poster.jpg", row.items.single().artworkUrl)
    }

    @Test fun mixedDirectAndPagedFalcorRowsDeduplicateTheSameVideo() {
        val cache = JSONObject("""{
          "lists":{"row":{"componentSummary":{"displayName":"Lista"},
            "0":{"reference":{"${'$'}type":"ref","value":["videos","8"]}},
            "page":{"0":{"0":{"reference":{"${'$'}type":"ref","value":["videos","8"]}},
              "1":{"reference":{"${'$'}type":"ref","value":["videos","9"]}}}}}},
          "videos":{"8":{"summary":{"id":8,"type":"movie","name":"A"}},"9":{"summary":{"id":9,"type":"show","name":"B"}}}
        }""")
        assertEquals(listOf("8", "9"), CatalogGraph(cache).falcorRows().single().items.map { it.id })
    }

    @Test fun selectorSeasonsAcceptTheQueryScopedSingletonWithoutParentVideoId() {
        val data = JSONObject("""{"videos":[{"__typename":"Show","seasons":{"edges":[
          {"node":{"videoId":201,"title":"Sezon 1"}}, {"node":{"videoId":202,"title":"Sezon 2"}}
        ]}}]}""")
        assertEquals(listOf("201", "202"), CatalogGraph(data).seasons("100").map { it.id })
    }

    @Test fun selectorEpisodesAcceptTheQueryScopedSingletonWithoutParentVideoId() {
        val data = JSONObject("""{"videos":[{"__typename":"Season","number":2,"episodes":{"edges":[
          {"node":{"videoId":301,"number":4,"title":"Odcinek","isPlayable":true}},
          {"node":{"videoId":302,"title":"Niedostępny","isPlayable":false}}
        ]}}]}""")
        val episode = CatalogGraph(data).episodes("201").single()
        assertEquals("301", episode.id)
        assertEquals(2, episode.seasonNumber)
        assertEquals(4, episode.episodeNumber)
    }

    @Test fun selectorsRejectExplicitlyDifferentOrAmbiguousParents() {
        val wrongShow = JSONObject("""{"videos":[{"videoId":999,"seasons":{"edges":[{"node":{"videoId":201}}]}}]}""")
        val wrongSeason = JSONObject("""{"videos":[{"videoId":999,"episodes":{"edges":[{"node":{"videoId":301}}]}}]}""")
        val ambiguous = JSONObject("""{"videos":[{"episodes":{"edges":[{"node":{"videoId":301}}]}},{"episodes":{"edges":[{"node":{"videoId":401}}]}}]}""")
        val wrongKind = JSONObject("""{"videos":[{"__typename":"Movie","episodes":{"edges":[{"node":{"videoId":301}}]}}]}""")
        assertTrue(CatalogGraph(wrongShow).seasons("100").isEmpty())
        assertTrue(CatalogGraph(wrongSeason).episodes("201").isEmpty())
        assertTrue(CatalogGraph(ambiguous).episodes("201").isEmpty())
        assertTrue(CatalogGraph(wrongKind).episodes("201").isEmpty())
    }

    @Test fun falcorCurrentEpisodeUsesTheEpisodeIdAndBookmark() {
        val data = JSONObject("""{"videos":{
          "100":{"summary":{"value":{"id":100,"type":"show","name":"Serial"}},"current":{"${'$'}type":"ref","value":["episodes","315"]}}
        },"episodes":{"315":{"summary":{"value":{"id":315,"type":"episode","name":"Odcinek"}},"bookmarkPosition":{"value":825},"runtime":{"value":2700}}}}""")
        val current = CatalogGraph(data).currentEpisodes().getValue("100")
        assertEquals("315", current.id)
        assertEquals(825L, current.resumeSeconds)
    }

    @Test fun pageMergesInitialApolloDataWithLaterHydrationPatches() {
        val react = capturedMember().getJSONObject("reactContext")
        react.getJSONObject("models").put("graphql", JSONObject("""{"data":{
          "Movie:801":{"__typename":"Movie","videoId":801,"title":"Film"}
        }}"""))
        val section = """{"data":{"Section:1":{"__typename":"PinotCarouselSection","displayString":"Polecane","entities":{"edges":[{"node":{"unifiedEntity":{"__ref":"Movie:801"}}}]}}}}"""
        val html = "<script>netflix.reactContext=$react;</script>" +
            "<script>netflix.reactContext.models.graphql={\"data\":{}};</script>" +
            "<script>netflix.reactContext.models.graphql=JSON.parse(${JSONObject.quote(section)});</script>"
        val result = NetflixCatalogParser.page(html, SessionData("NetflixId=test; SecureNetflixId=test", "test-agent"))
        assertEquals("801", result.rows.single().items.single().id)
    }

    @Test fun profileRecoveryOnlyRetainsAuthenticatedNetflixSwitchLinks() {
        val react = capturedMember().getJSONObject("reactContext")
        val html = "<script>netflix.reactContext=$react;</script>" +
            "<a href=\"https://www.netflix.com/SwitchProfile?tkn=active-profile&amp;next=%2Fbrowse\">Profil</a>" +
            "<a href=\"https://evil.example/SwitchProfile?tkn=active-profile\">Obcy adres</a>"
        val result = NetflixCatalogParser.page(html, SessionData("NetflixId=test; SecureNetflixId=test", "test-agent"))
        assertEquals("https://www.netflix.com/SwitchProfile?tkn=active-profile&next=%2Fbrowse", result.profileLinks["active-profile"])
    }

    private fun capturedMember() = JSONObject("""{
      "reactContext":{"models":{
        "userInfo":{"data":{"membershipStatus":"CURRENT_MEMBER","authURL":"test-auth","userGuid":"active-profile"}},
        "services":{"data":{"memberapi":"/nq/website/memberapi/release"}}
      }},"falcorCache":{}
    }""")
}
