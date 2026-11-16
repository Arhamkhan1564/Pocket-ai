package com.pocketai.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local administrator authentication. No PIN, recovery code, or derived key is exported. */
class AuthManager(context: Context, private val wallClockMillis: () -> Long = { System.currentTimeMillis() }) {
    private val prefs = context.applicationContext.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private data class Session(val revision: Long, val authorizedUntil: Long)
    private val session = AtomicReference(Session(0L, 0L))

    val isConfigured: Boolean get() = prefs.contains("pin_hash")
    val isUnlocked: Boolean
        get() = isConfigured && SystemClock.elapsedRealtime() < session.get().authorizedUntil
    val retryAfterSeconds: Long
        get() = ((prefs.getLong("locked_until", 0L) - wallClockMillis()).coerceAtLeast(0L) + 999L) / 1000L

    @Synchronized
    fun configure(pin: String): String {
        val initialSession = session.get()
        check(!isConfigured) { "Administrator is already configured." }
        return replaceCredentials(pin, initialSession)
    }

    @Synchronized
    fun unlock(pin: String): Boolean {
        val initialSession = session.get()
        check(isConfigured) { "Create an administrator PIN first." }
        check(retryAfterSeconds == 0L) { "Please wait $retryAfterSeconds seconds before trying again." }
        if (!verify(pin, "pin")) {
            registerFailure()
            return false
        }
        prefs.edit().remove("failures").remove("locked_until").commit()
        return authorizeUnlessRevoked(initialSession)
    }

    fun requireAuthorized() {
        renewAuthorizedSession()
    }

    /** Never waits for hashing or credential I/O, including when invoked by Activity.onStop. */
    fun lock() {
        while (true) {
            val current = session.get()
            if (session.compareAndSet(current, Session(current.revision + 1L, 0L))) return
        }
    }

    @Synchronized
    fun changePin(newPin: String): String {
        val initialSession = renewAuthorizedSession()
        return replaceCredentials(newPin, initialSession)
    }

    @Synchronized
    fun recover(recoveryCode: String, newPin: String): String {
        val initialSession = session.get()
        check(isConfigured) { "Create an administrator PIN first." }
        check(retryAfterSeconds == 0L) { "Please wait $retryAfterSeconds seconds before trying again." }
        if (!verify(normalizeRecovery(recoveryCode), "recovery")) {
            registerFailure()
            throw IllegalArgumentException("Recovery code did not match.")
        }
        return replaceCredentials(newPin, initialSession)
    }

    private fun replaceCredentials(pin: String, initialSession: Session): String {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "Use a PIN containing 6–12 digits." }
        val recovery = ByteArray(16).also(random::nextBytes).joinToString("") { "%02X".format(it.toInt() and 255) }
        val algorithm = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val pinSalt = ByteArray(24).also(random::nextBytes)
        val recoverySalt = ByteArray(24).also(random::nextBytes)
        val editor = prefs.edit().clear().putString("algorithm", algorithm).putInt("iterations", ITERATIONS)
            .putString("pin_salt", encode(pinSalt)).putString("pin_hash", encode(derive(pin, pinSalt, algorithm, ITERATIONS)))
            .putString("recovery_salt", encode(recoverySalt)).putString("recovery_hash", encode(derive(recovery, recoverySalt, algorithm, ITERATIONS)))
        check(editor.commit()) { "Administrator credentials could not be saved." }
        authorizeUnlessRevoked(initialSession)
        return recovery.chunked(4).joinToString("-")
    }

    /** Renewal can race with another renewal, but a lock invalidates the captured revision. */
    private fun renewAuthorizedSession(): Session {
        val revisionAtEntry = session.get().revision
        while (true) {
            val current = session.get()
            check(isConfigured && current.revision == revisionAtEntry && SystemClock.elapsedRealtime() < current.authorizedUntil) {
                "Unlock Training Mode with the administrator PIN first."
            }
            val renewed = Session(current.revision, SystemClock.elapsedRealtime() + SESSION_DURATION)
            if (session.compareAndSet(current, renewed)) return renewed
        }
    }

    /** A PIN verified after the Activity was locked must not restore administrator access. */
    private fun authorizeUnlessRevoked(initialSession: Session): Boolean {
        while (true) {
            val current = session.get()
            if (current.revision != initialSession.revision) return false
            if (session.compareAndSet(current, Session(current.revision, SystemClock.elapsedRealtime() + SESSION_DURATION))) return true
        }
    }

    private fun verify(value: String, kind: String): Boolean {
        if (value.length > 256) return false
        return runCatching {
            val salt = Base64.decode(prefs.getString("${kind}_salt", ""), Base64.NO_WRAP)
            val expected = Base64.decode(prefs.getString("${kind}_hash", ""), Base64.NO_WRAP)
            val actual = derive(value, salt, prefs.getString("algorithm", "PBKDF2WithHmacSHA1")!!, prefs.getInt("iterations", ITERATIONS))
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    private fun registerFailure() {
        val failures = prefs.getInt("failures", 0) + 1
        val delay = if (failures >= 5) (30_000L shl (failures - 5).coerceAtMost(4)).coerceAtMost(300_000L) else 0L
        prefs.edit().putInt("failures", failures.coerceAtMost(10)).putLong("locked_until", wallClockMillis() + delay).commit()
        lock()
    }

    private fun derive(value: String, salt: ByteArray, algorithm: String, iterations: Int): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun normalizeRecovery(value: String) = value.replace("-", "").replace(" ", "").uppercase(Locale.ROOT)

    companion object {
        private const val ITERATIONS = 120_000
        private const val SESSION_DURATION = 10L * 60L * 1000L
    }
}
