package com.sabreware.aide.core.common.prefs

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Policy, not location — both tiers share one store.
 *  - [Settings] — user intent. Exportable/syncable one day.
 *  - [UiState] — view state (what was open, how wide). Device-local; must never roam.
 *
 * Derived/refetchable data is neither: that belongs in the cache dir or a Room table.
 */
enum class Tier {
    Settings,
    UiState,
    ;

    @PrefStorageApi
    fun owns(name: String): Boolean =
        if (this == UiState) name.startsWith(UI_PREFIX) else !name.startsWith(UI_PREFIX)

    companion object {
        /** Namespacing UiState keys makes tier membership readable in the stored file — and [owns] cheap. */
        @PrefStorageApi
        const val UI_PREFIX = "ui."
    }
}

/**
 * Marks the parts of a [PrefKey] that exist for **the PreferenceStore implementation only** — the storage
 * mapping (`storageKey`/`read`/`write`) and tier ownership. These were `internal` while the port and its
 * DataStore-backed store lived in one module; the store now sits in `:data:prefs`, so the compiler can no
 * longer express "same module". Opt-in keeps the boundary explicit: feature code that reaches for the
 * storage mapping fails to compile, exactly as it did before the split.
 */
@RequiresOptIn(
    message = "PrefKey storage internals are the PreferenceStore implementation's contract, not app API. " +
        "Read preferences through PreferenceStore.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class PrefStorageApi

/**
 * A typed preference. Declaring one is the entire cost of a new preference — no interface member, no
 * repository method, no test-fake stub. Declare it next to the feature that owns it.
 *
 * Reads are total: missing, corrupt, or no-longer-valid stored values yield [default] rather than throwing.
 */
@OptIn(PrefStorageApi::class)
class PrefKey<T> @PublishedApi internal constructor(
    val name: String,
    val default: T,
    val tier: Tier,
    @property:PrefStorageApi val storageKey: Preferences.Key<*>,
    @property:PrefStorageApi val read: (Preferences) -> T?,
    @property:PrefStorageApi val write: (MutablePreferences, T) -> Unit,
) {
    init {
        require(tier.owns(name)) {
            "PrefKey '$name' is $tier but must ${if (tier == Tier.UiState) "" else "not "}" +
                "start with '${Tier.UI_PREFIX}'"
        }
    }

    override fun toString(): String = "PrefKey($name, $tier)"
}

fun boolKey(name: String, default: Boolean, tier: Tier = Tier.Settings): PrefKey<Boolean> =
    booleanPreferencesKey(name).let { k ->
        PrefKey(name, default, tier, k, { it[k] }, { prefs, v -> prefs[k] = v })
    }

fun intKey(
    name: String,
    default: Int,
    tier: Tier = Tier.Settings,
    range: IntRange? = null,
): PrefKey<Int> = intPreferencesKey(name).let { k ->
    // Clamp on read too: a value stored before the range existed must not come back out of bounds.
    fun clamp(v: Int) = range?.let { v.coerceIn(it.first, it.last) } ?: v
    PrefKey(name, clamp(default), tier, k, { p -> p[k]?.let(::clamp) }, { p, v -> p[k] = clamp(v) })
}

fun longKey(name: String, default: Long, tier: Tier = Tier.Settings): PrefKey<Long> =
    longPreferencesKey(name).let { k ->
        PrefKey(name, default, tier, k, { it[k] }, { prefs, v -> prefs[k] = v })
    }

fun floatKey(
    name: String,
    default: Float,
    tier: Tier = Tier.Settings,
    range: ClosedFloatingPointRange<Float>? = null,
): PrefKey<Float> = floatPreferencesKey(name).let { k ->
    fun clamp(v: Float) = range?.let(v::coerceIn) ?: v
    PrefKey(name, clamp(default), tier, k, { p -> p[k]?.let(::clamp) }, { p, v -> p[k] = clamp(v) })
}

fun stringKey(name: String, default: String, tier: Tier = Tier.Settings): PrefKey<String> =
    stringPreferencesKey(name).let { k ->
        PrefKey(name, default, tier, k, { it[k] }, { prefs, v -> prefs[k] = v })
    }

fun stringSetKey(
    name: String,
    default: Set<String> = emptySet(),
    tier: Tier = Tier.Settings,
): PrefKey<Set<String>> = stringSetPreferencesKey(name).let { k ->
    PrefKey(name, default, tier, k, { it[k] }, { prefs, v -> prefs[k] = v })
}

/**
 * A string preference whose absence is meaningful — `null` = "not set" / "auto", as opposed to an empty
 * string the user actually chose. Writing null REMOVES the key, so "unset" and "never set" stay the same
 * state on disk.
 */
fun nullableStringKey(name: String, tier: Tier = Tier.Settings): PrefKey<String?> =
    stringPreferencesKey(name).let { k ->
        PrefKey(name, null, tier, k, { it[k] }, { prefs, v -> if (v == null) prefs -= k else prefs[k] = v })
    }

/** [nullableStringKey] for an enum: stored as `enum.name`, unknown constants read back as null. */
inline fun <reified T : Enum<T>> nullableEnumKey(
    name: String,
    tier: Tier = Tier.Settings,
    noinline decode: (String) -> T? = { raw -> enumValues<T>().firstOrNull { it.name == raw } },
): PrefKey<T?> = stringPreferencesKey(name).let { k ->
    PrefKey(
        name = name,
        default = null,
        tier = tier,
        storageKey = k,
        read = { prefs -> prefs[k]?.let(decode) },
        write = { prefs, v -> if (v == null) prefs -= k else prefs[k] = v.name },
    )
}

/** Stored as `enum.name`; a renamed/removed constant reads back as [default]. */
inline fun <reified T : Enum<T>> enumKey(
    name: String,
    default: T,
    tier: Tier = Tier.Settings,
): PrefKey<T> = stringPreferencesKey(name).let { k ->
    PrefKey(
        name = name,
        default = default,
        tier = tier,
        storageKey = k,
        read = { prefs -> prefs[k]?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } },
        write = { prefs, v -> prefs[k] = v.name },
    )
}

/** Stored as JSON; unknown fields ignored, undecodable content falls back to [default]. */
fun <T> jsonKey(
    name: String,
    default: T,
    serializer: KSerializer<T>,
    tier: Tier = Tier.Settings,
): PrefKey<T> = stringPreferencesKey(name).let { k ->
    PrefKey(
        name = name,
        default = default,
        tier = tier,
        storageKey = k,
        read = { prefs ->
            prefs[k]?.let { raw -> runCatching { PREF_JSON.decodeFromString(serializer, raw) }.getOrNull() }
        },
        write = { prefs, v -> prefs[k] = PREF_JSON.encodeToString(serializer, v) },
    )
}

@PublishedApi
internal val PREF_JSON: Json = Json { ignoreUnknownKeys = true }
