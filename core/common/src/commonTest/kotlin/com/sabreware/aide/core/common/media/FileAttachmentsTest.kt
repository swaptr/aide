package com.sabreware.aide.core.common.media

import kotlin.test.Test
import kotlin.test.assertEquals

class FileAttachmentsTest {

    @Test
    fun `routes by extension`() {
        assertEquals(AttachmentKind.Image, classifyAttachment("photo.PNG"))
        assertEquals(AttachmentKind.Audio, classifyAttachment("song.mp3"))
        assertEquals(AttachmentKind.Pdf, classifyAttachment("paper.pdf"))
        assertEquals(AttachmentKind.Text, classifyAttachment("notes.md"))
        assertEquals(AttachmentKind.Text, classifyAttachment("Main.kt"))
    }

    @Test
    fun `unknown or missing extension is refused, not guessed`() {
        assertEquals(AttachmentKind.Unsupported, classifyAttachment("archive.zip"))
        assertEquals(AttachmentKind.Unsupported, classifyAttachment("sheet.xlsx"))
        assertEquals(AttachmentKind.Unsupported, classifyAttachment("noextension"))
        assertEquals(AttachmentKind.Unsupported, classifyAttachment("weird."))
    }
}
