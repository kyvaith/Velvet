package pl.kyvaith.velvet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.kyvaith.velvet.model.AppAction
import pl.kyvaith.velvet.model.NfEpisode
import pl.kyvaith.velvet.model.TitleDetails

@Composable
internal fun TitleDetailsScreen(details: TitleDetails, busy: Boolean, onAction: (AppAction) -> Unit) {
    val title = details.title
    var expandedSynopsis by remember(title.id) { mutableStateOf(false) }
    var seasonPicker by remember { mutableStateOf(false) }
    val selectedSeason = details.seasons.firstOrNull { it.id == details.selectedSeasonId } ?: details.seasons.firstOrNull()

    Box(Modifier.fillMaxSize().background(VelvetBackground)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            item(key = "artwork") {
                Box(Modifier.fillMaxWidth().height(330.dp)) {
                    Artwork(title.heroUrl ?: title.artworkUrl, title.title, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = .25f), Color.Transparent, VelvetBackground)),
                    ))
                    Text(
                        if (title.isSeries) "SERIAL" else "FILM",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = .8f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 8.dp),
                    )
                }
            }
            item(key = "information") {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(title.title, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    TitleMetadata(title)
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { onAction(AppAction.Play(title.id, title.resumeSeconds)) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(25.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (title.resumeSeconds > 0) "Kontynuuj oglądanie" else "Odtwórz", style = MaterialTheme.typography.titleSmall)
                    }
                    if (title.resumeSeconds > 0 && title.runtimeSeconds > 0 && !title.isSeries) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (title.resumeSeconds.toFloat() / title.runtimeSeconds).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            trackColor = VelvetElevated,
                        )
                        Text("Pozostało ${formatRuntime((title.runtimeSeconds - title.resumeSeconds).coerceAtLeast(0))}",
                            style = MaterialTheme.typography.bodySmall, color = VelvetMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (title.synopsis.isNotBlank()) {
                        Spacer(Modifier.height(22.dp))
                        Text(
                            title.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = .82f),
                            maxLines = if (expandedSynopsis) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (title.synopsis.length > 220) TextButton(
                            onClick = { expandedSynopsis = !expandedSynopsis },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (expandedSynopsis) "Zwiń opis" else "Czytaj więcej", color = Color.White) }
                    }
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = { onAction(AppAction.ToggleMyList(title.id)) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = VelvetSurface, contentColor = Color.White),
                    ) {
                        Icon(if (title.isInMyList) Icons.Rounded.Check else Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (title.isInMyList) "Na Mojej liście" else "Dodaj do Mojej listy")
                    }
                    Spacer(Modifier.height(25.dp))
                    HorizontalDivider(color = VelvetBorder.copy(alpha = .6f))
                }
            }
            if (title.isSeries) {
                item(key = "episodeHeader") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 20.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Odcinki", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (details.seasons.isNotEmpty()) Box {
                            OutlinedButton(onClick = { seasonPicker = true }, shape = RoundedCornerShape(10.dp)) {
                                Text(selectedSeason?.name?.takeIf { it.isNotBlank() } ?: "Sezon ${selectedSeason?.number ?: 1}", color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.ExpandMore, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = seasonPicker, onDismissRequest = { seasonPicker = false }) {
                                details.seasons.forEach { season ->
                                    DropdownMenuItem(
                                        text = { Text(season.name.ifBlank { "Sezon ${season.number}" }) },
                                        onClick = { seasonPicker = false; onAction(AppAction.SelectSeason(season.id)) },
                                        trailingIcon = { if (selectedSeason?.id == season.id) Icon(Icons.Rounded.Check, null, tint = VelvetRed) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) item(key = "episodeLoading") {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
                    }
                }
                items(details.episodes.distinctBy { it.id }, key = { "episode-${it.id}" }) { episode ->
                    EpisodeCard(episode) { onAction(AppAction.Play(episode.id, 0)) }
                }
                if (details.episodes.isEmpty() && !busy) item(key = "noEpisodes") {
                    Text("Nie udało się wczytać odcinków tego sezonu.", color = VelvetMuted,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                }
            }
        }
        IconButton(
            onClick = { onAction(AppAction.CloseTitle) },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 14.dp)
                .background(Color.Black.copy(alpha = .58f), CircleShape),
        ) {
            Icon(Icons.Rounded.Close, "Zamknij szczegóły", tint = Color.White)
        }
    }
}

@Composable
private fun EpisodeCard(episode: NfEpisode, onPlay: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(10.dp)).background(VelvetSurface)) {
                Artwork(episode.artworkUrl, null, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .15f)))
                Box(Modifier.size(33.dp).align(Alignment.Center).background(Color.Black.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Odtwórz odcinek ${episode.episodeNumber}", modifier = Modifier.size(23.dp), tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("ODCINEK ${episode.episodeNumber}", style = MaterialTheme.typography.labelSmall, color = VelvetMuted, letterSpacing = 1.sp)
                Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                if (episode.durationSeconds > 0) Text(formatRuntime(episode.durationSeconds), style = MaterialTheme.typography.bodySmall,
                    color = VelvetMuted, modifier = Modifier.padding(top = 5.dp))
            }
        }
        if (episode.synopsis.isNotBlank()) Text(episode.synopsis, style = MaterialTheme.typography.bodySmall, color = VelvetMuted,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 11.dp))
    }
}
