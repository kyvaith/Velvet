@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pl.kyvaith.velvet.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.kyvaith.velvet.playback.PreparedPlayback
import pl.kyvaith.velvet.playback.findNetflixPlaybackFailure
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.diagnostics.PlayerStartupDiagnostics
import java.util.Locale

private enum class PlayerMenu { AUDIO, SUBTITLES, SPEED }

/** Owns the Media3 instance. Netflix license exchange stays in PreparedPlayback. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NetflixPlayerScreen(
    prepared: PreparedPlayback,
    onClose: () -> Unit,
    onPosition: (Long, Long) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    // Reading configuration ensures PiP size changes also re-evaluate the Activity state.
    val inPictureInPicture = remember(configuration, activity?.isInPictureInPictureMode) {
        activity?.isInPictureInPictureMode == true
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnClose by rememberUpdatedState(onClose)
    val player = remember(prepared) {
        ExoPlayer.Builder(context.applicationContext)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
            }
    }
    val startupDiagnostics = remember(player) { PlayerStartupDiagnostics() }
    var controlsVisible by remember(prepared) { mutableStateOf(true) }
    var locked by remember(prepared) { mutableStateOf(false) }
    var isPlaying by remember(prepared) { mutableStateOf(false) }
    var playbackState by remember(prepared) { mutableIntStateOf(Player.STATE_IDLE) }
    var position by remember(prepared) { mutableLongStateOf(prepared.request.resumePositionMs.coerceAtLeast(0)) }
    var duration by remember(prepared) { mutableLongStateOf(prepared.durationMs.coerceAtLeast(0)) }
    var bufferedPosition by remember(prepared) { mutableLongStateOf(0) }
    var seekPosition by remember(prepared) { mutableStateOf<Float?>(null) }
    var currentTracks by remember(prepared) { mutableStateOf(Tracks.EMPTY) }
    var playbackSpeed by remember(prepared) { mutableFloatStateOf(1f) }
    var playerMenu by remember(prepared) { mutableStateOf<PlayerMenu?>(null) }
    var zoomed by remember(prepared) { mutableStateOf(false) }
    var failed by remember(prepared) { mutableStateOf(false) }
    var failureMessage by remember(prepared) { mutableStateOf("Szczegóły błędu znajdziesz w dzienniku sesji.") }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player, prepared) {
        val mediaSession = MediaSession.Builder(context, player).build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                AppLog.info("player", "playing_changed", mapOf("isPlaying" to value))
            }
            override fun onPlaybackStateChanged(value: Int) {
                playbackState = value
                AppLog.info("player", "state_changed", mapOf("playerState" to when(value) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> "unknown"
                }))
                if (value == Player.STATE_ENDED) controlsVisible = true
            }
            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
                AppLog.info("player", "tracks_changed", mapOf("trackCount" to tracks.groups.sumOf { it.length }))
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { playbackSpeed = parameters.speed }
            override fun onPlayerError(error: PlaybackException) {
                val serviceFailure = findNetflixPlaybackFailure(error)
                AppLog.error("player", "media3_error", error, mapOf("mediaCode" to error.errorCode, "code" to error.errorCodeName,
                    "serviceCode" to serviceFailure?.serviceCode, "stage" to serviceFailure?.stage?.name,
                    "retryable" to serviceFailure?.retryable))
                failed = true
                controlsVisible = true
                // Only our fixed, privacy-safe service messages may be displayed. Raw Media3
                // exception messages can contain stream URLs and must never be copied here.
                failureMessage = serviceFailure?.message
                    ?: "Błąd odtwarzacza (${error.errorCode}). Szczegóły znajdziesz w dzienniku sesji."
                currentOnError(failureMessage)
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(startupDiagnostics)
        try {
            player.setMediaSource(prepared.createMediaSource())
            player.seekTo(prepared.request.resumePositionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = true
        } catch (error: Exception) {
            AppLog.error("player", "preparation_failed", error)
            failed = true
            failureMessage = findNetflixPlaybackFailure(error)?.message
                ?: "Nie udało się przygotować odtwarzacza. Szczegóły znajdziesz w dzienniku sesji."
            currentOnError(failureMessage)
        }
        onDispose {
            val finalDuration = player.duration.takeIf { it > 0 } ?: prepared.durationMs
            if (finalDuration > 0 && !failed) currentOnPosition(player.currentPosition.coerceAtLeast(0), finalDuration)
            startupDiagnostics.close(player)
            player.removeAnalyticsListener(startupDiagnostics)
            player.removeListener(listener)
            mediaSession.release()
            player.release()
            // The composition scope is already cancelled here. Use a short, independently owned
            // cleanup scope so Netflix's license-release request still gets a chance to complete.
            val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanup.launch {
                try { prepared.close() } catch (ex: Exception) { AppLog.error("player", "release_failed", ex) }
                finally { cleanup.cancel() }
            }
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        var resumeAfterBackground = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) {
                resumeAfterBackground = player.playWhenReady
                player.pause()
            } else if (event == Lifecycle.Event.ON_START && resumeAfterBackground) {
                resumeAfterBackground = false
                player.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            startupDiagnostics.tick(player)
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: prepared.durationMs.coerceAtLeast(0)
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0)
            if (duration > 0 && !failed && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)) {
                currentOnPosition(position, duration)
            }
            delay(1000)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying, playerMenu, locked, seekPosition != null) {
        if (controlsVisible && isPlaying && playerMenu == null && !locked && seekPosition == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) { delay(800); seekFeedback = null }
    }
    BackHandler {
        when {
            playerMenu != null -> playerMenu = null
            locked -> { locked = false; controlsVisible = true }
            else -> currentOnClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; this.player = player; setKeepContentOnPlayerReset(true) } },
            update = { it.player = player; it.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )
        if (!inPictureInPicture) {
            Box(
                Modifier.fillMaxSize().pointerInput(player, locked) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            if (!locked && !failed) {
                                if (offset.x < size.width / 2) { player.seekBack(); seekFeedback = "−10 s" }
                                else { player.seekForward(); seekFeedback = "+10 s" }
                            }
                        },
                    )
                },
            )
            if (playbackState == Player.STATE_BUFFERING && !failed) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(43.dp), color = Color.White, strokeWidth = 2.5.dp,
            )
            seekFeedback?.let {
                Text(it, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 16.dp), color = Color.White)
            }
            AnimatedVisibility(controlsVisible && !locked && !failed, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Black.copy(alpha = .15f), Color.Black.copy(alpha = .85f))))) {
                    Row(
                        Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 10.dp, vertical = 7.dp).align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { currentOnClose() }) { Icon(Icons.Rounded.ArrowBack, "Zamknij odtwarzacz", tint = Color.White) }
                        Text(prepared.request.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        IconButton(onClick = { zoomed = !zoomed }) {
                            Icon(if (zoomed) Icons.Rounded.FitScreen else Icons.Rounded.CropFree,
                                if (zoomed) "Dopasuj obraz do ekranu" else "Wypełnij ekran", tint = Color.White)
                        }
                    }
                    if (playbackState != Player.STATE_BUFFERING) Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(35.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { player.seekBack(); controlsVisible = true }, modifier = Modifier.size(55.dp)) {
                            Icon(Icons.Rounded.Replay10, "Cofnij 10 sekund", tint = Color.White, modifier = Modifier.size(33.dp))
                        }
                        FilledIconButton(
                            onClick = {
                                if (playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play() }
                                else if (isPlaying) player.pause() else player.play()
                            },
                            modifier = Modifier.size(70.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) {
                            Icon(if (playbackState == Player.STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (isPlaying) "Wstrzymaj" else "Odtwórz", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { player.seekForward(); controlsVisible = true }, modifier = Modifier.size(55.dp)) {
                            Icon(Icons.Rounded.Forward10, "Przewiń 10 sekund", tint = Color.White, modifier = Modifier.size(33.dp))
                        }
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatPlaybackTime((seekPosition ?: position.toFloat()).toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = seekPosition ?: position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                                onValueChange = { seekPosition = it },
                                onValueChangeFinished = {
                                    seekPosition?.let { player.seekTo(it.toLong()); position = it.toLong() }
                                    seekPosition = null
                                },
                                enabled = duration > 0,
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                                colors = SliderDefaults.colors(thumbColor = VelvetRed, activeTrackColor = VelvetRed, inactiveTrackColor = Color.White.copy(alpha = .28f)),
                            )
                            Text(formatPlaybackTime(duration), color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PlayerTool(Icons.Rounded.LockOpen, "Blokada") { locked = true }
                            PlayerTool(Icons.Rounded.Speed, "${formatSpeed(playbackSpeed)}×") { playerMenu = PlayerMenu.SPEED }
                            PlayerTool(Icons.Rounded.VolumeUp, "Dźwięk") { playerMenu = PlayerMenu.AUDIO }
                            PlayerTool(Icons.Rounded.Subtitles, "Napisy") { playerMenu = PlayerMenu.SUBTITLES }
                        }
                    }
                }
            }
            if (locked && controlsVisible) FilledTonalButton(
                onClick = { locked = false; controlsVisible = true },
                modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(22.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Black.copy(alpha = .75f), contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Odblokuj")
            }
            if (failed) Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .85f)).safeDrawingPadding()
                    .verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = VelvetRed, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(14.dp))
                Text("Nie udało się odtworzyć materiału", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(failureMessage, style = MaterialTheme.typography.bodyMedium, color = VelvetMuted, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(22.dp))
                Button(onClick = { currentOnClose() }) { Text("Wróć do tytułu") }
            }
        }
    }

    when (playerMenu) {
        PlayerMenu.AUDIO, PlayerMenu.SUBTITLES -> TrackPicker(
            tracks = currentTracks,
            type = if (playerMenu == PlayerMenu.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT,
            onDismiss = { playerMenu = null },
            onSelect = { trackType, selection ->
                val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType)
                if (selection == null) builder.setTrackTypeDisabled(trackType, trackType == C.TRACK_TYPE_TEXT)
                else builder.setTrackTypeDisabled(trackType, false).addOverride(TrackSelectionOverride(selection.group.mediaTrackGroup, selection.index))
                player.trackSelectionParameters = builder.build()
                playerMenu = null
                controlsVisible = true
            },
        )
        PlayerMenu.SPEED -> ModalBottomSheet(onDismissRequest = { playerMenu = null }, containerColor = VelvetSurface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Prędkość odtwarzania", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    SelectionRow("${formatSpeed(speed)}×${if (speed == 1f) " · Normalna" else ""}", speed == playbackSpeed) {
                        player.setPlaybackSpeed(speed)
                        playerMenu = null
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        null -> Unit
    }
}

private data class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TrackPicker(tracks: Tracks, type: Int, onDismiss: () -> Unit, onSelect: (Int, TrackOption?) -> Unit) {
    val options = remember(tracks, type) {
        tracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val format = group.getTrackFormat(index)
                val language = format.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("pl")) }
                    ?.takeIf { it.isNotBlank() && it != "und" }
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: language?.replaceFirstChar { it.titlecase(Locale.forLanguageTag("pl")) }
                    ?: if (type == C.TRACK_TYPE_AUDIO) "Ścieżka dźwiękowa ${index + 1}" else "Napisy ${index + 1}"
                val extra = if (type == C.TRACK_TYPE_AUDIO && format.channelCount >= 6) " · ${if (format.channelCount >= 8) "7.1" else "5.1"}" else ""
                TrackOption(group, index, label + extra, group.isTrackSelected(index))
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VelvetSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(if (type == C.TRACK_TYPE_AUDIO) "Dźwięk" else "Napisy", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            SelectionRow(if (type == C.TRACK_TYPE_AUDIO) "Automatycznie" else "Wyłącz napisy", type == C.TRACK_TYPE_TEXT && options.none { it.selected }) {
                onSelect(type, null)
            }
            options.forEach { option -> SelectionRow(option.label, option.selected) { onSelect(type, option) } }
            if (options.isEmpty()) Text("Brak dostępnych ścieżek.", color = VelvetMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .background(if (selected) VelvetRed.copy(alpha = .12f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = Color.White)
        if (selected) Icon(Icons.Rounded.Check, null, tint = VelvetRed, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PlayerTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

private fun formatSpeed(speed: Float): String = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
