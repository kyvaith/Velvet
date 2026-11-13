package pl.kyvaith.velvet.model

import pl.kyvaith.velvet.playback.PreparedPlayback

data class NfProfile(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val isKids: Boolean = false,
    val isLocked: Boolean = false,
)

data class NfTitle(
    val id: String,
    val title: String,
    val synopsis: String = "",
    val artworkUrl: String? = null,
    val heroUrl: String? = null,
    val year: String = "",
    val maturity: String = "",
    val runtimeSeconds: Long = 0,
    val resumeSeconds: Long = 0,
    val isInMyList: Boolean = false,
    val isSeries: Boolean = false,
)

data class CatalogRow(val id: String, val title: String, val items: List<NfTitle>)
data class NfSeason(val id: String, val name: String, val number: Int)
data class NfEpisode(
    val id: String,
    val title: String,
    val synopsis: String = "",
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val durationSeconds: Long = 0,
    val artworkUrl: String? = null,
)
data class TitleDetails(
    val title: NfTitle,
    val seasons: List<NfSeason> = emptyList(),
    val episodes: List<NfEpisode> = emptyList(),
    val selectedSeasonId: String? = null,
)

enum class AppPhase { LOGGED_OUT, LOADING, PROFILES, READY }
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class AppUiState(
    val phase: AppPhase = AppPhase.LOGGED_OUT,
    val profiles: List<NfProfile> = emptyList(),
    val activeProfile: NfProfile? = null,
    val rows: List<CatalogRow> = emptyList(),
    val myList: List<NfTitle> = emptyList(),
    val searchResults: List<NfTitle> = emptyList(),
    val selectedTitle: TitleDetails? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val query: String = "",
    val diagnostics: String = "",
    val appVersion: String = "0.1.1",
    val playback: PreparedPlayback? = null,
    val playbackPreparing: Boolean = false,
)

sealed interface AppAction {
    data object Login : AppAction
    data class SelectProfile(val id: String) : AppAction
    data class Search(val query: String) : AppAction
    data class OpenTitle(val id: String) : AppAction
    data object CloseTitle : AppAction
    data class SelectSeason(val id: String) : AppAction
    data class Play(val id: String, val resumeSeconds: Long = 0) : AppAction
    data class ToggleMyList(val id: String) : AppAction
    data object LoadMyList : AppAction
    data object Refresh : AppAction
    data object ChangeProfile : AppAction
    data object Logout : AppAction
    data object ClearSession : AppAction
    data object DismissError : AppAction
    data object OpenDiagnostics : AppAction
    data object ExportDiagnostics : AppAction
    data object SaveDiagnostics : AppAction
    data object ClosePlayer : AppAction
    data class PlaybackProgress(val positionMs: Long, val durationMs: Long, val videoId: String = "") : AppAction
    data class PlaybackError(val message: String) : AppAction
}
