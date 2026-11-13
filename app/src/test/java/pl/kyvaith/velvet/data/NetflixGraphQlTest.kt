package pl.kyvaith.velvet.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetflixGraphQlTest {
    @Test fun http200WithGraphqlErrorsIsNotAPlaybackOrCatalogSuccess() {
        val payload = JSONObject("""{"errors":[{"message":"Private token=DO_NOT_COPY","extensions":{"code":"UNAUTHENTICATED"}}]}""")
        val error = assertThrows(NetflixApiException::class.java) { NetflixHttp.requireGraphQlData(payload) }
        assertEquals("GraphQL", error.stage)
        assertFalse(error.message.orEmpty().contains("DO_NOT_COPY"))
    }

    @Test fun partialDataDoesNotConcealServiceErrors() {
        val payload = JSONObject("""{"data":{"viewer":null},"errors":[{"message":"Session expired"}]}""")
        assertThrows(NetflixApiException::class.java) { NetflixHttp.requireGraphQlData(payload) }
    }

    @Test fun validDataWithNoErrorsIsReturned() {
        val payload = JSONObject("""{"data":{"viewer":{"id":"profile-1"}},"errors":[]}""")
        assertEquals("profile-1", NetflixHttp.requireGraphQlData(payload).getJSONObject("viewer").getString("id"))
    }

    @Test fun missingNullOrNonObjectDataIsRejected() {
        listOf("{}", "{\"data\":null}", "{\"data\":[]}").forEach {
            assertThrows(NetflixApiException::class.java) { NetflixHttp.requireGraphQlData(JSONObject(it)) }
        }
    }
}
