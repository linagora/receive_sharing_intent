package com.kasem.receive_sharing_intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDirectoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cacheFileFor_keepsPlainName() {
        assertEquals(File(tmp.root, "photo.jpg"), FileDirectory.cacheFileFor(tmp.root, "photo.jpg"))
    }

    @Test
    fun cacheFileFor_keepsNameStartingWithDots() {
        assertEquals(File(tmp.root, "..photo.jpg"), FileDirectory.cacheFileFor(tmp.root, "..photo.jpg"))
    }

    @Test
    fun cacheFileFor_stripsParentSegments() {
        assertEquals(File(tmp.root, "x.xml"), FileDirectory.cacheFileFor(tmp.root, "../../shared_prefs/x.xml"))
    }

    @Test
    fun cacheFileFor_stripsNestedDirectories() {
        assertEquals(File(tmp.root, "x.jpg"), FileDirectory.cacheFileFor(tmp.root, "sub/x.jpg"))
    }

    @Test
    fun cacheFileFor_rejectsCurrentDirName() {
        assertNull(FileDirectory.cacheFileFor(tmp.root, "."))
    }

    @Test
    fun cacheFileFor_rejectsParentDirName() {
        assertNull(FileDirectory.cacheFileFor(tmp.root, ".."))
    }

    @Test
    fun cacheFileFor_rejectsNameWithoutBaseName() {
        assertNull(FileDirectory.cacheFileFor(tmp.root, "/"))
        assertNull(FileDirectory.cacheFileFor(tmp.root, ""))
    }

    @Test
    fun cacheFileFor_rejectsMissingName() {
        assertNull(FileDirectory.cacheFileFor(tmp.root, null))
    }
}
