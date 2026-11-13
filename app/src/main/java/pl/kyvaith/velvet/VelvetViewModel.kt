// SPDX-License-Identifier: MIT
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package pl.kyvaith.velvet

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import pl.kyvaith.velvet.data.*
import pl.kyvaith.velvet.model.*
import pl.kyvaith.velvet.playback.*
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit

class VelvetViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VelvetApplication
    private val repo = app.netflix
    private val sessions = app.sessions
    private val mutableState = MutableStateFlow(AppUiState(appVersion=BuildConfig.VERSION_NAME))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private val playbackHttp=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
        .callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var accountJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var playbackJob: Job? = null
    private var generation = 0
    private var lastStage = "uruchomienie"
    private var lastResult = "Aplikacja uruchomiona. Odtwarzanie Netflixa nie zostało jeszcze sprawdzone."
    private var lastPositionSave = 0L
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var playbackProfile = ""
    private var playbackGeneration = -1L
    private var playbackAttempt = 0L

    init { restoreAccount() }

    fun loginFailed(message:String) {
        lastStage="logowanie"; lastResult=message.take(300)
        AppLog.error("login", "failed")
        mutableState.update { it.copy(error=message.take(300),busy=false) }
    }

    fun restoreAccount(openHome: Boolean = false) {
        AppLog.info("session", "restore_started")
        cancelAccountWork()
        accountJob = viewModelScope.launch {
            val session = withContext(Dispatchers.IO) { sessions.read() }
            if (session == null) {
                AppLog.info("session", "absent", mapOf("recovered" to sessions.unreadableSession))
                mutableState.value=AppUiState(appVersion=BuildConfig.VERSION_NAME,
                    error=if(sessions.unreadableSession) "Nie można odczytać zapisanej sesji. Zaloguj się ponownie." else null)
                return@launch
            }
            mutableState.update { it.copy(phase=AppPhase.LOADING,error=null,selectedTitle=null,
                rows=emptyList(),myList=emptyList(),searchResults=emptyList(),query="") }
            try {
                val data = repo.bootstrap()
                val active = data.profiles.firstOrNull { it.id == data.session.profileGuid }
                mutableState.update { it.copy(phase=AppPhase.PROFILES,profiles=data.profiles,activeProfile=active,busy=false) }
                note("sesja","Netflix potwierdził aktywne konto.")
                if (openHome && active != null) {
                    mutableState.update { it.copy(phase=AppPhase.READY,busy=true) }
                    val rows = repo.home()
                    mutableState.update { it.copy(rows=rows,busy=false) }
                    note("katalog","Wczytano dane wybranego profilu.")
                }
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) {
                mutableState.update { it.copy(phase=if(openHome && it.activeProfile!=null) AppPhase.READY else AppPhase.LOGGED_OUT,busy=false) }
                report(ex)
            }
        }
    }

    fun onAction(action: AppAction) {
        if (action !is AppAction.PlaybackProgress && action !is AppAction.Search && action != AppAction.DismissError) {
            AppLog.info("ui", "action", mapOf("operation" to action.javaClass.simpleName))
        }
        when(action) {
            AppAction.Login -> Unit // MainActivity opens the owned HTTPS login surface.
            is AppAction.SelectProfile -> selectProfile(action.id)
            is AppAction.Search -> search(action.query)
            is AppAction.OpenTitle -> openTitle(action.id)
            AppAction.CloseTitle -> { detailJob?.cancel(); mutableState.update { it.copy(selectedTitle=null,busy=false) } }
            is AppAction.SelectSeason -> selectSeason(action.id)
            is AppAction.Play -> play(action.id,action.resumeSeconds)
            is AppAction.ToggleMyList -> toggleMyList(action.id)
            AppAction.LoadMyList -> loadMyList()
            AppAction.Refresh -> if(state.value.phase==AppPhase.READY) refreshHome() else restoreAccount()
            AppAction.ChangeProfile -> {
                cancelAccountWork()
                mutableState.update { it.copy(phase=AppPhase.PROFILES,selectedTitle=null,error=null,busy=false,searchResults=emptyList(),query="") }
            }
            AppAction.Logout, AppAction.ClearSession -> logout()
            AppAction.DismissError -> mutableState.update { it.copy(error=null) }
            AppAction.OpenDiagnostics -> viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    DeviceDiagnostics.report(sessions.read(),lastStage,lastResult) + "\n\n" + AppLog.overview()
                }
                mutableState.update { it.copy(diagnostics=result) }
            }
            AppAction.ExportDiagnostics, AppAction.SaveDiagnostics -> Unit // MainActivity owns document/share launchers.
            AppAction.ClosePlayer -> closePlayer()
            is AppAction.PlaybackProgress -> if(action.videoId==state.value.playback?.request?.videoId) recordPosition(action.positionMs,action.durationMs)
            is AppAction.PlaybackError -> {
                lastStage="odtwarzacz"; lastResult=action.message.take(240)
                mutableState.update { it.copy(error=action.message) }
            }
        }
    }

    private fun selectProfile(id: String) {
        cancelAccountWork()
        accountJob = viewModelScope.launch {
            mutableState.update { it.copy(busy=true,error=null) }
            try {
                val data = repo.selectProfile(id)
                val selected = data.profiles.firstOrNull { it.id==id }
                    ?: throw NetflixApiException("profil",message="Netflix nie potwierdził profilu.")
                mutableState.update { it.copy(phase=AppPhase.READY,activeProfile=selected,rows=emptyList(),myList=emptyList(),searchResults=emptyList(),query="",selectedTitle=null) }
                val rows = repo.home()
                mutableState.update { it.copy(rows=rows,busy=false) }
                note("katalog","Wczytano dane profilu.")
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { mutableState.update { it.copy(busy=false) }; report(ex) }
        }
    }

    private fun refreshHome() {
        accountJob?.cancel()
        accountJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true,error=null) }
            try {
                val rows=repo.home()
                mutableState.update { it.copy(rows=rows,busy=false) }
                note("katalog","Odświeżono katalog.")
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { mutableState.update { it.copy(busy=false) }; report(ex) }
        }
    }

    private fun search(query: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(query=query,searchResults=if(query.isBlank()) emptyList() else it.searchResults) }
        if(query.isBlank()) return
        val currentGeneration=generation
        searchJob=viewModelScope.launch {
            delay(400)
            AppLog.info("catalog", "search_started")
            try {
                val result=repo.search(query)
                if (currentGeneration==generation && state.value.query==query) mutableState.update { it.copy(searchResults=result) }
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { if(currentGeneration==generation) report(ex) }
        }
    }

    private fun openTitle(id: String) {
        detailJob?.cancel()
        val currentGeneration=generation
        detailJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true,error=null,selectedTitle=repo.cachedTitle(id)?.let(::TitleDetails)) }
            try {
                val details=repo.details(id)
                if(currentGeneration==generation) mutableState.update { it.copy(selectedTitle=details,busy=false) }
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { if(currentGeneration==generation) { mutableState.update { it.copy(busy=false) }; report(ex) } }
        }
    }

    private fun selectSeason(id: String) {
        val selected=state.value.selectedTitle ?: return
        if(selected.seasons.none { it.id==id }) return
        detailJob?.cancel()
        detailJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true) }
            try {
                val episodes=repo.episodes(id)
                mutableState.update { current ->
                    if(current.selectedTitle?.title?.id != selected.title.id) current
                    else current.copy(selectedTitle=selected.copy(selectedSeasonId=id,episodes=episodes),busy=false)
                }
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { mutableState.update { it.copy(busy=false) }; report(ex) }
        }
    }

    private fun loadMyList() {
        mutationJob?.cancel()
        mutationJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true) }
            try {
                val items=repo.myList()
                mutableState.update { it.copy(myList=items,busy=false) }
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { mutableState.update { it.copy(busy=false) }; report(ex) }
        }
    }

    private fun toggleMyList(id: String) {
        if(mutationJob?.isActive == true) return
        val existing=repo.cachedTitle(id) ?: return
        val expectedProfile=state.value.activeProfile?.id ?: return
        mutationJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true) }
            try {
                val changed=repo.setInMyList(id,!existing.isInMyList,expectedProfile) ?: existing.copy(isInMyList=!existing.isInMyList)
                fun replace(items:List<NfTitle>)=items.map { if(it.id==id) changed else it }
                mutableState.update { current -> current.copy(busy=false,
                    rows=current.rows.map { it.copy(items=replace(it.items)) },searchResults=replace(current.searchResults),
                    myList=if(changed.isInMyList) (replace(current.myList)+changed).distinctBy { it.id } else current.myList.filterNot { it.id==id },
                    selectedTitle=current.selectedTitle?.let { if(it.title.id==id) it.copy(title=changed) else it }) }
                note("moja lista","Netflix potwierdził zmianę listy.")
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) { mutableState.update { it.copy(busy=false) }; report(ex) }
        }
    }

    private fun play(id: String, resumeSeconds: Long) {
        if(playbackJob?.isActive == true || state.value.playback!=null) return
        val attempt=++playbackAttempt
        playbackJob=viewModelScope.launch {
            var prepared:PreparedPlayback?=null
            var handedToUi=false
            mutableState.update { it.copy(playbackPreparing=true,error=null) }
            try {
                val ownerGeneration=sessions.generation
                val owner=sessions.read(ownerGeneration) ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
                val playbackOwner = PlaybackOwner(ownerGeneration, owner.playbackContext())
                if(owner.isAdsPlan) throw NetflixPlaybackException(PlaybackStage.MANIFEST,
                    message="Ta wersja nie obsługuje planów Netflix z reklamami.")
                val title=repo.resolvePlayable(id)
                val selectedEpisode=state.value.selectedTitle?.episodes?.firstOrNull { it.id==title.id }
                val label=selectedEpisode?.title ?: title.title
                val resume=maxOf(if(title.id==id) resumeSeconds else 0,repo.localResume(title.id),title.resumeSeconds)
                note("DRM","Próba uzyskania manifestu i sesji Widevine.")
                val playbackClient=NetflixPlaybackClient(app,playbackHttp,
                    StoredPlaybackSession(sessions, ownerGeneration, owner))
                val ready=playbackClient.prepare(PlaybackRequest(title.id,label,resume*1000))
                prepared=ready
                currentCoroutineContext().ensureActive()
                sessions.read(ownerGeneration) { currentOwner ->
                    currentOwner ?: throw NetflixApiException("sesja",401,"Zaloguj się ponownie.")
                    playbackOwner.requireOwner(ownerGeneration, currentOwner.playbackContext())
                    playbackProfile=owner.profileGuid
                    playbackGeneration=ownerGeneration
                    lastPosition=resume*1000; lastDuration=ready.durationMs; lastPositionSave=0
                    mutableState.update { it.copy(playback=ready,playbackPreparing=false) }
                    handedToUi=true
                }
                note("manifest","Manifest otrzymany. Odtwarzacz musi jeszcze uzyskać licencję filmu.")
            } catch(ex: CancellationException) { throw ex }
            catch(ex: Exception) {
                if (attempt == playbackAttempt) report(ex)
            }
            finally {
                if(!handedToUi) withContext(NonCancellable) { prepared?.close() }
                if(attempt==playbackAttempt) mutableState.update { it.copy(playbackPreparing=false) }
            }
        }
    }

    private fun recordPosition(position:Long,duration:Long) {
        val playback=state.value.playback ?: return
        val owner=playbackProfile
        val ownerGeneration=playbackGeneration
        lastPosition=position; lastDuration=duration
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastPositionSave>=10000) {
            lastPositionSave=now
            viewModelScope.launch(Dispatchers.IO) { repo.recordPosition(playback.request.videoId,position,duration,owner,ownerGeneration) }
        }
    }

    private fun closePlayer() {
        AppLog.info("player", "close_requested")
        playbackAttempt++
        playbackJob?.cancel()
        val owner=playbackProfile
        val ownerGeneration=playbackGeneration
        state.value.playback?.let { current ->
            val position=lastPosition
            val duration=lastDuration
            viewModelScope.launch(Dispatchers.IO) { repo.recordPosition(current.request.videoId,position,duration,owner,ownerGeneration) }
        }
        mutableState.update { it.copy(playback=null,playbackPreparing=false) }
        // The UI releases ExoPlayer first, then PreparedPlayback.close().
    }

    private fun cancelAccountWork() {
        generation++
        accountJob?.cancel(); searchJob?.cancel(); detailJob?.cancel(); mutationJob?.cancel()
        if (playbackJob?.isActive == true || state.value.playback != null) closePlayer()
    }

    private fun logout() {
        cancelAccountWork()
        closePlayer()
        repo.clearMemory()
        // The account's other devices are unaffected; this installation's credentials are erased.
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        sessions.clear()
        mutableState.value=AppUiState(appVersion=BuildConfig.VERSION_NAME)
        note("sesja","Usunięto lokalną sesję i historię tej instalacji.")
    }

    private fun report(ex:Exception) {
        val message=when(ex) {
            is NetflixApiException -> ex.message ?: "Netflix odrzucił zapytanie."
            is NetflixPlaybackException -> "${ex.message} (Etap: ${ex.stage.name.lowercase()})"
            is IOException -> "Nie udało się połączyć z Netflixem. Sprawdź połączenie i spróbuj ponownie."
            else -> "Nie udało się odczytać danych Netflixa. Jego API mogło się zmienić."
        }
        val stage=when(ex) { is NetflixApiException -> ex.stage; is NetflixPlaybackException -> ex.stage.name; else -> "aplikacja" }
        lastStage=stage; lastResult=message
        AppLog.error(stageCode(stage), "operation_failed", ex, mapOf(
            "httpStatus" to (ex as? NetflixApiException)?.statusCode,
            "serviceCode" to (ex as? NetflixPlaybackException)?.serviceCode,
            "code" to (ex as? NetflixPlaybackException)?.mslResponseCode?.toString(),
        ))
        mutableState.update { it.copy(error=message) }
    }
    private fun note(stage:String,result:String) {
        lastStage=stage; lastResult=result
        AppLog.info(stageCode(stage), "completed")
    }

    suspend fun exportDiagnostics(): String = withContext(Dispatchers.IO) {
        AppLog.info("diagnostics", "export_requested")
        val session = runCatching { sessions.read() }.onFailure { AppLog.error("session", "read_failed", it) }.getOrNull()
        // Do not copy dynamic server error messages into an export. The journal has codes and stack frames.
        AppLog.exportText(DeviceDiagnostics.report(session, "dziennik poniżej", "Wszystkie zapisane etapy i błędy w kolejności zdarzeń."))
    }

    private fun stageCode(stage: String): String = when(stage.lowercase(java.util.Locale.ROOT)) {
        "sesja" -> "session"
        "katalog" -> "catalog"
        "logowanie" -> "login"
        "profil" -> "profile"
        "odcinki" -> "episodes"
        "szczegóły" -> "details"
        "moja lista" -> "my_list"
        "odtwarzacz" -> "player"
        "aplikacja", "uruchomienie" -> "app"
        else -> stage.lowercase(java.util.Locale.ROOT)
    }
}
