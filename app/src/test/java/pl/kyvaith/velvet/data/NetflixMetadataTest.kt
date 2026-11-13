package pl.kyvaith.velvet.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetflixMetadataTest {
    private fun show() = JSONObject("""{
      "id":100,"type":"show","title":"Serial","seasons":[
        {"id":201,"seq":1,"title":"Pierwszy sezon","episodes":[
          {"id":301,"seq":1,"title":"Pilot","synopsis":"Opis","runtime":2700,"stills":[{"url":"https://cdn.example/still.jpg"}]},
          {"id":302,"seq":2,"title":"Niedostępny","isPlayable":false}
        ]},
        {"id":202,"seq":2,"title":"Drugi sezon","episodes":[{"id":401,"seq":1,"title":"Powrót","runtime":2800}]}
      ]
    }""")

    @Test fun metadataFallbackPreservesSeasonAndEpisodeOwnership() {
        val metadata = show()
        assertTrue(NetflixMetadata.title(metadata, "100")!!.isSeries)
        assertEquals(listOf("201", "202"), NetflixMetadata.seasons(metadata, "100").map { it.id })
        val episode = NetflixMetadata.episodes(metadata, "100", "202").single()
        assertEquals("401", episode.id)
        assertEquals(2, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
        assertEquals(2800L, episode.durationSeconds)
    }

    @Test fun metadataFallbackRejectsWrongShowAndWrongSeason() {
        val metadata = show()
        assertNull(NetflixMetadata.title(metadata, "999"))
        assertTrue(NetflixMetadata.seasons(metadata, "999").isEmpty())
        assertTrue(NetflixMetadata.episodes(metadata, "999", "201").isEmpty())
        assertTrue(NetflixMetadata.episodes(metadata, "100", "999").isEmpty())
    }

    @Test fun metadataFallbackDoesNotOfferAnExplicitlyUnavailableEpisode() {
        val episode = NetflixMetadata.episodes(show(), "100", "201").single()
        assertEquals("301", episode.id)
        assertEquals("https://cdn.example/still.jpg", episode.artworkUrl)
    }
}
