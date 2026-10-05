package cn.music.audioworkshop.domain.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTargetTest {

    @Test
    fun appFileKeepsItsAbsolutePathAsStableKey() {
        val target = DownloadTarget.AppFile("/data/user/0/cn.music.audioworkshop/files/downloads/夜曲-周杰伦.mp3")
        assertEquals("/data/user/0/cn.music.audioworkshop/files/downloads/夜曲-周杰伦.mp3", target.stableKey)
    }

    @Test
    fun unresolvedTreeTargetEncodesTreeAndFileName() {
        val target = DownloadTarget.TreeDocument(
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            fileName = "夜曲-周杰伦.mp3",
        )
        assertEquals(
            "tree:content://com.android.externalstorage.documents/tree/primary%3AMusic|夜曲-周杰伦.mp3",
            target.stableKey,
        )
    }

    @Test
    fun createdTreeTargetSwitchesToItsDocumentUri() {
        val target = DownloadTarget.TreeDocument(
            treeUri = "content://tree/primary",
            fileName = "夜曲-周杰伦.mp3",
            documentUri = "content://tree/primary/document/primary%3AMusic%2F音频工坊%2F夜曲-周杰伦.mp3",
        )
        assertEquals(
            "doc:content://tree/primary/document/primary%3AMusic%2F音频工坊%2F夜曲-周杰伦.mp3",
            target.stableKey,
        )
    }

    @Test
    fun parsesAppFileStableKey() {
        val target = downloadTargetOf("/data/a.mp3")
        assertEquals(DownloadTarget.AppFile("/data/a.mp3"), target)
    }

    @Test
    fun parsesUnresolvedTreeStableKey() {
        val target = downloadTargetOf("tree:content://tree/primary|夜曲.mp3")
        assertEquals(
            DownloadTarget.TreeDocument(treeUri = "content://tree/primary", fileName = "夜曲.mp3"),
            target,
        )
    }

    @Test
    fun parsesCreatedDocumentStableKey() {
        val target = downloadTargetOf("doc:content://tree/primary/document/primary%3AMusic%2F音频工坊%2Fa.mp3")
        assertEquals(
            DownloadTarget.TreeDocument(
                treeUri = "",
                fileName = "a.mp3",
                documentUri = "content://tree/primary/document/primary%3AMusic%2F音频工坊%2Fa.mp3",
            ),
            target,
        )
    }

    @Test
    fun stableKeyRoundTripsThroughTheParser() {
        listOf(
            DownloadTarget.AppFile("/data/downloads/a.mp3"),
            DownloadTarget.TreeDocument(treeUri = "content://tree/x", fileName = "a.mp3"),
        ).forEach { target ->
            assertEquals(target.stableKey, downloadTargetOf(target.stableKey).stableKey)
        }
    }

    @Test
    fun treeDirectoryNameIsTheApprovedSubfolder() {
        assertEquals("音频工坊", TREE_DIRECTORY_NAME)
    }
}

