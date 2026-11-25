package com.tencent.qqmusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.tencent.qqmusic.data.AudioTrack
import com.tencent.qqmusic.data.LrcParser
import com.tencent.qqmusic.data.LyricLine
import com.tencent.qqmusic.data.MediaScanner
import com.tencent.qqmusic.service.PlaybackService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0B0F)
                ) {
                    GlassPlayerScreen()
                }
            }
        }
    }
}

@Composable
fun GlassPlayerScreen() {
    val context = LocalContext.current
    val scanner = remember { MediaScanner(context) }

    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    fun hasAudioPermission(): Boolean {
        val audioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, audioPerm) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshLibrary() {
        if (hasAudioPermission()) {
            tracks = scanner.scanTracks()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLibrary()
    }

    LaunchedEffect(Unit) {
        if (hasAudioPermission()) {
            refreshLibrary()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        var playerListener: Player.Listener? = null

        controllerFuture.addListener({
            val mediaController = controllerFuture.get()
            controller = mediaController

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val idx = mediaController.currentMediaItemIndex
                    if (idx in tracks.indices) {
                        currentIndex = idx
                        lyrics = LrcParser.loadLyricsForAudio(tracks[idx].filePath)
                    }
                }
            }
            playerListener = listener
            mediaController.addListener(listener)
            isPlaying = mediaController.isPlaying
        }, MoreExecutors.directExecutor())

        onDispose {
            playerListener?.let { controller?.removeListener(it) }
            MediaController.releaseFuture(controllerFuture)
        }
    }

    LaunchedEffect(controller, isPlaying) {
        while (true) {
            val activeController = controller
            if (activeController != null) {
                positionMs = activeController.currentPosition.coerceAtLeast(0L)
                durationMs = activeController.duration.coerceAtLeast(0L)
                isPlaying = activeController.isPlaying
                val idx = activeController.currentMediaItemIndex
                if (idx in tracks.indices && idx != currentIndex) {
                    currentIndex = idx
                    lyrics = LrcParser.loadLyricsForAudio(tracks[idx].filePath)
                }
            }
            delay(300L)
        }
    }

    fun playTrackAt(index: Int) {
        if (index !in tracks.indices) return
        val activeController = controller ?: return
        currentIndex = index
        val selected = tracks[index]
        lyrics = LrcParser.loadLyricsForAudio(selected.filePath)

        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.contentUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }
        activeController.setMediaItems(mediaItems, index, 0L)
        activeController.prepare()
        activeController.play()
    }

    val activeLyricIndex = remember(lyrics, positionMs) {
        LrcParser.findActiveLineIndex(lyrics, positionMs)
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF15192B),
            Color(0xFF0A0B0F),
            Color(0xFF07080B)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "GlassPlayer",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${tracks.size} tracks · ${tracks.count { it.isSnaptube }} Snaptube",
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp
                )
            }

            IconButton(
                onClick = {
                    if (hasAudioPermission()) refreshLibrary()
                    else permissionLauncher.launch(requiredPermissions)
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Scan library",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x18FFFFFF))
                .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(22.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (lyrics.isNotEmpty() && activeLyricIndex in lyrics.indices) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Synced Lyrics",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lyrics[activeLyricIndex].text,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "Place a matching .lrc file next to your audio track for live lyrics",
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                val isSelected = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isSelected) Color(0x2B7C5CFF) else Color(0x14FFFFFF)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0x88A78BFA) else Color(0x22FFFFFF),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable { playTrackAt(index) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x2638BDF8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected && isPlaying) {
                                Icons.Rounded.GraphicEq
                            } else {
                                Icons.Rounded.MusicNote
                            },
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFF38BDF8) else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (track.isSnaptube) "${track.artist} · Snaptube" else track.artist,
                            color = if (track.isSnaptube) Color(0xFFA78BFA) else Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = formatDuration(track.durationMs),
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val currentTrack = tracks.getOrNull(currentIndex)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x1EFFFFFF))
                .border(1.dp, Color(0x36FFFFFF), RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Text(
                text = currentTrack?.title ?: "No track selected",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = currentTrack?.artist ?: "Tap any song to start playback",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            val sliderMax = if (durationMs > 0L) durationMs.toFloat() else 1f
            val sliderValue = positionMs.toFloat().coerceIn(0f, sliderMax)

            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    positionMs = newValue.toLong()
                    controller?.seekTo(newValue.toLong())
                },
                valueRange = 0f..sliderMax,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF38BDF8),
                    activeTrackColor = Color(0xFF7C5CFF),
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(positionMs),
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
                Text(
                    text = formatDuration(durationMs),
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (tracks.isNotEmpty()) {
                            val prev = if (currentIndex > 0) currentIndex - 1 else tracks.lastIndex
                            playTrackAt(prev)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(18.dp))

                IconButton(
                    onClick = {
                        val activeController = controller ?: return@IconButton
                        if (activeController.isPlaying) {
                            activeController.pause()
                        } else if (activeController.mediaItemCount > 0) {
                            activeController.play()
                        } else if (tracks.isNotEmpty()) {
                            playTrackAt(0)
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF7C5CFF))
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play or Pause",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(18.dp))

                IconButton(
                    onClick = {
                        if (tracks.isNotEmpty()) {
                            val next = if (currentIndex < tracks.lastIndex) currentIndex + 1 else 0
                            playTrackAt(next)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
