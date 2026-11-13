// SPDX-License-Identifier: MIT
// Protocol field mappings adapted from plugin.video.netflix (see THIRD_PARTY_NOTICES.md).
package pl.kyvaith.velvet.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.model.CatalogRow
import pl.kyvaith.velvet.model.NfEpisode
import pl.kyvaith.velvet.model.NfProfile
import pl.kyvaith.velvet.model.NfSeason
import pl.kyvaith.velvet.model.NfTitle

class BrowseSnapshot(
    val session: SessionData,
    val profiles: List<NfProfile>,
    val rows: List<CatalogRow>,
    val graphql: JSONObject,
    val falcor: JSONObject,
    val profileLinks: Map<String, String>,
    val locoRootIds: List<String> = emptyList(),
)

object NetflixCatalogParser {
    fun page(html: String, previous: SessionData): BrowseSnapshot {
        val react = PageJson.assignment(html, "netflix.reactContext")
            ?: throw NetflixApiException("sesja", message="Nie można odczytać danych strony Netflixa. Otwórz logowanie i wybierz profil na stronie.")
        val graphqlOverride = JSONObject().apply {
            PageJson.assignments(html, "netflix.reactContext.models.graphql").forEach { patch ->
                val data = patch.optJSONObject("data") ?: patch
                data.entries().forEach { (key, value) -> put(key, value) }
            }
        }
        val falcor = PageJson.assignment(html, "netflix.falcorCache") ?: JSONObject()
        return bootstrap(react, graphqlOverride, falcor, previous, html)
    }

    fun captured(data: JSONObject, previous: SessionData): BrowseSnapshot {
        return bootstrap(data.optJSONObject("reactContext") ?: JSONObject(), null,
            data.optJSONObject("falcorCache") ?: JSONObject(), previous, "")
    }

    private fun bootstrap(react: JSONObject, override: JSONObject?, falcor: JSONObject, previous: SessionData, html: String): BrowseSnapshot {
        val user = react.at("models","userInfo","data").obj()
        when (user.optString("membershipStatus")) {
            "CURRENT_MEMBER" -> Unit
            "FORMER_MEMBER", "NEVER_MEMBER" -> throw NetflixApiException("sesja", message="To konto nie ma aktywnej subskrypcji Netflix.")
            else -> throw NetflixApiException("sesja", 401, "Netflix nie potwierdził zalogowania. Dokończ logowanie na stronie.")
        }
        val auth = user.optString("authURL")
        if (auth.isBlank()) throw NetflixApiException("sesja", message="Strona Netflixa nie udostępniła tokenu sesji.")
        val config = react.at("models","playerModel","data","config").obj()
        val services = react.at("models","services","data").obj()
        val memberApi = services.opt("memberapi")
        val api = when (memberApi) {
            is String -> if (memberApi.startsWith('/')) "https://www.netflix.com$memberApi" else memberApi
            is JSONObject -> "${memberApi.optString("protocol", "https").trimEnd(':')}://${memberApi.optString("hostname")}${memberApi.optJSONArray("path")?.optString(0).orEmpty()}"
            else -> previous.apiUrl
        }.trimEnd('/')
        if (api.toHttpUrlOrNull()?.let(NetflixOrigins::isApi) != true) throw NetflixApiException("sesja", message="Nieobsługiwany adres API zwrócony przez Netflix.")
        val session = previous.copy(
            authUrl=auth,
            esn=react.at("models","esnAccessor","data","esn").text().ifBlank { previous.esn },
            profileGuid=user.optString("userGuid").ifBlank { previous.profileGuid },
            buildIdentifier=react.at("models","serverDefs","data","BUILD_IDENTIFIER").text(),
            clientVersion=Regex("-([0-9.]+)\\.js").find(config.at("core","assets","core").text())?.groupValues?.get(1).orEmpty(),
            uiVersion=config.at("ui","initParams","uiVersion").text(),
            browserVersion=config.at("core","initParams","browserInfo","version").text(),
            osName=config.at("core","initParams","browserInfo","os","name").text(),
            osVersion=config.at("core","initParams","browserInfo","os","version").text(),
            locale=react.at("models","geo","data","preferredLocale","id").text().ifBlank { previous.locale },
            apiUrl=api,
            isAdsPlan=react.at("models","truths","data","isAdsPlan") == true,
        )
        val graph = JSONObject().apply {
            react.at("models","graphql","data").obj().entries().forEach { (key, value) -> put(key, value) }
            override?.entries()?.forEach { (key, value) -> put(key, value) }
        }
        val graphParser = CatalogGraph(graph)
        val profiles = profiles(falcor, graph)
        val links = LinkedHashMap<String, String>()
        // Only use switch links actually supplied by the authenticated website.
        Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val href = decodeHtml(match.groupValues[1])
            val url = "https://www.netflix.com/browse".toHttpUrlOrNull()!!.resolve(href)
            if (url != null && NetflixOrigins.isApi(url) && url.encodedPath.contains("SwitchProfile", true)) {
                val guid = url.queryParameter("tkn") ?: url.queryParameter("switchProfileGuid")
                if (guid != null && (guid == session.profileGuid || profiles.any { it.id == guid })) links[guid] = url.toString()
            }
        }
        val rows = graphParser.rows().ifEmpty { CatalogGraph(falcor).falcorRows() }
        val rootPattern = Regex("NES_[A-Za-z0-9_]+")
        val locoRoots = (falcor.optJSONObject("locos")?.keys()?.asSequence()?.toList().orEmpty() +
            rootPattern.findAll(html).map { it.value }.toList())
            .filter { rootPattern.matches(it) }.distinct().sortedByDescending { it.matches(Regex(".*_p_[0-9]+")) }.take(4)
        AppLog.info("catalog", "bootstrap_parsed", mapOf("hasApollo" to (graph.length() > 0),
            "hasFalcor" to (falcor.length() > 0), "rowCount" to rows.size, "profileCount" to profiles.size,
            "hasProfile" to session.profileGuid.isNotBlank(), "hasEsn" to session.esn.isNotBlank(),
            "modelCount" to react.optJSONObject("models").let { it?.length() ?: 0 },
            "locoRootCount" to locoRoots.size, "profileLinkCount" to links.size,
            "hasPlayerConfig" to (config.length() > 0),
            "outcome" to if (rows.isNotEmpty()) "catalog" else if (links.isNotEmpty()) "profile_gate" else "session_only"))
        return BrowseSnapshot(session, profiles, rows, graph, falcor, links, locoRoots)
    }

    fun profiles(falcor: JSONObject, graph: JSONObject): List<NfProfile> {
        val fg = NetflixGraph(falcor)
        val list = fg.resolve(falcor.opt("profilesList"))
        val entries = when (list) {
            is JSONObject -> list.entries().filter { it.first.toIntOrNull() != null }.sortedBy { it.first.toInt() }.map { it.second }
            is JSONArray -> list.values()
            else -> emptyList()
        }
        val fromFalcor = entries.mapNotNull { entry ->
            val summary = fg.field(entry, "summary").obj()
            val guid = summary.optString("guid")
            val name = summary.optString("profileName")
            if (guid.isBlank() || name.isBlank()) null else NfProfile(guid, decodeHtml(name),
                fg.image(fg.field(entry, "avatar")), summary.optBoolean("isKids"),
                summary.optBoolean("isProfileLocked") || summary.optBoolean("isProfileLockEnabled"))
        }
        if (fromFalcor.isNotEmpty()) return fromFalcor.distinctBy { it.id }
        val gg = NetflixGraph(graph)
        return gg.allNodes().filter { it.optString("__typename") == "Profile" }.mapNotNull {
            val id = it.optString("guid").ifBlank { it.optString("profileGuid") }
            val name = it.optString("name").ifBlank { it.optString("profileName") }
            if (id.isBlank() || name.isBlank()) null else NfProfile(id, decodeHtml(name),
                gg.image(gg.field(it, "avatar")), it.optBoolean("isKids"), it.optBoolean("isLocked"))
        }.distinctBy { it.id }
    }

    internal fun decodeHtml(text: String): String {
        var result = text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        result = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(result) {
            val number = it.groupValues[1]
            val cp = if (number.startsWith('x')) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else it.value
        }
        return result
    }
}

class CatalogGraph(private val cache: JSONObject) {
    private val graph = NetflixGraph(cache)
    private val nodes by lazy { graph.allNodes() }

    fun title(node: Any?): NfTitle? {
        val entity = graph.field(node, "unifiedEntity") ?: graph.field(node, "reference") ?: graph.resolve(node)
        val kind = graph.field(entity, "__typename").text()
        val summary = graph.field(entity, "summary").obj()
        val itemSummary = (graph.field(node, "itemSummary") ?: graph.field(entity, "itemSummary")).obj()
        val summaryType = summary.optString("type").ifBlank { itemSummary.optString("type") }
        if (kind !in setOf("Movie", "Show", "Episode", "SupplementalVideo") && summaryType !in setOf("movie", "show", "episode")) return null
        val id = graph.field(entity, "videoId").text().ifBlank { summary.opt("id").text() }
            .ifBlank { itemSummary.opt("videoId").text().ifBlank { itemSummary.opt("id").text() } }
        if (id.toLongOrNull()?.let { it > 0 } != true) return null
        val name = graph.field(node, "displayString").text().ifBlank {
            graph.field(entity, "title").text().ifBlank { summary.optString("name") }
                .ifBlank { itemSummary.optString("title").ifBlank { itemSummary.optString("name") }.ifBlank { itemSummary.optString("displayName") } }
        }
        if (name.isBlank()) return null
        val contextual = graph.field(node, "contextualArtwork")
        val artwork = graph.image(graph.field(contextual, "artwork"))
            ?: graph.image(graph.field(entity, "boxarts"))
            ?: graph.image(graph.field(entity, "artwork"))
            ?: graph.image(graph.field(entity, "boxArt"))
            ?: graph.image(itemSummary.opt("boxArt"))
        val synopsisNode = graph.field(entity, "contextualSynopsis")
        val synopsis = graph.field(synopsisNode, "text").text().ifBlank { graph.field(entity, "synopsis").text() }
            .ifBlank { itemSummary.at("synopses", "regularSynopsis").text().ifBlank { itemSummary.at("synopses", "shortSynopsis").text() } }
        val current = graph.field(entity, "currentEpisode") ?: entity
        val bookmark = graph.field(current, "bookmark")
        val advisory = graph.field(entity, "contentAdvisory") ?: graph.field(entity, "maturity")
        val maturity = graph.field(advisory, "rating").text().ifBlank { graph.field(advisory, "value").text() }
        return NfTitle(
            id=id, title=NetflixCatalogParser.decodeHtml(name), synopsis=synopsis, artworkUrl=artwork,
            heroUrl=graph.image(graph.field(entity, "storyArt")) ?: graph.image(graph.field(entity, "backgroundImage")) ?: artwork,
            year=graph.field(entity, "releaseYear").text(), maturity=maturity,
            runtimeSeconds=graph.field(entity,"runtimeSec").number().takeIf { it > 0 } ?: graph.field(entity,"runtime").number(),
            resumeSeconds=graph.field(bookmark,"position").number().takeIf { it > 0 }
                ?: graph.field(bookmark,"offset").number().takeIf { it > 0 } ?: graph.field(entity,"bookmarkPosition").number(),
            isInMyList=graph.field(entity,"isInMyList") == true || graph.field(graph.field(entity,"queue"),"inQueue") == true,
            isSeries=kind == "Show" || summaryType == "show",
        )
    }

    fun titles(): List<NfTitle> = nodes.mapNotNull(::title).distinctBy { it.id }

    /** The service's current episode belongs to a show; it must not be confused with the show's ID. */
    fun currentEpisodes(): Map<String, NfTitle> = nodes.mapNotNull { node ->
        val summary = graph.field(node, "summary").obj()
        if (graph.field(node, "__typename").text() != "Show" && summary.optString("type") != "show") return@mapNotNull null
        val showId = videoId(node)
        val episode = graph.field(node, "currentEpisode") ?: graph.field(node, "current") ?: return@mapNotNull null
        val episodeId = videoId(episode)
        if (showId.isBlank() || episodeId.isBlank() || showId == episodeId) return@mapNotNull null
        val parsed = title(episode) ?: NfTitle(episodeId,
            graph.field(episode, "title").text().ifBlank { graph.field(node, "title").text() }.ifBlank { "Odcinek serialu" },
            resumeSeconds = bookmark(episode),
            runtimeSeconds = graph.field(episode, "runtimeSec").number().takeIf { it > 0 } ?: graph.field(episode, "runtime").number())
        showId to parsed
    }.toMap()

    private fun bookmark(node: Any?): Long {
        val bookmark = graph.field(node, "bookmark")
        return graph.field(bookmark, "position").number().takeIf { it > 0 }
            ?: graph.field(bookmark, "offset").number().takeIf { it > 0 }
            ?: graph.field(node, "bookmarkPosition").number()
    }

    private fun videoId(node: Any?): String = graph.field(node, "videoId").text()
        .ifBlank { graph.field(graph.field(node, "summary"), "id").text() }
        .takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }.orEmpty()

    fun rows(): List<CatalogRow> {
        val sections = nodes.filter { it.optString("__typename") in setOf("PinotCarouselSection", "PinotGallerySection") }
        val rows = sections.mapNotNull { section ->
            val items = graph.edges(graph.field(section,"entities")).mapNotNull(::title).distinctBy { it.id }
            val name = graph.field(section, "displayString").text()
            if (items.isEmpty() || name.isBlank()) null else CatalogRow(
                section.optString("_id").ifBlank { section.optString("id").ifBlank { name } }, name, items)
        }.distinctBy { it.id }
        AppLog.info("catalog", "rows_shape", mapOf("nodeCount" to nodes.size, "sectionCount" to sections.size,
            "connectionCount" to sections.count { graph.field(it, "entities") != null },
            "rootVideoCount" to graph.edges(graph.field(cache, "videos")).size,
            "unresolvedRefCount" to graph.unresolvedReferenceCount(),
            "rowCount" to rows.size, "titleCount" to rows.sumOf { it.items.size },
            "cacheKind" to if (cache.has("ROOT_QUERY")) "apollo" else "nested"))
        return rows
    }

    fun falcorRows(): List<CatalogRow> = nodes.mapNotNull { list ->
        val summary = graph.field(list, "componentSummary").obj()
        val label = summary.optString("displayName").ifBlank { summary.optString("name") }
        if (label.isBlank()) return@mapNotNull null
        // The page collection is on the list itself, not inside each numbered list item.
        val directItems = graph.edges(list)
        val pagedItems = graph.edges(graph.field(list, "page")).flatMap(graph::edges)
        val items = (directItems + pagedItems).mapNotNull(::title).distinctBy { it.id }
        if (items.isEmpty()) null else CatalogRow(summary.optString("id", label), label, items)
    }.distinctBy { it.id }

    /** The persisted selector query may omit the parent videoId; its singleton videos root
     * is still scoped by the requested showId/seasonId. Never pick an unrelated nested node. */
    private fun selectorParent(id: String, field: String, type: String): Any? {
        nodes.firstOrNull { videoId(it) == id && graph.field(it, field) != null }?.let { return it }
        val roots = graph.edges(graph.field(cache, "videos"))
        if (roots.size != 1) return null
        val root = roots.single()
        val returnedId = graph.field(root, "videoId").text()
            .ifBlank { graph.field(graph.field(root, "summary"), "id").text() }
        val returnedType = graph.field(root, "__typename").text()
        if (returnedId.isNotBlank() && returnedId != id) return null
        if (returnedType.isNotBlank() && returnedType != type) return null
        return root.takeIf { graph.field(it, field) != null }
    }

    fun seasons(showId: String): List<NfSeason> {
        val show = selectorParent(showId, "seasons", "Show")
        AppLog.info("catalog", "seasons_shape", mapOf("matchedParent" to (show != null), "nodeCount" to nodes.size))
        if (show == null) return emptyList()
        return graph.edges(graph.field(show, "seasons")).mapIndexedNotNull { index, node ->
            val id = videoId(node)
            if (id.isBlank()) null else NfSeason(id, graph.field(node, "title").text().ifBlank { "Sezon ${index+1}" },
                graph.field(node, "number").number().toInt().takeIf { it > 0 } ?: index+1)
        }.distinctBy { it.id }
    }

    fun episodes(seasonId: String): List<NfEpisode> {
        val season = selectorParent(seasonId, "episodes", "Season")
        AppLog.info("catalog", "episodes_shape", mapOf("matchedParent" to (season != null), "nodeCount" to nodes.size))
        if (season == null) return emptyList()
        val number = graph.field(season, "number").number().toInt().coerceAtLeast(1)
        return graph.edges(graph.field(season, "episodes")).mapIndexedNotNull { index, node ->
            val id = videoId(node)
            if (id.isBlank() || graph.field(node, "isPlayable") == false) null else NfEpisode(id,
                graph.field(node,"title").text().ifBlank { "Odcinek ${index+1}" },
                graph.field(graph.field(node,"contextualSynopsis"),"text").text().ifBlank { graph.field(node,"synopsis").text() },
                number, graph.field(node,"number").number().toInt().takeIf { it > 0 } ?: index+1,
                graph.field(node,"runtimeSec").number().takeIf { it > 0 } ?: graph.field(node,"displayRuntimeSec").number(),
                graph.image(graph.field(node,"artwork")))
        }.distinctBy { it.id }
    }
}

/** Metadata is an independent Netflix response, used when selector GraphQL has no usable result. */
object NetflixMetadata {
    private fun matches(video: JSONObject, expected: String): Boolean {
        val actual = video.opt("id").text().ifBlank { video.opt("videoId").text() }
        return actual.isBlank() || actual == expected
    }

    fun title(video: JSONObject, expectedId: String): NfTitle? {
        if (!matches(video, expectedId)) return null
        val name = video.optString("title")
        if (name.isBlank()) return null
        val graph = NetflixGraph(video)
        return NfTitle(expectedId, NetflixCatalogParser.decodeHtml(name), video.optString("synopsis"),
            graph.image(video.opt("boxart")) ?: graph.image(video.opt("boxArt")), graph.image(video.opt("storyArt")),
            video.opt("year").text(), runtimeSeconds=video.optLong("runtime"),
            resumeSeconds=video.optJSONObject("bookmark")?.optLong("offset") ?: 0,
            isSeries=video.optString("type") == "show" || video.optJSONArray("seasons") != null)
    }

    fun seasons(video: JSONObject, showId: String): List<NfSeason> {
        if (!matches(video, showId)) return emptyList()
        return video.optJSONArray("seasons")?.values().orEmpty().mapIndexedNotNull { index, value ->
            val node = value as? JSONObject ?: return@mapIndexedNotNull null
            val id = node.opt("id").text()
            if (id.toLongOrNull()?.let { it > 0 } != true) return@mapIndexedNotNull null
            val number = node.optInt("seq", node.optInt("number", index+1)).coerceAtLeast(1)
            NfSeason(id, node.optString("title").ifBlank { node.optString("name") }.ifBlank { "Sezon $number" }, number)
        }.distinctBy { it.id }
    }

    fun episodes(video: JSONObject, showId: String, seasonId: String): List<NfEpisode> {
        if (!matches(video, showId)) return emptyList()
        val seasonInfo = seasons(video, showId).firstOrNull { it.id == seasonId } ?: return emptyList()
        val season = video.optJSONArray("seasons")?.values()?.filterIsInstance<JSONObject>()
            ?.firstOrNull { it.opt("id").text() == seasonId } ?: return emptyList()
        val graph = NetflixGraph(video)
        return season.optJSONArray("episodes")?.values().orEmpty().mapIndexedNotNull { index, value ->
            val node = value as? JSONObject ?: return@mapIndexedNotNull null
            val id = node.opt("id").text()
            if (id.toLongOrNull()?.let { it > 0 } != true || (node.has("isPlayable") && !node.optBoolean("isPlayable"))) return@mapIndexedNotNull null
            val number = node.optInt("seq", node.optInt("number", index+1)).coerceAtLeast(1)
            NfEpisode(id, node.optString("title").ifBlank { "Odcinek $number" }, node.optString("synopsis"),
                seasonInfo.number, number, node.optLong("runtime"),
                graph.image(node.opt("stills")) ?: graph.image(node.opt("interestingMoment")) ?: graph.image(node.opt("boxart")))
        }.distinctBy { it.id }
    }
}
