package com.sabreware.aide.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.net.toUri
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.verify.runWithVerify
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.tools.BOTH_SURFACES
import com.sabreware.aide.core.domain.tools.CHAT_AND_VOICE
import com.sabreware.aide.core.domain.tools.CHAT_ONLY
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetScope
import com.sabreware.aide.core.domain.tools.objectSchema
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import com.sabreware.aide.app.tools.results.PhoneResult
import com.sabreware.aide.core.domain.tools.stringArrayProp
import com.sabreware.aide.core.domain.tools.stringProp
import com.sabreware.aide.platform.android.intent.IntentDispatcher
import com.sabreware.aide.platform.android.intent.IntentDispatchers
import com.sabreware.aide.app.tools.verify.ContactEvidence
import com.sabreware.aide.app.tools.verify.ContactVerifier
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AideTools"
private val PHONE_ERROR_CODES = setOf("NO_HANDLER", "INVALID_ARGS", "LAUNCH_FAILED", "USER_CANCELLED")

// Permission-free: no CALL_PHONE/READ_CONTACTS/READ_CALL_LOG — everything goes through
// system UI for user confirmation. PickContact blocks the handler on ContactPickGate.await.
class PhoneToolset(
    private val context: Context,
    private val pickGate: ContactPickGate,
    private val gate: RuntimePermissionGate,
    private val dispatchers: IntentDispatchers,
) : Toolset {

    override val category = ToolCategory.Phone
    override val displayName = "Phone"
    override val blurb = "Dial, SMS, email, and open contacts/call log."

    /** A broad, occasional area — worth a RequestToolset round trip. */
    override val onDemand = true

    override fun tools(scope: ToolsetScope): List<AideTool> =
        asAideTools(dispatchers.forSurface(scope.surface))

    fun asAideTools(dispatcher: IntentDispatcher): List<AideTool> = listOf(
        dialTool(dispatcher),
        sendSmsTool(dispatcher),
        composeEmailTool(dispatcher),
        openCallLogTool(dispatcher),
        addContactTool(dispatcher),
        editOrAddContactTool(dispatcher),
        showOrCreateContactTool(dispatcher),
        viewContactsTool(dispatcher),
        pickContactTool(),
    )

    private fun dialTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "Dial",
        description = "Open the system dialer pre-filled with a phone number. The user " +
            "still has to tap the call button. Accepts E.164 ('+15551234567') or local " +
            "formats. DTMF post-dial supported: ',' = pause, ';' = wait for user. Use " +
            "this whenever the user asks to call, ring, or phone someone.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "number" to stringProp("Phone number, e.g. '+15551234567' or '555-1234,,1234'."),
            ),
        ),
        handler = { args ->
            val number = args["number"]?.jsonPrimitive?.content?.trim().orEmpty()
            // Shape, not content: the number the user is calling is the single most identifying thing this
            // toolset touches, and it went to logcat at INFO on release builds. Two call sites in this same
            // file already log counts and lengths only — this matches them.
            Log.i(TAG, "Dial called: ${number.length} digits")
            if (number.isEmpty()) return@Function phoneInvalidArgs("number is required")
            dispatch(Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(number)}".toUri()), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun sendSmsTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SendSms",
        description = "Open the SMS composer pre-filled with a recipient number and " +
            "optional message body. The user still has to tap send. Use this when the " +
            "user wants to text or message someone.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "number" to stringProp("Recipient phone number."),
            ),
            optionalProps = listOf(
                "body" to stringProp("Pre-filled message body."),
            ),
        ),
        handler = { args ->
            val number = args["number"]?.jsonPrimitive?.content?.trim().orEmpty()
            val body = args["body"]?.jsonPrimitive?.content
            Log.i(TAG, "SendSms called: ${number.length} digits bodyLen=${body?.length ?: 0}")
            if (number.isEmpty()) return@Function phoneInvalidArgs("number is required")
            val intent = Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(number)}".toUri())
            if (!body.isNullOrEmpty()) intent.putExtra("sms_body", body)
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun composeEmailTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ComposeEmail",
        description = "Open the email composer pre-filled with recipient(s), subject, " +
            "and body. The user still has to tap send. Use this when the user wants to " +
            "email someone.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "to" to stringArrayProp("Primary recipient email addresses."),
            ),
            optionalProps = listOf(
                "cc" to stringArrayProp("CC recipient email addresses."),
                "bcc" to stringArrayProp("BCC recipient email addresses."),
                "subject" to stringProp("Subject line."),
                "body" to stringProp("Message body (plain text)."),
            ),
        ),
        handler = { args ->
            val to = (args["to"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
                ?: emptyList()
            if (to.isEmpty()) return@Function phoneInvalidArgs("to[] must have at least one address")
            val cc = (args["cc"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            val bcc = (args["bcc"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            val subject = args["subject"]?.jsonPrimitive?.content
            val body = args["body"]?.jsonPrimitive?.content
            Log.i(TAG, "ComposeEmail called: to=${to.size} cc=${cc?.size ?: 0} bcc=${bcc?.size ?: 0}")
            val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
                putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
                if (!cc.isNullOrEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())
                if (!bcc.isNullOrEmpty()) putExtra(Intent.EXTRA_BCC, bcc.toTypedArray())
                if (!subject.isNullOrEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (!body.isNullOrEmpty()) putExtra(Intent.EXTRA_TEXT, body)
            }
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun openCallLogTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "OpenCallLog",
        description = "Open the system call-log UI. Cannot read entries back into the " +
            "chat — that requires the READ_CALL_LOG permission which Aide doesn't hold. " +
            "Use when the user asks to see recent calls.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "OpenCallLog called")
            dispatch(Intent(Intent.ACTION_VIEW).apply { type = CallLog.Calls.CONTENT_TYPE }, dispatcher)
        },
        surfaces = CHAT_AND_VOICE,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun addContactTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "AddContact",
        description = "Open the contacts editor pre-filled to create a new contact. The " +
            "user still has to tap save.",
        parametersSchema = contactExtrasSchema(requireName = true),
        handler = { args ->
            Log.i(TAG, "AddContact called")
            val name = args["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            applyContactExtras(intent, args)
            // Best-effort: most contact editors require a save tap so verification times
            // out and the envelope reports `verified: false, reason: "timeout"`.
            runWithVerify(
                op = { dispatch(intent, dispatcher) },
                verifier = ContactVerifier(context, gate, name),
                evidenceSerializer = { ev: ContactEvidence ->
                    buildJsonObject { put("contact_id", JsonPrimitive(ev.contactId)) }
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun editOrAddContactTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "EditOrAddContact",
        description = "Open contacts in 'insert or edit' mode. If the contact already " +
            "exists (matched by name / phone / email) the system offers an edit; " +
            "otherwise it offers an insert. The user still has to tap save.",
        parametersSchema = contactExtrasSchema(requireName = false),
        handler = { args ->
            Log.i(TAG, "EditOrAddContact called")
            val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            }
            applyContactExtras(intent, args)
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun showOrCreateContactTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowOrCreateContact",
        description = "Open the contact matching the given phone OR email if it exists; " +
            "otherwise prompt the user to create one. Provide exactly one of `phone` or " +
            "`email`.",
        parametersSchema = objectSchema(
            requiredProps = emptyList(),
            optionalProps = listOf(
                "phone" to stringProp("Phone number to look up."),
                "email" to stringProp("Email address to look up."),
            ),
        ),
        handler = { args ->
            val phone = args["phone"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            val email = args["email"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            Log.i(TAG, "ShowOrCreateContact called: phone=${phone != null} email=${email != null}")
            if ((phone == null) == (email == null)) {
                return@Function phoneInvalidArgs("provide exactly one of phone or email")
            }
            val data = if (phone != null) "tel:${Uri.encode(phone)}".toUri()
            else "mailto:${Uri.encode(email)}".toUri()
            dispatch(Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, data), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun viewContactsTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ViewContacts",
        description = "Open the system contacts app's list view. Use when the user asks " +
            "to see all their contacts.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ViewContacts called")
            dispatch(Intent(Intent.ACTION_VIEW).apply { type = ContactsContract.Contacts.CONTENT_TYPE }, dispatcher)
        },
        surfaces = CHAT_AND_VOICE,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun pickContactTool(): AideTool = AideTool.Function(
        name = "PickContact",
        description = "Prompt the user to pick a phone number from their contacts. " +
            "Returns the chosen contact's display name + number so you can chain into " +
            "Dial / SendSms. Blocks until the user picks or cancels (up to 60s).",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            val opId = UUID.randomUUID().toString()
            Log.i(TAG, "PickContact called: opId=$opId")
            val result = pickGate.await(opId)
            if (result == null) {
                PhoneResult.Err("USER_CANCELLED", "user did not pick a contact").toEnvelope()
            } else {
                PhoneResult.Picked(result.displayName ?: "", result.number).toEnvelope()
            }
        },
        surfaces = CHAT_ONLY,
        errorCodes = PHONE_ERROR_CODES,
    )

    private fun dispatch(intent: Intent, dispatcher: IntentDispatcher): JsonObject {
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "no handler for ${intent.action} ${intent.data} ${intent.type}")
            return PhoneResult.Err(
                "NO_HANDLER",
                "no installed app handles ${intent.action}",
            ).toEnvelope()
        }
        return when (val outcome = dispatcher.launch(intent)) {
            is IntentDispatcher.LaunchOutcome.Launched ->
                PhoneResult.Launched(intent.action.orEmpty()).toEnvelope()
            is IntentDispatcher.LaunchOutcome.Failed -> {
                Log.w(TAG, "dispatcher launch failed for ${intent.action}", outcome.error)
                PhoneResult.Err(
                    "LAUNCH_FAILED",
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                ).toEnvelope()
            }
        }
    }

    private fun phoneInvalidArgs(reason: String): JsonObject =
        PhoneResult.Err("INVALID_ARGS", reason).toEnvelope()

    private fun contactExtrasSchema(requireName: Boolean): JsonObject {
        val nameProp = "name" to stringProp("Display name, e.g. 'Aman Sharma'.")
        val others = listOf(
            "phone" to stringProp("Primary phone number."),
            "phoneType" to stringProp("One of: 'mobile','home','work','fax_home','fax_work','other'."),
            "email" to stringProp("Primary email address."),
            "company" to stringProp("Organization / company name."),
            "jobTitle" to stringProp("Job title."),
            "notes" to stringProp("Free-form notes."),
            "postal" to stringProp("Postal address (single line)."),
        )
        return if (requireName) {
            objectSchema(requiredProps = listOf(nameProp), optionalProps = others)
        } else {
            objectSchema(requiredProps = emptyList(), optionalProps = listOf(nameProp) + others)
        }
    }

    private fun applyContactExtras(intent: Intent, args: JsonObject) {
        args["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.NAME, it)
        }
        args["phone"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, it)
        }
        args["phoneType"]?.jsonPrimitive?.content?.let { raw ->
            phoneTypeFor(raw)?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, it) }
        }
        args["email"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it)
        }
        args["company"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it)
        }
        args["jobTitle"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it)
        }
        args["notes"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.NOTES, it)
        }
        args["postal"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(ContactsContract.Intents.Insert.POSTAL, it)
        }
    }

    private fun phoneTypeFor(raw: String): Int? = when (raw.lowercase()) {
        "mobile", "cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        "fax_home", "home_fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME
        "fax_work", "work_fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
        "other" -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
        else -> null
    }
}
