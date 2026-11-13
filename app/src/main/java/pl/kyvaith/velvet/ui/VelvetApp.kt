@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pl.kyvaith.velvet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.kyvaith.velvet.model.*

private enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Główna", Icons.Rounded.Home),
    SEARCH("Szukaj", Icons.Rounded.Search),
    MY_LIST("Moja lista", Icons.Rounded.BookmarkBorder),
    SETTINGS("Ustawienia", Icons.Rounded.Tune),
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VelvetApp(state: AppUiState, onAction: (AppAction) -> Unit) {
    VelvetTheme {
        var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
        var showEarlyDiagnostics by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val currentAction by rememberUpdatedState(onAction)

        LaunchedEffect(state.error) {
            state.error?.takeIf(String::isNotBlank)?.let { message ->
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
                currentAction(AppAction.DismissError)
            }
        }
        LaunchedEffect(state.activeProfile?.id) { destination = Destination.HOME }

        val playback = state.playback
        if (playback != null) {
            NetflixPlayerScreen(
                prepared = playback,
                onClose = { onAction(AppAction.ClosePlayer) },
                onPosition = { position, duration -> onAction(AppAction.PlaybackProgress(position, duration, playback.request.videoId)) },
                onError = { onAction(AppAction.PlaybackError(it)) },
            )
            return@VelvetTheme
        }

        BackHandler(state.selectedTitle != null) { onAction(AppAction.CloseTitle) }
        BackHandler(state.selectedTitle == null && state.phase == AppPhase.READY && destination != Destination.HOME) {
            destination = Destination.HOME
        }

        Scaffold(
            containerColor = VelvetBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
            bottomBar = {
                if (state.phase == AppPhase.READY && state.selectedTitle == null) {
                    VelvetNavigation(destination) { next ->
                        destination = next
                        if (next == Destination.MY_LIST) onAction(AppAction.LoadMyList)
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                if (state.selectedTitle != null && state.phase == AppPhase.READY) {
                    TitleDetailsScreen(state.selectedTitle, state.busy, onAction)
                } else {
                    when (state.phase) {
                        AppPhase.LOGGED_OUT -> WelcomeScreen(state.busy) { onAction(AppAction.Login) }
                        AppPhase.LOADING -> AccountLoadingScreen()
                        AppPhase.PROFILES -> ProfilePicker(state, onAction)
                        AppPhase.READY -> Crossfade(destination, label = "navigation") { route ->
                            when (route) {
                                Destination.HOME -> HomeScreen(
                                    state = state,
                                    onSearch = { destination = Destination.SEARCH },
                                    onAction = onAction,
                                )
                                Destination.SEARCH -> SearchScreen(state, onAction)
                                Destination.MY_LIST -> MyListScreen(state, onAction)
                                Destination.SETTINGS -> SettingsScreen(state, onAction)
                            }
                        }
                    }
                }
                if (state.phase != AppPhase.READY) TextButton(
                    onClick = { showEarlyDiagnostics = true; onAction(AppAction.OpenDiagnostics) },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                ) {
                    Icon(Icons.Rounded.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Diagnostyka")
                }
                if (state.playbackPreparing) PlaybackPreparingOverlay { onAction(AppAction.ClosePlayer) }
            }
        }
        if (showEarlyDiagnostics) DiagnosticsDialog(state, onAction) { showEarlyDiagnostics = false }
    }
}

@Composable
private fun VelvetNavigation(destination: Destination, onSelect: (Destination) -> Unit) {
    Column {
        HorizontalDivider(color = VelvetBorder.copy(alpha = .45f))
        NavigationBar(containerColor = VelvetBackground, tonalElevation = 0.dp) {
            Destination.entries.forEach { item ->
                NavigationBarItem(
                    selected = item == destination,
                    onClick = { onSelect(item) },
                    icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(23.dp)) },
                    label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VelvetRed,
                        selectedTextColor = Color.White,
                        indicatorColor = VelvetRed.copy(alpha = .13f),
                        unselectedIconColor = VelvetMuted,
                        unselectedTextColor = VelvetMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(busy: Boolean, onLogin: () -> Unit) {
    Box(Modifier.fillMaxSize().background(VelvetBackground)) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.73f).background(
                Brush.radialGradient(listOf(Color(0xFF650718).copy(alpha = .65f), VelvetBackground)),
            ),
        )
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(30.dp))
                    BrandWordmark()
                    Spacer(Modifier.height(50.dp))
                    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(190.dp).border(1.dp, Color.White.copy(alpha = .055f), CircleShape))
                        Box(Modifier.size(154.dp).border(1.dp, VelvetRed.copy(alpha = .2f), CircleShape))
                        BrandMark(Modifier.size(104.dp))
                    }
                    Spacer(Modifier.height(38.dp))
                    Text("Twój Netflix.\nPo Twojemu.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Zaloguj się na swoje konto, wybierz profil i wróć do oglądania.",
                        color = VelvetMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 330.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(36.dp))
                    Button(
                        onClick = onLogin,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else {
                            Text("Połącz konto Netflix", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Wymagana aktywna subskrypcja Netflix.", color = VelvetMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Text("Wersja eksperymentalna. Zgodność odtwarzania zależy od Netflixa.", color = VelvetMuted,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountLoadingScreen() {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(Modifier.size(58.dp))
        Spacer(Modifier.height(28.dp))
        Text("Już za chwilę", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text("Wczytuję Twoje konto…", style = MaterialTheme.typography.bodyMedium, color = VelvetMuted)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ProfilePicker(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        BrandWordmark()
        Spacer(Modifier.height(48.dp))
        Text("Kto dziś ogląda?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text("Wybierz swój profil Netflix", color = VelvetMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        if (state.profiles.isEmpty()) {
            EmptyContent(
                title = "Nie udało się wczytać profili",
                message = "Odśwież konto, aby spróbować ponownie.",
                icon = Icons.Rounded.PersonOutline,
                modifier = Modifier.weight(1f),
                buttonLabel = "Spróbuj ponownie",
                onClick = { onAction(AppAction.Refresh) },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).widthIn(max = 440.dp),
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    Column(
                        Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = !state.busy) { onAction(AppAction.SelectProfile(profile.id)) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            ProfileAvatar(profile, Modifier.size(106.dp), 22.dp)
                            if (profile.isLocked) Icon(
                                Icons.Rounded.Lock,
                                contentDescription = "Profil chroniony PIN-em",
                                modifier = Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp)
                                    .background(VelvetElevated, CircleShape).padding(7.dp).size(15.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (profile.isKids) Text("Dzieci", color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(23.dp), strokeWidth = 2.dp)
        OutlinedButton(onClick = { onAction(AppAction.Login) }, enabled = !state.busy, modifier = Modifier.padding(horizontal = 24.dp)) {
            Icon(Icons.Rounded.OpenInBrowser, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Wybierz profil lub wpisz PIN", color = Color.White)
        }
        Text("Na oficjalnej stronie Netflix", color = VelvetMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 7.dp))
        TextButton(onClick = { onAction(AppAction.Logout) }, modifier = Modifier.padding(bottom = 24.dp)) {
            Text("Użyj innego konta", color = VelvetMuted)
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, onSearch: () -> Unit, onAction: (AppAction) -> Unit) {
    val hero = remember(state.rows) {
        val titles = state.rows.flatMap { it.items }
        titles.firstOrNull { !it.heroUrl.isNullOrBlank() } ?: titles.firstOrNull()
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BrandWordmark(small = true)
                    state.activeProfile?.name?.takeIf(String::isNotBlank)?.let {
                        Text("Cześć, $it", color = VelvetMuted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "Szukaj filmów i seriali") }
                IconButton(onClick = { onAction(AppAction.ChangeProfile) }) {
                    ProfileAvatar(state.activeProfile, Modifier.size(32.dp), 9.dp)
                }
            }
        }
        if (state.busy) item(key = "loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
        }
        if (hero != null) item(key = "hero") {
            HeroCard(hero, Modifier.padding(horizontal = 16.dp), onAction)
            Spacer(Modifier.height(28.dp))
        }
        items(state.rows.filter { it.items.isNotEmpty() }, key = { it.id }) { row ->
            CatalogCarousel(row, onAction)
            Spacer(Modifier.height(27.dp))
        }
        if (state.rows.isEmpty()) item(key = "empty") {
            if (state.busy) CatalogSkeleton()
            else EmptyContent(
                title = "Twoje filmy czekają",
                message = "Odśwież bibliotekę, aby wczytać ofertę dla tego profilu.",
                icon = Icons.Rounded.Movie,
                modifier = Modifier.fillMaxWidth().heightIn(min = 350.dp),
                buttonLabel = "Odśwież",
                onClick = { onAction(AppAction.Refresh) },
            )
        }
    }
}

@Composable
private fun HeroCard(title: NfTitle, modifier: Modifier = Modifier, onAction: (AppAction) -> Unit) {
    Box(
        modifier.fillMaxWidth().height(425.dp).clip(RoundedCornerShape(24.dp))
            .background(VelvetSurface).clickable { onAction(AppAction.OpenTitle(title.id)) },
    ) {
        Artwork(title.heroUrl ?: title.artworkUrl, title.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .05f), Color.Transparent, Color(0xFF08090D).copy(alpha = .97f)))))
        Row(
            Modifier.padding(18.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(VelvetRed, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (title.isSeries) "SERIAL" else "FILM", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.7.sp)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp)) {
            Text(title.title, style = MaterialTheme.typography.headlineLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            TitleMetadata(title)
            if (title.synopsis.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(title.synopsis, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .8f))
            }
            Spacer(Modifier.height(17.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onAction(AppAction.Play(title.id, title.resumeSeconds)) },
                    modifier = Modifier.weight(1f).height(47.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = VelvetBackground),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (title.resumeSeconds > 0) "Kontynuuj" else "Odtwórz")
                }
                FilledTonalButton(
                    onClick = { onAction(AppAction.OpenTitle(title.id)) },
                    modifier = Modifier.weight(1f).height(47.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .16f), contentColor = Color.White),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Szczegóły")
                }
            }
        }
    }
}

@Composable
private fun CatalogCarousel(row: CatalogRow, onAction: (AppAction) -> Unit) {
    Column {
        Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 22.dp, end = 20.dp, bottom = 13.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(row.items.distinctBy { it.id }, key = { it.id }) { title ->
                TitleCard(title, Modifier.width(126.dp)) { onAction(AppAction.OpenTitle(title.id)) }
            }
        }
    }
}

@Composable
internal fun TitleCard(title: NfTitle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.69f).clip(RoundedCornerShape(10.dp)).background(VelvetSurface)) {
            Artwork(title.artworkUrl ?: title.heroUrl, title.title, Modifier.fillMaxSize())
            if (title.resumeSeconds > 0 && title.runtimeSeconds > 0) {
                LinearProgressIndicator(
                    progress = { (title.resumeSeconds.toFloat() / title.runtimeSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = VelvetRed,
                    trackColor = Color.White.copy(alpha = .25f),
                )
            }
        }
        Text(title.title, modifier = Modifier.padding(top = 8.dp, start = 1.dp, end = 1.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeading("Znajdź coś dla siebie", "Szukaj")
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(AppAction.Search(it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text("Filmy, seriale, nazwiska…", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onAction(AppAction.Search("")) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Wyczyść wyszukiwanie", modifier = Modifier.size(18.dp))
                }
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = VelvetSurface, focusedContainerColor = VelvetSurface,
                unfocusedBorderColor = VelvetBorder, focusedBorderColor = VelvetRed),
        )
        Spacer(Modifier.height(20.dp))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
        when {
            state.query.isBlank() -> EmptyContent("Na co masz ochotę?", "Wpisz tytuł filmu, serialu lub nazwisko.", Icons.Rounded.Search, Modifier.weight(1f))
            state.searchResults.isEmpty() && !state.busy -> EmptyContent("Brak wyników", "Spróbuj innego tytułu lub krótszego hasła.", Icons.Rounded.SearchOff, Modifier.weight(1f))
            else -> TitleGrid(state.searchResults, Modifier.weight(1f), onAction)
        }
    }
}

@Composable
private fun MyListScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenHeading("Zapisane na później", "Moja lista", Modifier.weight(1f))
            IconButton(onClick = { onAction(AppAction.LoadMyList) }, modifier = Modifier.padding(end = 14.dp)) {
                Icon(Icons.Rounded.Refresh, "Odśwież moją listę", tint = VelvetMuted)
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
        if (state.myList.isEmpty() && !state.busy) {
            EmptyContent("Miejsce na Twoje odkrycia", "Dodaj film lub serial do Mojej listy. Będzie tu czekał, gdy znajdziesz czas.", Icons.Rounded.BookmarkBorder, Modifier.weight(1f))
        } else TitleGrid(state.myList, Modifier.weight(1f), onAction)
    }
}

@Composable
private fun TitleGrid(titles: List<NfTitle>, modifier: Modifier = Modifier, onAction: (AppAction) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(105.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(titles.distinctBy { it.id }, key = { it.id }) { title ->
            TitleCard(title) { onAction(AppAction.OpenTitle(title.id)) }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    var confirmation by remember { mutableStateOf<AppAction?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        ScreenHeading("Wszystko po Twojemu", "Ustawienia")
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A1721), VelvetSurface)))
                .clickable { onAction(AppAction.ChangeProfile) }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(state.activeProfile, Modifier.size(58.dp), 15.dp)
            Column(Modifier.weight(1f).padding(start = 15.dp)) {
                Text(state.activeProfile?.name ?: "Profil Netflix", style = MaterialTheme.typography.titleMedium)
                Text("Zmień profil", color = VelvetMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = VelvetMuted)
        }
        SettingsSectionLabel("TWOJE KONTO")
        SettingsRow(Icons.Rounded.OpenInBrowser, "Otwórz konto Netflix", "Wybierz profil lub wpisz PIN na stronie Netflix") { onAction(AppAction.Login) }
        SettingsRow(Icons.Rounded.Refresh, "Odśwież bibliotekę", "Pobierz aktualne dane z Netflix") { onAction(AppAction.Refresh) }
        SettingsRow(Icons.Rounded.Logout, "Wyloguj się", "Zakończ sesję na tym urządzeniu") { confirmation = AppAction.Logout }
        SettingsSectionLabel("APLIKACJA")
        SettingsRow(Icons.Rounded.FactCheck, "Diagnostyka i logi", "Wszystkie błędy sesji w jednym pliku") {
            showDiagnostics = true
            onAction(AppAction.OpenDiagnostics)
        }
        SettingsRow(Icons.Rounded.DeleteOutline, "Wyczyść sesję", "Usuń zapisane dane logowania") { confirmation = AppAction.ClearSession }
        Spacer(Modifier.height(34.dp))
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandWordmark(small = true)
            Text("Wersja ${state.appVersion}", color = VelvetMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == AppAction.ClearSession) "Wyczyścić sesję?" else "Wylogować się?") },
            text = { Text("Aby ponownie oglądać, trzeba będzie zalogować się na konto Netflix.") },
            confirmButton = { TextButton(onClick = { confirmation = null; onAction(action) }) {
                Text(if (action == AppAction.ClearSession) "Wyczyść" else "Wyloguj się", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Anuluj", color = Color.White) } },
        )
    }
    if (showDiagnostics) DiagnosticsDialog(state, onAction) { showDiagnostics = false }
}

@Composable
private fun DiagnosticsDialog(state: AppUiState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostyka i logi") },
        text = {
            Column {
                Text("Zapis działa automatycznie. Plik zawiera kolejne zdarzenia i błędy, także z poprzednich uruchomień. Bez danych logowania i adresów filmów.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onAction(AppAction.ExportDiagnostics) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Udostępnij logi")
                }
                OutlinedButton(onClick = { onAction(AppAction.SaveDiagnostics) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zapisz plik")
                }
                Spacer(Modifier.height(14.dp))
                SelectionContainer {
                    Text(
                        state.diagnostics.ifBlank { "Wczytywanie podsumowania…" },
                        modifier = Modifier.heightIn(max = 245.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Gotowe") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(state.diagnostics)) }) { Text("Kopiuj opis", color = Color.White) } },
    )
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(label, color = VelvetMuted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.7.sp,
        modifier = Modifier.padding(start = 23.dp, top = 29.dp, bottom = 9.dp))
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).background(VelvetSurface, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, color = VelvetMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = VelvetMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ScreenHeading(kicker: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 22.dp, end = 20.dp, top = 24.dp, bottom = 23.dp)) {
        Text(kicker, color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
internal fun EmptyContent(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    buttonLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(VelvetSurface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = VelvetMuted, modifier = Modifier.size(31.dp))
        }
        Spacer(Modifier.height(23.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = VelvetMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).widthIn(max = 300.dp))
        if (buttonLabel != null && onClick != null) {
            Spacer(Modifier.height(22.dp))
            OutlinedButton(onClick = onClick) { Text(buttonLabel, color = Color.White) }
        }
    }
}

@Composable
internal fun Artwork(url: String?, description: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(VelvetElevated, VelvetSurface))), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Movie, contentDescription = description, tint = VelvetMuted.copy(alpha = .35f), modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
internal fun ProfileAvatar(profile: NfProfile?, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(Color(0xFFAE1A31), Color(0xFF5C1737)))), contentAlignment = Alignment.Center) {
        if (!profile?.avatarUrl.isNullOrBlank()) AsyncImage(profile?.avatarUrl, profile?.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(profile?.name?.firstOrNull()?.uppercase() ?: "V", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
internal fun TitleMetadata(title: NfTitle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (title.year.isNotBlank()) Text(title.year, color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
        if (title.maturity.isNotBlank()) Text(title.maturity, color = Color.White.copy(alpha = .83f), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.border(1.dp, Color.White.copy(alpha = .3f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        if (title.runtimeSeconds > 0 && !title.isSeries) Text(formatRuntime(title.runtimeSeconds), color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
        if (title.isSeries) Text("Serial", color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatRuntime(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return if (minutes >= 60) "${minutes / 60} godz. ${minutes % 60} min" else "$minutes min"
}

@Composable
private fun CatalogSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(.15f, .35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(Modifier.padding(20.dp)) {
        Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = alpha / 3)))
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Box(Modifier.weight(1f).height(170.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = alpha / 3))) }
        }
    }
}

@Composable
private fun PlaybackPreparingOverlay(onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .85f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.height(23.dp))
            Text("Przygotowuję odtwarzanie…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Jeszcze chwila", color = VelvetMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Anuluj", color = Color.White) }
        }
    }
}

@Composable
internal fun BrandWordmark(small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(Modifier.size(if (small) 23.dp else 29.dp))
        Spacer(Modifier.width(if (small) 8.dp else 10.dp))
        Text("VELVET", fontSize = if (small) 17.sp else 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Color.White)
    }
}

@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawPath(Path().apply {
            moveTo(.06f * w, .12f * h); lineTo(.32f * w, .12f * h); lineTo(.60f * w, .88f * h)
            lineTo(.39f * w, .88f * h); close()
        }, VelvetRed)
        drawPath(Path().apply {
            moveTo(.68f * w, .12f * h); lineTo(.94f * w, .12f * h); lineTo(.60f * w, .88f * h)
            lineTo(.39f * w, .88f * h); close()
        }, Brush.verticalGradient(listOf(Color(0xFFFF4660), VelvetRed, Color(0xFFA00420))))
    }
}
