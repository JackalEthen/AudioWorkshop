package cn.music.audioworkshop.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDeletionPolicyTest {
    @Test
    fun recordOnlyDeletionKeepsFiles() {
        assertEquals(
            emptyList<String>(),
            managedFilesToDelete(deleteFile = false, partPath = "song.part", finalPath = "song.m4a"),
        )
    }

    @Test
    fun fileDeletionIncludesExistingManagedPaths() {
        assertEquals(
            listOf("song.part", "song.m4a"),
            managedFilesToDelete(deleteFile = true, partPath = "song.part", finalPath = "song.m4a"),
        )
    }
}
