package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.net.Uri
import io.flutter.plugin.common.EventChannel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReceiveSharingIntentPluginTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val readable = Uri.parse("content://$AUTHORITY/shared/1")
    private val unreadable = Uri.parse("content://$AUTHORITY/missing/1")
    private val cachedVideo = File(context.cacheDir, "clip.mp4")
    private val events = mutableListOf<JSONArray>()
    private val plugin = ReceiveSharingIntentPlugin()

    @Before
    fun setUp() {
        FileDirectoryContentUriTest.FakeProvider.displayName = "photo.jpg"
        FileDirectoryContentUriTest.FakeProvider.queryError = null
        Robolectric.setupContentProvider(FileDirectoryContentUriTest.FakeProvider::class.java, AUTHORITY)
        shadowOf(context.contentResolver).registerInputStream(readable, ByteArrayInputStream("content".toByteArray()))

        plugin.applicationContext = context
        plugin.onListen(null, object : EventChannel.EventSink {
            override fun success(event: Any?) {
                events.add(JSONArray(event as String))
            }

            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun endOfStream() = Unit
        })
    }

    @Test
    fun send_unreadableUriWithoutText_reportsOneUnreadable() {
        plugin.onNewIntent(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, unreadable))

        val entries = events.single()
        assertEquals(1, entries.length())
        assertEquals("unreadable", entries.getJSONObject(0).getString("type"))
        assertEquals(unreadable.toString(), entries.getJSONObject(0).getString("path"))
    }

    @Test
    fun send_unreadableUriWithText_reportsUnreadableThenText() {
        plugin.onNewIntent(Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, unreadable)
                .putExtra(Intent.EXTRA_TEXT, "hi"))

        val entries = events.single()
        assertEquals(2, entries.length())
        assertEquals("unreadable", entries.getJSONObject(0).getString("type"))
        assertEquals("hi", entries.getJSONObject(1).getString("path"))
        assertEquals("text", entries.getJSONObject(1).getString("type"))
        assertEquals("text/plain", entries.getJSONObject(1).getString("mimeType"))
    }

    @Test
    fun sendMultiple_readableAndUnreadable_reportsFileThenUnreadable() {
        shareImages(readable, unreadable)

        val entries = events.single()
        assertEquals(2, entries.length())
        assertEquals("image", entries.getJSONObject(0).getString("type"))
        assertEquals("photo.jpg", entries.getJSONObject(0).getString("path").substringAfterLast('/'))
        assertEquals("unreadable", entries.getJSONObject(1).getString("type"))
    }

    @Test
    fun sendMultiple_allUnreadable_reportsEachUnreadable() {
        shareImages(unreadable, unreadable)

        val entries = events.single()
        assertEquals(2, entries.length())
        assertEquals("unreadable", entries.getJSONObject(0).getString("type"))
        assertEquals("unreadable", entries.getJSONObject(1).getString("type"))
    }

    @Test
    fun view_unreadableUri_reportsOneUnreadable() {
        plugin.onNewIntent(Intent(Intent.ACTION_VIEW).setDataAndType(unreadable, "image/jpeg"))

        val entries = events.single()
        assertEquals(1, entries.length())
        assertEquals("unreadable", entries.getJSONObject(0).getString("type"))
    }

    @Test
    fun send_textOnly_keepsSharedMimeType() {
        plugin.onNewIntent(Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_TEXT, "<b>hi</b>"))

        val entries = events.single()
        assertEquals(1, entries.length())
        assertEquals("<b>hi</b>", entries.getJSONObject(0).getString("path"))
        assertEquals("text", entries.getJSONObject(0).getString("type"))
        assertEquals("text/html", entries.getJSONObject(0).getString("mimeType"))
    }

    @Test
    @Config(sdk = [34]) // getScaledFrameAtTime needs API 27, the default test SDK is minSdk 19
    fun send_video_reportsThumbnailAndDuration() {
        val video = DataSource.toDataSource(cachedVideo.path)
        ShadowMediaMetadataRetriever.addMetadata(video, METADATA_KEY_DURATION, "1500")
        ShadowMediaMetadataRetriever.addScaledFrame(video, -1, 360, 360,
                Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888))

        shareVideo()

        val entries = events.single()
        assertEquals(1, entries.length())
        assertEquals("video", entries.getJSONObject(0).getString("type"))
        assertEquals(1500L, entries.getJSONObject(0).getLong("duration"))
        assertTrue(File(entries.getJSONObject(0).getString("thumbnail")).exists())
    }

    @Test
    fun send_undecodableVideo_reportsVideoWithoutThumbnail() {
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(cachedVideo.path),
                RuntimeException("setDataSource failed"))

        shareVideo()

        val entries = events.single()
        assertEquals(1, entries.length())
        assertEquals("video", entries.getJSONObject(0).getString("type"))
        assertEquals(cachedVideo.path, entries.getJSONObject(0).getString("path"))
        assertFalse(entries.getJSONObject(0).has("thumbnail"))
        assertFalse(entries.getJSONObject(0).has("duration"))
    }

    private fun shareImages(vararg uris: Uri) {
        plugin.onNewIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(*uris))
                .putExtra(Intent.EXTRA_MIME_TYPES, Array(uris.size) { "image/jpeg" }))
    }

    private fun shareVideo() {
        FileDirectoryContentUriTest.FakeProvider.displayName = "clip.mp4"
        plugin.onNewIntent(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, readable))
    }

    private companion object {
        const val AUTHORITY = "com.kasem.receive_sharing_intent.test"
    }
}
