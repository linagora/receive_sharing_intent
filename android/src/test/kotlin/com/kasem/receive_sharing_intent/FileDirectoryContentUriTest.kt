package com.kasem.receive_sharing_intent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
class FileDirectoryContentUriTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://$AUTHORITY/shared/1")
    private val content = "shared content".toByteArray()

    @Before
    fun setUp() {
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
    fun getAbsolutePath_usesGeneratedNameForMissingName() {
        FakeProvider.displayName = null

        assertCopiedToGeneratedName(FileDirectory.getAbsolutePath(context, uri))
    }

    private fun assertCopiedToGeneratedName(path: String?) {
        val file = File(path!!)
        assertEquals(context.cacheDir.canonicalPath, file.parentFile!!.canonicalPath)
        assertTrue(file.name.startsWith("IMG_"))
        assertTrue(file.readBytes().contentEquals(content))
    }

    class FakeProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            return MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf(displayName)) }
        }

        override fun getType(uri: Uri) = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?) = 0

        companion object {
            var displayName: String? = null
        }
    }

    private companion object {
        const val AUTHORITY = "com.kasem.receive_sharing_intent.test"
    }
}
