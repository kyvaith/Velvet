// SPDX-License-Identifier: MIT
// Persisted-query IDs and protocol mappings: see THIRD_PARTY_NOTICES.md.
package pl.kyvaith.velvet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.model.*
import pl.kyvaith.velvet.diagnostics.AppLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Live Netflix data only. No demo catalog, alternative providers or embedded account. */
class NetflixRepository(private val sessions: SessionStore) {
    private val http = NetflixHttp(sessions)
    private val sessionMutex = Mutex()
    private val titles = ConcurrentHashMap<String, NfTitle>()
    private val currentEpisodes = ConcurrentHashMap<String, NfTitle>()
    private val metadata = ConcurrentHashMap<String, JSONObject>()
    private val seasonShows = ConcurrentHashMap<String, String>()
    @Volatile private var snapshot: BrowseSnapshot? = null
    @Volatile private var hydrated: Pair<Long, BrowseSnapshot>? = null

    suspend fun importWebSession(cookieHeader: String, userAgent: String, captured: JSONObject): BrowseSnapshot = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            if (!NetflixOrigins.hasAuthCookies(cookieHeader)) throw NetflixApiException("sesja", 401,
                "Dokończ logowanie na stronie Netflixa. Brakuje ciasteczek aktywnej sesji.")
            val session = SessionData(cookieHeader=cookieHeader, userAgent=userAgent)
            val parsed = NetflixCatalogParser.captured(captured, session)
            clearMemory()
            sessions.beginSession(parsed.session)
            hydrated = sessions.generation to parsed
            // Verify the same authenticated session from the native HTTP client before exposing captured rows.
            fetchBrowse()
        }
    }

    suspend fun bootstrap(): BrowseSnapshot = withContext(Dispatchers.IO) { sessionMutex.withLock { fetchBrowse() } }

    private fun prepareBrowserContext() {
        val generation = sessions.generation
        val previous = sessions.read(generation) ?: throw NetflixApiException("sesja", 401, "Zaloguj się na konto Netflix.")
        if (NetflixBrowserContext.migratedSession(previous) == null) return
        // Both generation verification and the identity transition are atomic with logout.
        sessions.transition(generation) { current -> NetflixBrowserContext.migratedSession(current) ?: current }
            ?: throw NetflixApiException("sesja", 401, "Zaloguj się na konto Netflix.")
        clearMemory()
        AppLog.info("catalog", "browser_context_migrated", mapOf("source" to "desktop_site", "hasSession" to true))
    }

    private fun fetchBrowse(expectedProfile: String? = null): BrowseSnapshot {
        prepareBrowserContext()
        val sessionGeneration=sessions.generation
        val previous = sessions.read(sessionGeneration) ?: throw NetflixApiException("sesja", 401, "Zaloguj się na konto Netflix.")
        AppLog.info("catalog", "browse_started", mapOf("source" to NetflixBrowserContext.contextTag(previous.userAgent)))
        val html = http.get("https://www.netflix.com/browse", sessionGeneration)
        var parsed = NetflixCatalogParser.page(html, sessions.read(sessionGeneration) ?: previous)
        sessions.save(parsed.session,sessionGeneration)
        // Authenticated HTML may still be the profile gate. Use only the site's supplied link
        // for the already selected profile, and never submit a PIN or switch a different profile.
        val desiredProfile = expectedProfile ?: previous.profileGuid
        val activeLink = parsed.profileLinks[desiredProfile]
        val locked = parsed.profiles.firstOrNull { it.id == desiredProfile }?.isLocked == true
        if (parsed.rows.isEmpty() && activeLink != null && !locked) {
            AppLog.info("catalog", "profile_gate_recovery", mapOf("attempt" to 1, "hasProfile" to true))
            val activeHtml = http.get(activeLink, sessionGeneration)
            val active = NetflixCatalogParser.page(activeHtml, sessions.read(sessionGeneration) ?: parsed.session)
            if (active.session.profileGuid != desiredProfile)
                throw NetflixApiException("profil", message="Netflix nie potwierdził wybranego profilu. Wybierz go na stronie logowania.")
            parsed = active
            sessions.save(parsed.session, sessionGeneration)
            AppLog.info("catalog", "profile_gate_result", mapOf("rowCount" to parsed.rows.size, "recovered" to parsed.rows.isNotEmpty()))
        }
        if (parsed.profiles.isEmpty()) {
            val profiles = try {
                val data = path(listOf(
                    arr("profilesList", json("from" to 0,"to" to 5), "summary"),
                    arr("profilesList", json("from" to 0,"to" to 5), "avatar", "images", "byWidth", 320),
                ))
                NetflixCatalogParser.profiles(data, parsed.graphql)
            } catch (ex: NetflixApiException) {
                if (ex.statusCode == 401 || ex.statusCode == 403) throw ex
                emptyList()
            }
            AppLog.info("catalog", "profiles_fallback_result", mapOf("profileCount" to profiles.size, "recovered" to profiles.isNotEmpty()))
            val effective = profiles.ifEmpty {
                if (parsed.session.profileGuid.isNotBlank()) listOf(NfProfile(parsed.session.profileGuid,"Aktywny profil")) else emptyList()
            }
            parsed = BrowseSnapshot(parsed.session, effective, parsed.rows, parsed.graphql, parsed.falcor, parsed.profileLinks, parsed.locoRootIds)
        }
        sessions.requireGeneration(sessionGeneration)
        val captured = hydrated?.takeIf { it.first == sessionGeneration }?.second
        if (parsed.rows.isEmpty() && captured != null && captured.rows.isNotEmpty() && captured.session.profileGuid == parsed.session.profileGuid) {
            AppLog.info("catalog", "hydrated_cache_used", mapOf("rowCount" to captured.rows.size, "recovered" to true))
            parsed = BrowseSnapshot(parsed.session, parsed.profiles, captured.rows, captured.graphql, captured.falcor,
                parsed.profileLinks, parsed.locoRootIds)
        }
        snapshot = parsed
        cacheBrowse(parsed, sessionGeneration)
        return parsed
    }

    suspend fun home(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val (browse, operationGeneration) = sessionMutex.withLock {
            prepareBrowserContext()
            val generation = sessions.generation
            fetchBrowse() to generation
        }
        var rows = browse.rows
        for (root in browse.locoRootIds) {
            if (rows.isNotEmpty()) break
            rows = locoRows(root, operationGeneration)
        }
        if (rows.isEmpty()) {
            AppLog.warn("catalog", "home_empty", mapOf("rowCount" to 0, "hasApollo" to (browse.graphql.length() > 0), "hasFalcor" to (browse.falcor.length() > 0)))
            throw NetflixApiException("katalog", message="Sesja jest aktywna, ale nie udało się odczytać rzędów katalogu. Odśwież ekran; jeśli błąd wróci, wyeksportuj log sesji z ustawień.")
        }
        sessions.requireGeneration(operationGeneration)
        AppLog.info("catalog", "home_complete", mapOf("rowCount" to rows.size, "titleCount" to rows.sumOf { it.items.size }))
        rows.map { row -> row.copy(items=row.items.map { remember(it,operationGeneration) }) }
    }

    suspend fun selectProfile(id: String): BrowseSnapshot = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val before = snapshot ?: fetchBrowse()
            val profile = before.profiles.firstOrNull { it.id == id }
                ?: throw NetflixApiException("profil", message="Ten profil nie jest dostępny w bieżącej sesji.")
            if (id == sessions.read()?.profileGuid) return@withLock before
            if (profile.isLocked) throw NetflixApiException("profil", message="Ten profil wymaga PIN-u. Otwórz logowanie i wybierz go na stronie Netflixa.")
            sessions.invalidateInFlight()
            val actualLink = before.profileLinks[id]
                ?: "https://www.netflix.com/SwitchProfile".toHttpUrl().newBuilder().addQueryParameter("tkn",id).build().toString()
            http.get(actualLink)
            val after = fetchBrowse(id)
            if (after.session.profileGuid != id) throw NetflixApiException("profil", message="Netflix nie potwierdził zmiany profilu. Wybierz profil na stronie logowania.")
            titles.clear()
            currentEpisodes.clear()
            metadata.clear()
            seasonShows.clear()
            val currentGeneration=sessions.generation
            CatalogGraph(after.graphql).currentEpisodes().forEach { (id,title) -> currentEpisodes[cacheKey(id,currentGeneration)]=title }
            after.rows.flatMap { it.items }.forEach { titles[cacheKey(it.id,currentGeneration)]=it }
            after
        }
    }

    suspend fun search(query: String): List<NfTitle> = withContext(Dispatchers.IO) {
        val operationGeneration=sessions.generation
        if (query.trim().isBlank()) return@withContext emptyList()
        val data = graphql("SearchPageQueryResults", searchVariables(query.trim()), SEARCH_ID)
        val result = CatalogGraph(data).rows().flatMap { it.items }.ifEmpty { CatalogGraph(data).titles() }
        result.distinctBy { it.id }.map { remember(it,operationGeneration) }
    }

    suspend fun details(id: String): TitleDetails = withContext(Dispatchers.IO) {
        val operationGeneration=sessions.generation
        requireVideo(id)
        val data = fallback("details_graphql") { graphql("DetailModal", json(
            "artworkContext" to JSONObject(), "checkLinearChannel" to true,
            "fetchPromoVideoOverride" to false,"hasPromoVideoOverride" to false,"isLiveEpisodic" to false,
            "opaqueImageFormat" to "WEBP","promoVideoId" to 0,"textEvidenceUiContext" to "ODP",
            "transparentImageFormat" to "WEBP","unifiedEntityId" to "Video:$id","videoId" to id.toLong(),
            "videoMerchContext" to "BROWSE","videoMerchEnabled" to false), DETAIL_ID, operationGeneration) }
        val candidate = data?.let { CatalogGraph(it).titles().firstOrNull { title -> title.id == id } }
        data?.let { CatalogGraph(it).currentEpisodes() }?.forEach { (showId,title) -> currentEpisodes[cacheKey(showId,operationGeneration)]=title }
        val cached = titles[cacheKey(id,operationGeneration)]
        var title = candidate?.let { merge(it, cached) } ?: cached
            ?: metadataTitle(id, operationGeneration)
            ?: throw NetflixApiException("szczegóły", message="Netflix nie zwrócił danych tego tytułu.")
        title = remember(title,operationGeneration)
        if (!title.isSeries) return@withContext TitleDetails(title)
        val seasons = seasons(id, operationGeneration)
        val first = seasons.firstOrNull()
        val episodes = first?.let { loadEpisodes(it.id, operationGeneration) } ?: emptyList()
        AppLog.info("catalog", "details_complete", mapOf("isSeries" to true, "seasonCount" to seasons.size, "episodeCount" to episodes.size,
            "hasCurrentEpisode" to currentEpisodes.containsKey(cacheKey(id,operationGeneration))))
        TitleDetails(title,seasons,episodes,first?.id)
    }

    suspend fun episodes(seasonId: String): List<NfEpisode> = withContext(Dispatchers.IO) {
        val generation = sessions.generation
        val result = loadEpisodes(seasonId, generation)
        sessions.requireGeneration(generation)
        if (result.isEmpty()) throw NetflixApiException("odcinki", message="Netflix nie zwrócił dostępnych odcinków tego sezonu. Spróbuj innego sezonu i wyeksportuj log sesji, jeśli problem się powtarza.")
        result
    }

    private fun loadEpisodes(seasonId: String, generation: Long): List<NfEpisode> {
        requireVideo(seasonId)
        val data = fallback("episodes_graphql") { graphql("PreviewModalEpisodeSelectorSeasonEpisodes",json("seasonId" to seasonId.toLong(),"count" to 50,
            "opaqueImageFormat" to "JPG","artworkContext" to JSONObject()),EPISODES_ID, generation) }
        var result = data?.let { CatalogGraph(it).episodes(seasonId) }.orEmpty()
        var source = "graphql"
        if (result.isEmpty()) {
            val showId = seasonShows[cacheKey(seasonId, generation)]
            if (showId != null) {
                val video = fallback("episodes_metadata") { metadataVideo(showId, generation) }
                result = video?.let { NetflixMetadata.episodes(it, showId, seasonId) }.orEmpty()
                source = "metadata"
            }
        }
        sessions.requireGeneration(generation)
        AppLog.info("catalog", "episodes_complete", mapOf("source" to source, "episodeCount" to result.size))
        return result
    }

    private fun seasons(showId: String, generation: Long): List<NfSeason> {
        val data = fallback("seasons_graphql") { graphql("PreviewModalEpisodeSelector",json("showId" to showId.toLong(),"seasonCount" to 50),SEASONS_ID, generation) }
        var result = data?.let { CatalogGraph(it).seasons(showId) }.orEmpty()
        var source = "graphql"
        if (result.isEmpty()) {
            result = fallback("seasons_metadata") { metadataVideo(showId, generation) }?.let { NetflixMetadata.seasons(it, showId) }.orEmpty()
            source = "metadata"
        }
        sessions.requireGeneration(generation)
        result.forEach { seasonShows[cacheKey(it.id,generation)] = showId }
        AppLog.info("catalog", "seasons_complete", mapOf("source" to source, "seasonCount" to result.size))
        return result
    }

    suspend fun myList(): List<NfTitle> = withContext(Dispatchers.IO) {
        val operationGeneration=sessions.generation
        val fields = arr("availability","episodeCount","inRemindMeList","itemSummary","queue","summary","title","synopsis","releaseYear","runtime")
        val data = path(listOf(arr("mylist",arr("id","listId","name","requestId","trackIds")),
            arr("mylist",json("from" to 0,"to" to 48),fields)))
        val graph = NetflixGraph(data)
        val list = graph.field(data,"mylist")
            ?: throw NetflixApiException("moja lista",message="Netflix nie zwrócił Mojej listy.")
        val parser = CatalogGraph(data)
        graph.edges(list).mapNotNull(parser::title)
            .distinctBy { it.id }.map { remember(merge(it,titles[cacheKey(it.id,operationGeneration)]).copy(isInMyList=true),operationGeneration) }
    }

    suspend fun setInMyList(id: String, add: Boolean, expectedProfile: String): NfTitle? = withContext(Dispatchers.IO) { sessionMutex.withLock {
        val operationGeneration=sessions.generation
        val operationSession=sessions.read(operationGeneration) ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
        if(operationSession.profileGuid != expectedProfile) throw NetflixApiException("profil",message="Profil został zmieniony. Ponów operację Mojej listy.")
        requireVideo(id)
        val data = path(listOf(arr("videos",id.toLong(),arr("trackIds","queue","summary","title"))),operationGeneration)
        val graph = NetflixGraph(data)
        val video = graph.field(graph.field(data,"videos"),id)
        val track = graph.field(video,"trackIds").obj()
        val trackId = track.optLong("trackId_jaw",0).takeIf { it > 0 }
            ?: track.optLong("trackId",0).takeIf { it > 0 }
            ?: throw NetflixApiException("moja lista",message="Netflix nie udostępnił identyfikatora operacji Mojej listy.")
        val body = json("lolomoId" to "unknown","operation" to if(add) "add" else "remove",
            "videoId" to id.toLong(),"trackId" to trackId,"skipRootInvalidation" to true,
            "authURL" to operationSession.authUrl)
        val response = parseJson(http.post("https://www.netflix.com/api/shakti/mre/playlistop",body.toString().toRequestBody(JSON),
            expectedGeneration=operationGeneration),"moja lista")
        if (response.optString("status") != "success") throw NetflixApiException("moja lista",message="Netflix nie potwierdził zmiany Mojej listy.")
        sessions.requireGeneration(operationGeneration)
        val key=cacheKey(id,operationGeneration)
        titles[key]?.copy(isInMyList=add)?.also { titles[key]=it }
    } }

    /** A series is resolved to a real episode before a playback manifest is requested. */
    suspend fun resolvePlayable(id: String): NfTitle = withContext(Dispatchers.IO) {
        requireVideo(id)
        val generation = sessions.generation
        val title = titles[cacheKey(id, generation)] ?: metadataTitle(id, generation)
        if (title?.isSeries != true) return@withContext title ?: NfTitle(id,"Odtwarzanie")
        currentEpisodes[cacheKey(id, generation)]?.let { return@withContext it }
        val detail = details(id)
        sessions.requireGeneration(generation)
        currentEpisodes[cacheKey(id, generation)]?.let { return@withContext it }
        if(title.resumeSeconds>0) throw NetflixApiException("odcinki",message="Netflix zwrócił pozycję oglądania bez identyfikatora bieżącego odcinka. Wybierz odcinek w szczegółach serialu.")
        var episode = detail.episodes.firstOrNull()
        // A first season can consist of unavailable specials. Try the next returned season once.
        if (episode == null) detail.seasons.firstOrNull { it.id != detail.selectedSeasonId }?.let {
            episode = loadEpisodes(it.id, generation).firstOrNull()
        }
        val playable = episode ?: throw NetflixApiException("odcinki",message="Netflix nie zwrócił dostępnego odcinka. Otwórz szczegóły serialu i wyeksportuj log sesji, jeśli lista sezonów lub odcinków jest pusta.")
        sessions.requireGeneration(generation)
        NfTitle(playable.id,"${title.title} · ${playable.title}",playable.synopsis,playable.artworkUrl,
            runtimeSeconds=playable.durationSeconds)
    }

    fun cachedTitle(id: String): NfTitle? = titles[cacheKey(id)]
    fun localResume(id: String): Long = sessions.read()?.let { sessions.resumeSeconds(it.profileGuid,id) } ?: 0
    fun recordPosition(id: String, positionMs: Long, durationMs: Long, profile: String, generation: Long) {
        val seconds = if (durationMs > 0 && positionMs >= durationMs-5000) 0 else positionMs.coerceAtLeast(0)/1000
        sessions.savePosition(profile,id,seconds,generation)
    }
    fun clearMemory() { snapshot=null; hydrated=null; titles.clear(); currentEpisodes.clear(); metadata.clear(); seasonShows.clear() }

    private fun locoRows(root: String, generation: Long): List<CatalogRow> {
        val range = json("from" to 0, "to" to 25)
        val itemRange = json("from" to 0, "to" to 20)
        val base = listOf("locos", root, range)
        // Try paged and direct layouts separately: an unsupported path can reject the whole request.
        for (paged in listOf(true, false)) {
            val items = base + if (paged) listOf("page", 0, itemRange) else listOf(itemRange)
            val fields = arr("summary","title","synopsis","runtime","releaseYear","maturity","queue","bookmarkPosition","seasonCount")
            fun paths(selectedFields: JSONArray) = listOf(arr("locos",root,"componentSummary"),
                JSONArray(base+"componentSummary"), JSONArray(items+"itemSummary"), JSONArray(items+listOf("reference",selectedFields)))
            var data = fallback("home_falcor") { path(paths(fields), generation) }
            if (data == null) data = fallback("home_falcor_light") { path(paths(arr("summary","availability","queue")), generation) }
            if (data == null) continue
            val parser = CatalogGraph(data)
            val rows = parser.falcorRows()
            sessions.requireGeneration(generation)
            parser.currentEpisodes().forEach { (id, title) -> currentEpisodes[cacheKey(id,generation)] = title }
            AppLog.info("catalog", "home_falcor_result", mapOf("source" to if (paged) "falcor_paged" else "falcor_direct", "rowCount" to rows.size, "recovered" to rows.isNotEmpty()))
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun cacheBrowse(browse: BrowseSnapshot, generation: Long) {
        sessions.requireGeneration(generation)
        (CatalogGraph(browse.graphql).currentEpisodes() + CatalogGraph(browse.falcor).currentEpisodes())
            .forEach { (id,title) -> currentEpisodes[cacheKey(id,generation)] = title }
        browse.rows.flatMap { it.items }.forEach { titles[cacheKey(it.id,generation)] = it }
    }

    private fun metadataTitle(id: String, generation: Long = sessions.generation): NfTitle? =
        metadataVideo(id, generation)?.let { NetflixMetadata.title(it, id) }?.let { remember(it, generation) }

    private fun metadataVideo(id: String, generation: Long): JSONObject? {
        val key = cacheKey(id, generation)
        metadata[key]?.let { return it }
        val s = sessions.read(generation) ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
        val url = (s.apiUrl+"/metadata").toHttpUrl().newBuilder()
            .addQueryParameter("movieid",id).addQueryParameter("authURL",s.authUrl)
            .addQueryParameter("_",System.currentTimeMillis().toString()).build()
        val data = parseJson(http.get(url.toString(), generation),"szczegóły").optJSONObject("video") ?: return null
        sessions.requireGeneration(generation)
        if (NetflixMetadata.title(data, id) == null) return null
        metadata[key] = data
        return data
    }

    private fun graphql(name: String, variables: JSONObject, operationId: String, generation: Long = sessions.generation): JSONObject {
        val s = sessions.read(generation) ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
        val payload = json("operationName" to name,"variables" to variables,
            "extensions" to json("persistedQuery" to json("id" to operationId,"version" to 102)))
        val headers = linkedMapOf("x-netflix.nq.stack" to "prod","x-netflix.request.client.user.guid" to s.profileGuid)
        listOf("X-Netflix.browserVersion" to s.browserVersion,"X-Netflix.osName" to s.osName,
            "X-Netflix.osVersion" to s.osVersion,"X-Netflix.uiVersion" to s.uiVersion).filter { it.second.isNotBlank() }.forEach { headers[it.first]=it.second }
        AppLog.info("catalog", "graphql_request", mapOf("operation" to name))
        val data = NetflixHttp.requireGraphQlData(parseJson(http.post("https://web.prod.cloud.netflix.com/graphql",
            payload.toString().toRequestBody(JSON),headers,generation),"GraphQL"))
        AppLog.info("catalog", "graphql_response", mapOf("operation" to name, "hasData" to (data.length() > 0), "nodeCount" to NetflixGraph(data).allNodes().size))
        return data
    }

    /** Only schema / retired API failures qualify. Authentication and rate-limit errors stay fatal. */
    private inline fun <T> fallback(operation: String, block: () -> T): T? = try { block() } catch (error: NetflixApiException) {
        if (error.stage == "sesja" || (error.statusCode != null && error.statusCode !in setOf(404, 412))) throw error
        AppLog.error("catalog", "fallback_required", error, mapOf("operation" to operation, "httpStatus" to error.statusCode))
        null
    }

    private fun path(paths: List<JSONArray>, expectedGeneration: Long = sessions.generation): JSONObject {
        val s = sessions.read(expectedGeneration) ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
        val url = (s.apiUrl+"/pathEvaluator").toHttpUrl().newBuilder()
        mapOf("webp" to "false","drmSystem" to "widevine","isVolatileBillboardsEnabled" to "true",
            "isTop10Supported" to "true","hasVideoMerchInBob" to "false","hasVideoMerchInJaw" to "false",
            "falcor_server" to "0.1.0","withSize" to "true","materialize" to "true",
            "original_path" to "/shakti/mre/pathEvaluator").forEach { (key,value) -> url.addQueryParameter(key,value) }
        val form = FormBody.Builder().apply { paths.forEach { add("path",it.toString()) }; add("authURL",s.authUrl) }.build()
        val headers = mapOf("x-netflix.nq.stack" to "prod", "x-netflix.request.client.context" to "www.netflix.com",
            "x-netflix.request.client.user.guid" to s.profileGuid, "sec-fetch-dest" to "empty", "sec-fetch-mode" to "cors", "sec-fetch-site" to "same-origin")
        val payload = parseJson(http.post(url.build().toString(),form,headers,expectedGeneration),"katalog")
        if (payload.optJSONArray("errors")?.length()?.let { it>0 } == true) throw NetflixApiException("katalog",message="Netflix odrzucił zapytanie katalogu Falcor.")
        return payload.optJSONObject("jsonGraph") ?: throw NetflixApiException("katalog",message="Nieobsługiwany format odpowiedzi Netflixa.")
    }

    private fun cacheKey(id:String,generation:Long=sessions.generation)="$generation:$id"
    private fun remember(title: NfTitle, generation:Long=sessions.generation): NfTitle {
        sessions.requireGeneration(generation)
        return title.copy(resumeSeconds=maxOf(title.resumeSeconds,localResume(title.id))).also { titles[cacheKey(it.id,generation)]=it }
    }
    private fun merge(fresh: NfTitle, old: NfTitle?): NfTitle = if (old == null) fresh else fresh.copy(
        synopsis=fresh.synopsis.ifBlank { old.synopsis }, artworkUrl=fresh.artworkUrl ?: old.artworkUrl,
        heroUrl=fresh.heroUrl ?: old.heroUrl,year=fresh.year.ifBlank { old.year }, maturity=fresh.maturity.ifBlank { old.maturity },
        runtimeSeconds=fresh.runtimeSeconds.takeIf { it>0 } ?: old.runtimeSeconds,
        resumeSeconds=maxOf(fresh.resumeSeconds,old.resumeSeconds), isInMyList=fresh.isInMyList || old.isInMyList)
    private fun requireVideo(id: String) { if (id.toLongOrNull()?.let { it>0 } != true) throw NetflixApiException("tytuł",message="Niepoprawny identyfikator filmu.") }
    private fun parseJson(value: String, stage: String) = try { JSONObject(value) } catch (_: Exception) {
        throw NetflixApiException(stage,message="Netflix zwrócił stronę zamiast danych. Zaloguj się ponownie lub spróbuj później.")
    }

    private fun searchVariables(term: String): JSONObject {
        val entityTreatments = json(
            "pinotStandardBoxshot" to json("base" to json("canHandleEntityKinds" to arr("VIDEO"))),
            "pinotStandardCloudAppIcon" to json("base" to json("canHandleEntityKinds" to arr("GAME"))),
            "pinotStandardMobileAppIcon" to json("base" to json("canHandleEntityKinds" to arr("GAME"))),
            "pinotStandardDestination" to json("base" to json("canHandleEntityKinds" to arr("GENERIC_CONTAINER"))))
        fun treatments() = json("base" to json("capabilitiesByEntityTreatment" to entityTreatments,"maxTotalEntities" to 300))
        val options = json("pageCapabilities" to json("base" to json("canHandlePlayingCloudGames" to false,
            "capabilitiesBySection" to json(
                "pinotGallery" to json("base" to json("capabilitiesBySectionTreatment" to json("pinotCreatorHome" to treatments(),"pinotStandard" to treatments()))),
                "pinotList" to json("base" to json("capabilitiesBySectionTreatment" to json("pinotSuggestions" to json("base" to json(
                    "capabilitiesByEntityTreatment" to json("pinotSuggestion" to json("base" to json("canHandleEntityKinds" to arr("AUTOCOMPLETE","VIDEO","CHARACTER","GENERIC_CONTAINER","GENRE","PERSON")))),
                    "maxTotalEntities" to 100)))))),"maxTotalSections" to 2)),"session" to json("id" to UUID.randomUUID().toString()))
        fun artwork(type: String,w: Int,h: Int,badge: Boolean=false): JSONObject = json("artworkType" to type,
            "dimension" to json("width" to w,"height" to h),
            "features" to json("fallbackStrategy" to "STILL").apply { if(badge) put("topContentTypeBadge",true) })
        return json("imageParamsForStandardBoxart" to artwork("BOXSHOT",300,420),
            "imageParamsForCloudGameBoxart" to artwork("GAME_CLOUD_BOXART_HORIZONTAL_INCOMPATIBLE",342,192,true),
            "imageParamsForMobileGameBoxart" to artwork("GAME_ICON_BOXART_HORIZONTAL_CARD",342,192,true),
            "pageSize" to 48,"options" to options,"searchTerm" to term,"selectedSuggestionId" to null,"endCursor" to null)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val SEASONS_ID="dbc3b274-d4f9-4811-aaf1-d082d3b936f2"
        private const val EPISODES_ID="27b30e4e-871d-46aa-ac8b-244103d2e37d"
        private const val SEARCH_ID="8d902979-56f2-4886-8c16-f8910f6b52ee"
        private const val DETAIL_ID="7265187b-c065-4714-9eee-327751c8e215"
    }
}
