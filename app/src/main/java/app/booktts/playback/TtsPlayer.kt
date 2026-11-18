package app.booktts.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.booktts.data.Settings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Exposes [PlaybackController] as a Media3 [Player] so the media session can show a
 * notification, lock-screen and Bluetooth/headset controls. Chapters map to playlist items.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(
    private val controller: PlaybackController,
    private val settings: Settings,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private val scope = MainScope()
    private var playlistKey: String? = null
    private var playlist: List<MediaItemData> = emptyList()

    init {
        scope.launch {
            combine(controller.state, settings.speedFlow) { _, _ -> }.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = controller.state.value
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_PREPARE,
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
            COMMAND_SET_SPEED_AND_PITCH, COMMAND_RELEASE,
        ).build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(s.playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(settings.speed))

        val book = s.book
        val content = s.content
        if (book == null || content == null || content.chapters.isEmpty()) {
            return builder.setPlaybackState(STATE_IDLE).build()
        }
        val key = "${book.id}/${content.chapters.size}"
        if (key != playlistKey) {
            playlistKey = key
            playlist = content.chapters.mapIndexed { i, ch ->
                MediaItemData.Builder("${book.id}#$i")
                    .setMediaItem(
                        MediaItem.Builder()
                            .setMediaId("${book.id}#$i")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(ch.title)
                                    .setArtist(book.title)
                                    .setAlbumTitle(book.title)
                                    .setAlbumArtist(book.author)
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
            }
        }
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(s.chapterIndex.coerceIn(0, playlist.lastIndex))
            .setContentPositionMs(0)
            .setPlaybackState(
                when {
                    s.ended -> STATE_ENDED
                    s.buffering && s.playWhenReady -> STATE_BUFFERING
                    else -> STATE_READY
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex >= 0) controller.seekToChapter(mediaItemIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        controller.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        controller.pause()
        return Futures.immediateVoidFuture()
    }
}
