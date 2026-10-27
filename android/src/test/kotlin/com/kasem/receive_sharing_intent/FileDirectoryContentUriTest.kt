package com.kasem.receive_sharing_intent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class FileDirectoryContentUriTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://$AUTHORITY/shared/1")
    private val content = "shared content".toByteArray()

    @Before
    fun setUp() {
        FakeProvider.displayName = "photo.jpg"
        FakeProvider.queryError = null
        Robolectric.setupContentProvider(FakeProvider::class.java, AUTHORITY)
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(content))
    }

    @Test
    fun getAbsolutePath_copiesPlainNameIntoCacheDir() {
        FakeProvider.displayName = "photo.jpg"

        val path = FileDirectory.getAbsolutePath(context, uri)

        assertEquals(File(context.cacheDir, "photo.jpg").path, path)
        assertTrue(File(path!!).readBytes().contentEquals(content))
    }

    @Test
    fun getAbsolutePath_keepsParentPathNameInsideCacheDir() {
        FakeProvider.displayName = "../evil.txt"

        val path = FileDirectory.getAbsolutePath(context, uri)

        assertEquals(File(context.cacheDir, "evil.txt").path, path)
        assertTrue(File(path!!).readBytes().contentEquals(content))
        assertFalse(File(context.cacheDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun getAbsolutePath_usesGeneratedNameForParentDirName() {
        FakeProvider.displayName = ".."

        assertCopiedToGeneratedName(FileDirectory.getAbsolutePath(context, uri))
    }

    @Test
    fun getAbsolutePath_usesGeneratedNameForInvalidPathName() {
        FakeProvider.displayName = "a\u0000b.jpg"

        assertCopiedToGeneratedName(FileDirectory.getAbsolutePath(context, uri))
    }

    @Test
    fun getAbsolutePath_usesGeneratedNameForMissingName() {
        FakeProvider.displayName = null

        assertCopiedToGeneratedName(FileDirectory.getAbsolutePath(context, uri))
    }

    @Test
    fun getReadablePath_copiesReadableFile() {
        val path = FileDirectory.getReadablePath(context, uri)

        assertEquals(File(context.cacheDir, "photo.jpg").path, path)
        assertTrue(File(path!!).readBytes().contentEquals(content))
    }

    @Test
    fun getReadablePath_usesGeneratedNameWhenNameLookupFails() {
        FakeProvider.queryError = UnsupportedOperationException("no query")

        assertCopiedToGeneratedName(FileDirectory.getReadablePath(context, uri))
    }

    @Test
    fun getReadablePath_returnsNullWhenFileIsGone() {
        assertNull(FileDirectory.getReadablePath(context, Uri.parse("content://$AUTHORITY/missing/1")))
    }

    @Test
    fun getReadablePath_returnsNullWhenStreamIsNull() {
        assertNull(FileDirectory.getReadablePath(context, Uri.parse("content://$AUTHORITY/null-stream/1")))
        assertFalse(File(context.cacheDir, "photo.jpg").exists())
    }

    @Test
    fun getReadablePath_returnsNullWhenStreamAccessIsDenied() {
        assertNull(FileDirectory.getReadablePath(context, Uri.parse("content://$AUTHORITY/denied/1")))
    }

    @Test
    fun getReadablePath_replacesExistingCacheFile() {
        File(context.cacheDir, "photo.jpg").writeText("old")

        val path = FileDirectory.getReadablePath(context, uri)

        assertEquals(File(context.cacheDir, "photo.jpg").path, path)
        assertTrue(File(path!!).readBytes().contentEquals(content))
        assertNoPartFileLeft()
    }

    @Test
    fun getReadablePath_keepsExistingCacheFileWhenCopyFails() {
        val broken = Uri.parse("content://$AUTHORITY/broken/1")
        shadowOf(context.contentResolver).registerInputStream(broken, FailingInputStream())
        File(context.cacheDir, "photo.jpg").writeText("old")

        assertNull(FileDirectory.getReadablePath(context, broken))
        assertEquals("old", File(context.cacheDir, "photo.jpg").readText())
        assertNoPartFileLeft()
    }

    @Test
    fun getReadablePath_returnsNullWhenTargetNameIsADirectory() {
        val directory = File(context.cacheDir, "photo.jpg").apply { mkdir() }

        assertNull(FileDirectory.getReadablePath(context, uri))
        assertTrue(directory.isDirectory)
        assertNoPartFileLeft()
    }

    private fun assertNoPartFileLeft() {
        assertEquals(emptyList<String>(), context.cacheDir.list()!!.filter { it.endsWith(".part") })
    }

    private fun assertCopiedToGeneratedName(path: String?) {
        val file = File(path!!)
        assertEquals(context.cacheDir.canonicalPath, file.parentFile!!.canonicalPath)
        assertTrue(file.name.startsWith("IMG_"))
        assertTrue(file.readBytes().contentEquals(content))
    }

    private class FailingInputStream : InputStream() {
        private var delivered = false

        override fun read(): Int = throw IOException("stream broken")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered) throw IOException("stream broken")
            delivered = true
            b[off] = 'x'.code.toByte()
            return 1
        }
    }

    class FakeProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queryError?.let { throw it }
            return MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf(displayName)) }
        }

        override fun getType(uri: Uri) = "image/jpeg"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (uri.pathSegments.first() == "null-stream") return null
            if (uri.pathSegments.first() == "denied") throw SecurityException("no grant: $uri")
            throw FileNotFoundException("gone: $uri")
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?) = 0

        companion object {
            var displayName: String? = null
            var queryError: RuntimeException? = null
        }
    }

    private companion object {
        const val AUTHORITY = "com.kasem.receive_sharing_intent.test"
    }
}
