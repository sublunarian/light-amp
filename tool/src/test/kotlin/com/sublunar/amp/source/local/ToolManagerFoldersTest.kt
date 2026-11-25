package com.sublunar.amp.source.local

import com.sublunar.amp.data.ToolManagerFolders
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormResponse
import com.thelightphone.toolmanager.FormSpec
import com.thelightphone.toolmanager.FormSubmission
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Tool Manager's Bandcamp Login form: how a sent login is read, and that it never stays in a file. */
class ToolManagerFoldersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var shared: File

    @Before
    fun folders() {
        val filesDir = tmp.newFolder("files")
        ToolManagerFolders.attach(filesDir)
        shared = File(filesDir, "shared").canonicalFile
    }

    private fun form(vararg answers: Pair<String, String>): String =
        Json.encodeToString(
            FormSubmission.serializer(),
            FormSubmission(
                answers.map { (id, text) ->
                    FormResponse(FormItem(id = id, label = id, hint = "", hidden = id == "password", required = true), text)
                },
            ),
        )

    @Test
    fun `a sent login is read, and every form file is deleted`() {
        val dir = File(shared, "BandcampLogin").apply { mkdirs() }
        File(dir, "form_1.json").apply { writeText(form("username" to "old", "password" to "x")); setLastModified(1_000_000) }
        File(dir, "form_2.json").apply { writeText(form("username" to " fan ", "password" to "s3cret")); setLastModified(2_000_000) }
        ToolManagerFolders.sentLogin.value = null
        ToolManagerFolders.takeSentLogin()
        val login = ToolManagerFolders.sentLogin.value!!
        assertEquals("fan", login.username)
        assertEquals("s3cret", login.password)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertFalse(login.toString().contains("s3cret"))
    }

    @Test
    fun `a form without both answers is no login`() {
        assertEquals(null, ToolManagerFolders.readLogin(form("username" to "fan", "password" to "")))
        assertEquals(null, ToolManagerFolders.readLogin(form("password" to "s3cret")))
    }

    @Test
    fun `the Tool Manager lists only the form, kept out of backups`() {
        val form = ToolManagerFolders.manifest().roots.filterIsInstance<ClientLeafNode>().single()
        assertTrue(form.spec is FormSpec)
        assertFalse(form.canBeBackedUp)
    }
}
