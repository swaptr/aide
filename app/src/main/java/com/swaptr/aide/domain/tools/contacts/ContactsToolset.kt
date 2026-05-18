package com.swaptr.aide.domain.tools.contacts

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.gates.WriteConfirmGate
import com.swaptr.aide.domain.tools.boolProp
import com.swaptr.aide.domain.tools.intProp
import com.swaptr.aide.domain.tools.objectSchema
import com.swaptr.aide.domain.tools.stringProp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactsToolset"
private val BOTH_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)
private val CHAT_ONLY: Set<Surface> = setOf(Surface.CHAT)

object ContactsErr {
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val INVALID_ARGS = "INVALID_ARGS"
    const val NOT_FOUND = "NOT_FOUND"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT"
    const val CANCELLED_BY_USER = "CANCELLED_BY_USER"
    const val IO_ERROR = "IO_ERROR"
}

private val CONTACTS_ERROR_CODES = setOf(
    ContactsErr.PERMISSION_DENIED,
    ContactsErr.INVALID_ARGS,
    ContactsErr.NOT_FOUND,
    ContactsErr.USER_CANCELLED,
    ContactsErr.CONFIRM_TIMEOUT,
    ContactsErr.CANCELLED_BY_USER,
    ContactsErr.IO_ERROR,
)

@Singleton
class ContactsToolset @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writeGate: WriteConfirmGate,
) {

    fun asAideTools(): List<AideTool> = listOf(resolveContactTool(), deleteContactTool())

    private fun resolveContactTool(): AideTool = AideTool.Function(
        name = "ResolveContact",
        description = "Look up the user's contacts by a partial display name. Returns " +
            "up to `max_results` matches with their phones (with `is_primary` hint) and " +
            "emails. Use this BEFORE PickContact whenever the user names a person. If " +
            "the result has `ambiguous: true`, ask the user to clarify rather than " +
            "guessing — from the IME you cannot launch a picker anyway.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "name" to stringProp("Display-name fragment, e.g. 'mom' or 'Alex'."),
            ),
            optionalProps = listOf(
                "max_results" to intProp("Cap; default 5, hard max 20."),
                "include_phones" to boolProp("Fetch phones (default true)."),
                "include_emails" to boolProp("Fetch emails (default true)."),
            ),
        ),
        handler = { args ->
            denyIfMissing(Manifest.permission.READ_CONTACTS)?.let { return@Function it }
            val name = args["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isEmpty()) {
                return@Function ContactsResult.Err(
                    ContactsErr.INVALID_ARGS, "name required",
                ).toEnvelope()
            }
            val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)
            val includePhones = args["include_phones"]?.jsonPrimitive?.boolean() ?: true
            val includeEmails = args["include_emails"]?.jsonPrimitive?.boolean() ?: true
            runCatching {
                resolve(name, maxResults, includePhones, includeEmails)
            }.fold(
                onSuccess = {
                    ContactsResult.Resolved(it, ambiguous = isAmbiguous(name, it)).toEnvelope()
                },
                onFailure = {
                    Log.w(TAG, "resolve failed", it)
                    ContactsResult.Err(ContactsErr.IO_ERROR, it.message ?: "io").toEnvelope()
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CONTACTS_ERROR_CODES,
    )

    private fun deleteContactTool(): AideTool = AideTool.Function(
        name = "DeleteContact",
        description = "Delete a contact aggregate (all raw contacts merged under the same " +
            "person). The user is prompted to confirm. Requires WRITE_CONTACTS.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "contact_id" to intProp("Contact id from resolve_contact."),
            ),
        ),
        handler = { args ->
            denyIfMissing(Manifest.permission.WRITE_CONTACTS)?.let { return@Function it }
            val contactId = args["contact_id"]?.jsonPrimitive?.longOrNull
                ?: return@Function ContactsResult.Err(
                    ContactsErr.INVALID_ARGS, "contact_id required",
                ).toEnvelope()
            val summary = runCatching { lookupForDelete(contactId) }.getOrNull()
                ?: return@Function ContactsResult.Err(
                    ContactsErr.NOT_FOUND, "contact $contactId not found",
                ).toEnvelope()
            confirmAndDelete(contactId, summary)
        },
        surfaces = CHAT_ONLY,
        errorCodes = CONTACTS_ERROR_CODES,
    )

    private fun resolve(
        query: String,
        maxResults: Int,
        includePhones: Boolean,
        includeEmails: Boolean,
    ): List<ContactsResult.Match> {
        val matches = mutableListOf<ContactsResult.Match>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && matches.size < maxResults) {
                matches += ContactsResult.Match(
                    contactId = cursor.getLong(0),
                    lookupKey = cursor.getString(1) ?: "",
                    displayName = cursor.getString(2) ?: "",
                    phones = emptyList(),
                    emails = emptyList(),
                    thumbnailUri = cursor.getString(3),
                )
            }
        }
        if (matches.isEmpty()) return matches

        val ids = matches.map { it.contactId }
        val phoneByContact = if (includePhones) loadPhonesBatched(ids) else emptyMap()
        val emailByContact = if (includeEmails) loadEmailsBatched(ids) else emptyMap()
        return matches.map {
            it.copy(
                phones = phoneByContact[it.contactId] ?: emptyList(),
                emails = emailByContact[it.contactId] ?: emptyList(),
            )
        }
    }

    private fun loadPhonesBatched(ids: List<Long>): Map<Long, List<ContactsResult.Phone>> {
        val out = mutableMapOf<Long, MutableList<ContactsResult.Phone>>()
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val cid = cursor.getLong(0)
                val number = cursor.getString(1) ?: continue
                out.getOrPut(cid) { mutableListOf() }.add(
                    ContactsResult.Phone(
                        number = number,
                        type = phoneTypeLabel(cursor.getInt(2)),
                        isPrimary = cursor.getInt(3) == 1,
                    ),
                )
            }
        }
        return out
    }

    private fun loadEmailsBatched(ids: List<Long>): Map<Long, List<ContactsResult.Email>> {
        val out = mutableMapOf<Long, MutableList<ContactsResult.Email>>()
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
            ),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val cid = cursor.getLong(0)
                val address = cursor.getString(1) ?: continue
                out.getOrPut(cid) { mutableListOf() }.add(
                    ContactsResult.Email(
                        address = address,
                        type = emailTypeLabel(cursor.getInt(2)),
                    ),
                )
            }
        }
        return out
    }

    private fun isAmbiguous(query: String, matches: List<ContactsResult.Match>): Boolean {
        if (matches.size <= 1) return false
        val q = query.trim()
        val exact = matches.count { it.displayName.equals(q, ignoreCase = true) }
        return exact != 1
    }

    private data class DeleteCandidate(
        val displayName: String,
        val lookupKey: String,
        val phoneSample: String?,
    )

    private fun lookupForDelete(contactId: Long): DeleteCandidate? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        var candidate: DeleteCandidate? = null
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.LOOKUP_KEY,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                candidate = DeleteCandidate(
                    displayName = cursor.getString(0) ?: "(unnamed)",
                    lookupKey = cursor.getString(1) ?: "",
                    phoneSample = null,
                )
            }
        }
        val baseCandidate = candidate ?: return null
        val phones = loadPhonesBatched(listOf(contactId))[contactId]
        return baseCandidate.copy(phoneSample = phones?.firstOrNull()?.number)
    }

    private fun confirmAndDelete(contactId: Long, candidate: DeleteCandidate): JsonObject {
        val opId = UUID.randomUUID().toString()
        val outcome = writeGate.await(
            WriteConfirmGate.Prompt(
                opId = opId,
                toolName = "DeleteContact",
                summary = "Delete contact \"${candidate.displayName}\"?",
                details = buildList {
                    add(WriteConfirmGate.KeyValue("Name", candidate.displayName))
                    candidate.phoneSample?.let {
                        add(WriteConfirmGate.KeyValue("Phone", it))
                    }
                },
                severity = WriteConfirmGate.Severity.DANGER,
            ),
        )
        when (outcome) {
            WriteConfirmGate.Result.Approved -> Unit
            is WriteConfirmGate.Result.Denied -> {
                val code = when (outcome.reason) {
                    WriteConfirmGate.Result.Reason.USER_REJECTED -> ContactsErr.USER_CANCELLED
                    WriteConfirmGate.Result.Reason.TIMEOUT -> ContactsErr.CONFIRM_TIMEOUT
                    WriteConfirmGate.Result.Reason.CANCELLED_BY_STOP -> ContactsErr.CANCELLED_BY_USER
                }
                return ContactsResult.Err(code, "delete denied").toEnvelope()
            }
        }
        if (candidate.lookupKey.isBlank()) {
            return ContactsResult.Err(ContactsErr.NOT_FOUND, "lookup key empty").toEnvelope()
        }
        // Delete via lookup URI to clean up every merged raw_contact — deleting by _ID
        // would leak the others behind a tombstone.
        val lookupUri = android.net.Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_LOOKUP_URI,
            candidate.lookupKey,
        )
        val rows = runCatching { context.contentResolver.delete(lookupUri, null, null) }
            .getOrElse {
                Log.w(TAG, "delete failed", it)
                return ContactsResult.Err(ContactsErr.IO_ERROR, it.message ?: "io").toEnvelope()
            }
        return ContactsResult.Deleted(contactId, deleted = rows > 0).toEnvelope()
    }

    private fun denyIfMissing(perm: String): JsonObject? {
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (granted) return null
        return ContactsResult.Err(
            ContactsErr.PERMISSION_DENIED,
            "missing $perm — ask the user to grant contacts access in Settings",
        ).toEnvelope()
    }

    private fun phoneTypeLabel(value: Int): String = when (value) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "fax_home"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "fax_work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
        else -> "unknown"
    }

    private fun emailTypeLabel(value: Int): String = when (value) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Email.TYPE_OTHER -> "other"
        ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "mobile"
        else -> "unknown"
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.boolean(): Boolean? = when (content) {
    "true" -> true
    "false" -> false
    else -> null
}
