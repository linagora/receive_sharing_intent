package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.flutter.plugin.common.EventChannel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class ReceiveSharingIntentPluginTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val readable = Uri.parse("content://$AUTHORITY/shared/1")
    private val unreadable = Uri.parse("content://$AUTHORITY/missing/1")
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
        plugin.onNewIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(readable, unreadable))
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/jpeg")))

        val entries = events.single()
        assertEquals(2, entries.length())
        assertEquals("image", entries.getJSONObject(0).getString("type"))
        assertEquals("photo.jpg", entries.getJSONObject(0).getString("path").substringAfterLast('/'))
        assertEquals("unreadable", entries.getJSONObject(1).getString("type"))
    }

    @Test
    fun sendMultiple_allUnreadable_reportsEachUnreadable() {
        plugin.onNewIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(unreadable, unreadable))
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/jpeg")))

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

    private companion object {
        const val AUTHORITY = "com.kasem.receive_sharing_intent.test"
    }
}
