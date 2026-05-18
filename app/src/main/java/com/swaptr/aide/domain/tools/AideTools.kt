package com.swaptr.aide.domain.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.net.toUri
import com.swaptr.aide.data.search.DuckDuckGoSearchClient
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.search.ProviderChain
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.tools.calc.SafeMathEvaluator
import com.swaptr.aide.domain.tools.phone.ContactPickGate
import com.swaptr.aide.intent.IntentDispatcher
import com.swaptr.aide.domain.tools.results.CalculatorResult
import com.swaptr.aide.domain.tools.results.ClockResult
import com.swaptr.aide.domain.tools.results.PhoneResult
import com.swaptr.aide.domain.tools.results.TimeResult
import com.swaptr.aide.domain.tools.results.WebFetchResult
import com.swaptr.aide.domain.tools.results.WebSearchResult
import com.swaptr.aide.domain.llm.verify.runWithVerify
import com.swaptr.aide.domain.tools.verify.ContactEvidence
import com.swaptr.aide.domain.tools.verify.ContactVerifier
import com.swaptr.aide.domain.tools.verify.NextAlarmEvidence
import com.swaptr.aide.domain.tools.verify.NextAlarmVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AideTools"

private val BOTH_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)
// IME has no foreground activity to host destination intents; voice overlay does.
private val CHAT_AND_VOICE: Set<Surface> = setOf(Surface.CHAT, Surface.VOICE)
// AlertDialog / ContactPickGate host required — neither IME nor voice overlay can host.
private val CHAT_ONLY: Set<Surface> = setOf(Surface.CHAT)

private val CLOCK_ERROR_CODES = setOf("NO_CLOCK_APP", "INVALID_ARGS", "LAUNCH_FAILED")
private val PHONE_ERROR_CODES = setOf("NO_HANDLER", "INVALID_ARGS", "LAUNCH_FAILED", "USER_CANCELLED")
private val WEB_SEARCH_ERROR_CODES = setOf("SEARCH_FAILED")
private val WEB_FETCH_ERROR_CODES = setOf(
    "FETCH_FAILED", "INVALID_URL", "HTTP_4XX", "HTTP_5XX", "NETWORK", "TIMEOUT",
)

private const val FETCH_CAP = 10_000
private val CALCULATOR_ERROR_CODES = setOf(
    "INVALID_EXPRESSION", "DIVIDE_BY_ZERO", "OUT_OF_DOMAIN", "OVERFLOW",
)

class WebSearchToolset(
    private val chain: ProviderChain,
    val providerDisplayName: String,
) {

    @Volatile
    var onSearchStarted: (String) -> Unit = {}

    fun asAideTool(): AideTool = AideTool.Function(
        name = "WebSearch",
        description = "Search the web for current, factual, or time-sensitive " +
            "information. Use this when the answer needs up-to-date data the user " +
            "asked about (weather, news, prices, recent events) or any fact you " +
            "are not certain of. Returns a list of titles, snippets, and URLs. " +
            "If the snippets do not contain a direct answer, call WebFetch " +
            "on the most relevant url to read its full content.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "query" to stringProp("Plain-language search query, e.g. 'Tokyo weather today'"),
            ),
        ),
        // runBlocking is safe — handlers run on LiteRT's native thread or Ollama's IO dispatcher.
        handler = { args ->
            val query = args["query"]?.jsonPrimitive?.content.orEmpty()
            Log.i(TAG, "WebSearch called: query='$query'")
            onSearchStarted(query)
            val hits = runCatching { runBlocking { chain.search(query, max = 3) } }
                .getOrElse {
                    Log.w(TAG, "WebSearch failed", it)
                    return@Function WebSearchResult.Err(
                        "SEARCH_FAILED",
                        it.message ?: "search failed",
                    ).toEnvelope()
                }
            Log.i(TAG, "WebSearch returned ${hits.size} hits")
            val result = if (hits.isEmpty()) WebSearchResult.Empty
            else WebSearchResult.Hits(hits.map {
                WebSearchResult.Hit(it.title, it.snippet, it.url)
            })
            result.toEnvelope()
        },
        surfaces = BOTH_SURFACES,
        errorCodes = WEB_SEARCH_ERROR_CODES,
    )
}

class WebFetchToolset(
    private val client: DuckDuckGoSearchClient,
) {

    @Volatile
    var onFetchStarted: (String) -> Unit = {}

    fun asAideTool(): AideTool = AideTool.Function(
        name = "WebFetch",
        description = "Fetch a webpage and return its main text content, capped at " +
            "$FETCH_CAP chars. Use this when the user gives you a URL, or when a " +
            "WebSearch snippet is too thin to answer from.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "url" to stringProp("Full URL beginning with http:// or https://"),
            ),
        ),
        handler = { args ->
            val url = args["url"]?.jsonPrimitive?.content.orEmpty()
            Log.i(TAG, "WebFetch called: $url")
            onFetchStarted(url)
            when (val outcome = runCatching { client.fetchOutcome(url, FETCH_CAP) }
                .getOrElse {
                    Log.w(TAG, "WebFetch threw", it)
                    return@Function WebFetchResult.Err(
                        "FETCH_FAILED",
                        it.message ?: "fetch failed",
                        url,
                    ).toEnvelope()
                }) {
                is DuckDuckGoSearchClient.FetchOutcome.Text -> WebFetchResult.Page(
                    url = url,
                    text = outcome.text,
                    truncated = outcome.truncated,
                ).toEnvelope()
                is DuckDuckGoSearchClient.FetchOutcome.Empty -> WebFetchResult.Empty(
                    url = url,
                    note = outcome.note,
                ).toEnvelope()
                is DuckDuckGoSearchClient.FetchOutcome.Error -> WebFetchResult.Err(
                    code = outcome.code,
                    message = outcome.message,
                    url = url,
                ).toEnvelope()
            }
        },
        surfaces = BOTH_SURFACES,
        errorCodes = WEB_FETCH_ERROR_CODES,
    )
}

class TimeToolset {

    fun asAideTool(): AideTool = AideTool.Function(
        name = "CurrentTime",
        description = "Get the current local date and time on the user's device.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "CurrentTime called")
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            TimeResult.Now(
                iso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                human = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy 'at' h:mm a z")),
                zone = now.zone.id,
            ).toEnvelope()
        },
        surfaces = BOTH_SURFACES,
    )
}

// EXTRA_SKIP_UI is a request, not a guarantee — some OEM clock apps ignore it and still
// pop their editor. We send it anyway since Google Clock honours it.
@Singleton
class ClockToolset @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun asAideTools(dispatcher: IntentDispatcher): List<AideTool> = listOf(
        setAlarmTool(dispatcher),
        setTimerTool(dispatcher),
        showAlarmsTool(dispatcher),
        showTimersTool(dispatcher),
        dismissAlarmTool(dispatcher),
        snoozeAlarmTool(dispatcher),
        dismissTimerTool(dispatcher),
    )

    private fun setAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SetAlarm",
        description = "Set a new alarm on the user's device clock app. Hour is 24-hour " +
            "(0-23). `daysOfWeek` makes the alarm repeat on the given weekdays; omit for " +
            "a one-shot alarm. `vibrate` defaults to true. The clock app applies the " +
            "alarm silently when possible.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "hour" to intProp("Hour in 24-hour format, 0-23."),
                "minutes" to intProp("Minutes, 0-59."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label for the alarm, e.g. 'Standup'."),
                "daysOfWeek" to stringArrayProp(
                    "Repeat days as 3-letter names: 'mon','tue','wed','thu','fri','sat','sun'.",
                ),
                "vibrate" to boolProp("Vibrate on alarm; defaults true."),
            ),
        ),
        handler = { args ->
            Log.i(TAG, "SetAlarm called: $args")
            val hour = args["hour"]?.jsonPrimitive?.intOrNull
            val minutes = args["minutes"]?.jsonPrimitive?.intOrNull
            if (hour == null || hour !in 0..23) return@Function clockInvalidArgs("hour must be 0-23")
            if (minutes == null || minutes !in 0..59) return@Function clockInvalidArgs("minutes must be 0-59")
            val message = args["message"]?.jsonPrimitive?.content
            val vibrate = args["vibrate"]?.jsonPrimitive?.booleanOrNull ?: true
            val days = (args["daysOfWeek"] as? JsonArray)
                ?.let { runCatching { parseDays(it) }.getOrNull() }
            if (args["daysOfWeek"] != null && days == null) {
                return@Function clockInvalidArgs("daysOfWeek must be 3-letter names")
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (days != null && days.isNotEmpty()) {
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            runWithVerify(
                op = { dispatch(intent, dispatcher) },
                verifier = NextAlarmVerifier(context, hour, minutes),
                evidenceSerializer = { ev: NextAlarmEvidence ->
                    buildJsonObject {
                        put("trigger_epoch_ms", JsonPrimitive(ev.triggerEpochMs))
                        if (ev.label != null) put("label", JsonPrimitive(ev.label))
                    }
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun setTimerTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SetTimer",
        description = "Start a countdown timer on the user's device clock app. `lengthSeconds` " +
            "is the timer duration, 1 second to 24 hours. The clock app applies the timer " +
            "silently when possible.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "lengthSeconds" to intProp("Timer duration in seconds, 1-86400."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label for the timer, e.g. 'Tea'."),
            ),
        ),
        handler = { args ->
            Log.i(TAG, "SetTimer called: $args")
            val length = args["lengthSeconds"]?.jsonPrimitive?.intOrNull
            if (length == null || length !in 1..86_400) {
                return@Function clockInvalidArgs("lengthSeconds must be 1-86400")
            }
            val message = args["message"]?.jsonPrimitive?.content
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, length)
                if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun showAlarmsTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowAlarms",
        description = "Open the clock app's alarms list. Use when the user asks to see, " +
            "review, or manage their alarms. Cannot read alarms back into the chat — the " +
            "Android API does not expose alarm contents.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ShowAlarms called")
            dispatch(Intent(AlarmClock.ACTION_SHOW_ALARMS), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun showTimersTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowTimers",
        description = "Open the clock app's timers list. Use when the user asks to see " +
            "or manage their timers.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ShowTimers called")
            dispatch(Intent(AlarmClock.ACTION_SHOW_TIMERS), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dismissAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "DismissAlarm",
        description = "Dismiss a scheduled or firing alarm. `searchMode` selects which " +
            "alarm: 'next' = the next scheduled one, 'all' = every firing alarm, 'label' = " +
            "match by message text (provide `message`), 'time' = match by clock face " +
            "(provide `hour`, `minutes`, and `isPm` if using 12-hour).",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "searchMode" to stringProp("One of: 'next','all','label','time'."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label to match when searchMode='label'."),
                "hour" to intProp("Hour to match when searchMode='time' (12 or 24-hour)."),
                "minutes" to intProp("Minutes to match when searchMode='time'."),
                "isPm" to boolProp("Set true if hour is PM in 12-hour form; ignored for 24-hour."),
            ),
        ),
        handler = { args ->
            Log.i(TAG, "DismissAlarm called: $args")
            val mode = args["searchMode"]?.jsonPrimitive?.content
            val modeExtra = when (mode) {
                "next" -> AlarmClock.ALARM_SEARCH_MODE_NEXT
                "all" -> AlarmClock.ALARM_SEARCH_MODE_ALL
                "label" -> AlarmClock.ALARM_SEARCH_MODE_LABEL
                "time" -> AlarmClock.ALARM_SEARCH_MODE_TIME
                else -> return@Function clockInvalidArgs("searchMode must be next|all|label|time")
            }
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, modeExtra)
                if (mode == "label") {
                    val msg = args["message"]?.jsonPrimitive?.content
                    if (msg.isNullOrEmpty()) return@Function clockInvalidArgs("label mode needs message")
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                }
                if (mode == "time") {
                    val h = args["hour"]?.jsonPrimitive?.intOrNull
                    val m = args["minutes"]?.jsonPrimitive?.intOrNull
                    if (h == null || h !in 0..23) return@Function clockInvalidArgs("time mode needs hour 0-23")
                    if (m == null || m !in 0..59) return@Function clockInvalidArgs("time mode needs minutes 0-59")
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    args["isPm"]?.jsonPrimitive?.booleanOrNull?.let {
                        putExtra(AlarmClock.EXTRA_IS_PM, it)
                    }
                }
            }
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun snoozeAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SnoozeAlarm",
        description = "Snooze the currently firing alarm. Has no effect if no alarm is " +
            "actively ringing.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "SnoozeAlarm called")
            dispatch(Intent(AlarmClock.ACTION_SNOOZE_ALARM), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dismissTimerTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "DismissTimer",
        description = "Dismiss the currently firing timer.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "DismissTimer called")
            dispatch(Intent(AlarmClock.ACTION_DISMISS_TIMER), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dispatch(intent: Intent, dispatcher: IntentDispatcher): JsonObject {
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "no clock app handler for ${intent.action}")
            return ClockResult.Err(
                "NO_CLOCK_APP",
                "no installed clock app handles ${intent.action}",
            ).toEnvelope()
        }
        return when (val outcome = dispatcher.launch(intent)) {
            is IntentDispatcher.LaunchOutcome.Launched ->
                ClockResult.Launched(intent.action.orEmpty()).toEnvelope()
            is IntentDispatcher.LaunchOutcome.Failed -> {
                Log.w(TAG, "dispatcher launch failed for ${intent.action}", outcome.error)
                ClockResult.Err(
                    "LAUNCH_FAILED",
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                ).toEnvelope()
            }
        }
    }

    private fun clockInvalidArgs(reason: String): JsonObject =
        ClockResult.Err("INVALID_ARGS", reason).toEnvelope()

    private fun parseDays(arr: JsonArray): List<Int> = arr.map { el ->
        when (el.jsonPrimitive.content.lowercase().take(3)) {
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            "sun" -> Calendar.SUNDAY
            else -> throw IllegalArgumentException("bad day '${el.jsonPrimitive.content}'")
        }
    }
}

// Permission-free: no CALL_PHONE/READ_CONTACTS/READ_CALL_LOG — everything goes through
// system UI for user confirmation. PickContact blocks the handler on ContactPickGate.await.
@Singleton
class PhoneToolset @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pickGate: ContactPickGate,
) {

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
            Log.i(TAG, "Dial called: '$number'")
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
            Log.i(TAG, "SendSms called: number='$number' bodyLen=${body?.length ?: 0}")
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
                verifier = ContactVerifier(context, name),
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

class CalculatorToolset(
    private val evaluator: SafeMathEvaluator = SafeMathEvaluator(),
) {

    fun asAideTool(): AideTool = AideTool.Function(
        name = "Calculator",
        description = "Evaluate a mathematical expression. Supports the common scalar " +
            "functions (sqrt, cbrt, abs, ceil, floor, round, sin, cos, tan, asin, acos, " +
            "atan, sinh, cosh, tanh, log = log10, ln = natural log, log2, exp, pow), the " +
            "operators + - * / ^, parentheses, and decimals. Constants: pi, e. Returns " +
            "{result, formatted}. Identifiers outside the whitelist (including bare " +
            "variables like 'x') are rejected with INVALID_EXPRESSION.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "expression" to stringProp(
                    "Math expression, e.g. 'sqrt(2)' or 'sin(pi/4) + log2(8)'",
                ),
            ),
        ),
        handler = { args ->
            val expression = args["expression"]?.jsonPrimitive?.content.orEmpty()
            Log.i(TAG, "Calculator called: expression='$expression'")
            when (val outcome = evaluator.eval(expression)) {
                is SafeMathEvaluator.Outcome.Ok ->
                    CalculatorResult.Ok(outcome.value, outcome.formatted).toEnvelope()
                is SafeMathEvaluator.Outcome.Err ->
                    CalculatorResult.Err(outcome.code, outcome.message).toEnvelope()
            }
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CALCULATOR_ERROR_CODES,
    )
}

internal fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun stringArrayProp(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

internal fun objectSchema(
    requiredProps: List<Pair<String, JsonObject>>,
    optionalProps: List<Pair<String, JsonObject>> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        requiredProps.forEach { (k, v) -> put(k, v) }
        optionalProps.forEach { (k, v) -> put(k, v) }
    })
    if (requiredProps.isNotEmpty()) {
        put("required", buildJsonArray { requiredProps.forEach { (k, _) -> add(JsonPrimitive(k)) } })
    }
}

