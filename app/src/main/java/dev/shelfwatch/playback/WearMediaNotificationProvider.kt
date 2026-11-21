package dev.shelfwatch.playback

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.common.collect.ImmutableList
import dev.shelfwatch.R
import dev.shelfwatch.str
import dev.shelfwatch.ui.MainActivity

/**
 * Standard-Media3-Benachrichtigung + Wear-OS „Ongoing Activity“.
 * Dadurch erscheint ein Symbol auf dem Zifferblatt bzw. in den letzten Apps, und das System
 * behandelt die Wiedergabe als laufende Aktivität des Nutzers.
 */
@UnstableApi
class WearMediaNotificationProvider(private val context: Context) :
    DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(R.drawable.ic_book)
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        val result = super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
        val title = mediaSession.player.mediaMetadata.title?.toString() ?: str(R.string.audiobook)
        val touchIntent = PendingIntent.getActivity(
            context, 0, MainActivity.playerIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = Status.Builder()
            .addTemplate("#title#")
            .addPart("title", Status.TextPart(title))
            .build()
        OngoingActivity.Builder(context, DEFAULT_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_book)
            .setAnimatedIcon(R.drawable.ic_book)
            .setTouchIntent(touchIntent)
            .setStatus(status)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
            .apply(context)
        return result
    }
}
