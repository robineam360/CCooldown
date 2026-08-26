package com.robin.claudeusage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long, // epoch millis; 0 = unknown
)

/** A user paste: core credentials plus informational fields we surface in the UI. */
data class PastedToken(
    val creds: Credentials,
    val refreshExpiresAt: Long, // epoch millis; 0 = not in the pasted JSON
    val plan: String?, // subscriptionType: "pro" / "max" / "team" …
    val tier: String?, // rate-limit tier, raw: "default_5x" … (CCRM-38)
)

/** Android Keystore-backed storage for the OAuth tokens, one slot per profile. */
class CredentialStore(context: Context) {

    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // v0.5 and earlier stored the single (personal) token without a prefix. Restated as a
    // key comparison for CCRM-6 (Multi-Account), now that Profile is a value type — the
    // legacy key is a storage-format constant, so this exception outlives the enum.
    private fun k(profile: Profile, name: String): String =
        if (profile.key == Profile.LEGACY_KEY) name else "${profile.key}.$name"

    fun load(profile: Profile): Credentials? {
        val access = prefs.getString(k(profile, "accessToken"), null) ?: return null
        val refresh = prefs.getString(k(profile, "refreshToken"), null) ?: return null
        return Credentials(access, refresh, prefs.getLong(k(profile, "expiresAt"), 0L))
    }

    /**
     * stampAdded is true only when the user pastes a token; silent rotations
     * during background refresh keep the original added date and tail label.
     */
    fun save(profile: Profile, creds: Credentials, stampAdded: Boolean = false) {
        val e = prefs.edit()
            .putString(k(profile, "accessToken"), creds.accessToken)
            .putString(k(profile, "refreshToken"), creds.refreshToken)
            .putLong(k(profile, "expiresAt"), creds.expiresAt)
        if (stampAdded) {
            e.putLong(k(profile, "addedAt"), System.currentTimeMillis())
            e.putString(k(profile, "tokenTail"), creds.accessToken.takeLast(4))
        }
        e.apply()
    }

    fun addedAt(profile: Profile): Long = prefs.getLong(k(profile, "addedAt"), 0L)

    fun tokenTail(profile: Profile): String? = prefs.getString(k(profile, "tokenTail"), null)

    fun clear(profile: Profile) {
        prefs.edit()
            .remove(k(profile, "accessToken"))
            .remove(k(profile, "refreshToken"))
            .remove(k(profile, "expiresAt"))
            .remove(k(profile, "addedAt"))
            .remove(k(profile, "tokenTail"))
            .apply()
    }

    companion object {
        /**
         * Accepts either the full contents of .credentials.json (wrapper with a
         * `claudeAiOauth` key) or just the inner claudeAiOauth object. Unknown
         * fields are ignored.
         */
        fun parsePasted(text: String): PastedToken? = try {
            val root = JSONObject(sanitize(text))
            val o = root.optJSONObject("claudeAiOauth") ?: root
            val access = o.optString("accessToken")
            val refresh = o.optString("refreshToken")
            if (access.isEmpty() || refresh.isEmpty()) null
            else PastedToken(
                creds = Credentials(access, refresh, o.optLong("expiresAt", 0L)),
                refreshExpiresAt = o.optLong("refreshTokenExpiresAt", 0L),
                plan = o.optString("subscriptionType").ifEmpty { null },
                // The claudeAiOauth object is all-camelCase, so that spelling
                // first; snake_case tolerated in case the shape ever shifts.
                tier = o.optString("rateLimitTier").ifEmpty { o.optString("rate_limit_tier") }
                    .ifEmpty { null },
            )
        } catch (_: Exception) {
            null
        }

        /**
         * Tokens travel through chat apps, terminals, and clipboard sync, which
         * mangle them: curly "smart" quotes, zero-width characters and BOMs,
         * hard line-wraps inside the long token strings, prose around the JSON.
         * No field we read can contain whitespace, so stripping all of it is
         * safe and undoes line-wrap damage.
         */
        private fun sanitize(text: String): String {
            var s = text
                .replace('“', '"').replace('”', '"') // curly double quotes
                .replace('„', '"').replace('‟', '"')
                .replace('‘', '\'').replace('’', '\'') // curly single quotes
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start >= 0 && end > start) s = s.substring(start, end + 1)
            return s.filter {
                !it.isWhitespace() &&
                    it != '\uFEFF' && // BOM
                    it !in '\u200B'..'\u200D' && // zero-width space/joiners
                    it != '\u2060' // word joiner
            }
        }
    }
}
