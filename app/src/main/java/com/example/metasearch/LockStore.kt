package com.example.metasearch

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LockMethod(val label: String) {
    PIN("PIN"),
    PATTERN("pola"),
    BIOMETRIC("sidik jari")
}

/**
 * Pengaturan kunci aplikasi. PIN dan pola tidak disimpan polos, tapi di-hash (PBKDF2 + salt acak).
 * Ini melindungi dari orang iseng yang pegang HP-mu, bukan dari serangan forensik di HP yang di-root.
 */
class LockStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains(KEY_PIN)
    val hasPattern: Boolean get() = prefs.contains(KEY_PATTERN)

    var biometricOn: Boolean
        get() = prefs.getBoolean(KEY_BIO, false)
        set(v) = prefs.edit().putBoolean(KEY_BIO, v).apply()

    /** true = semua metode aktif harus lolos berurutan; false = salah satu cukup. */
    var requireAll: Boolean
        get() = prefs.getBoolean(KEY_ALL, false)
        set(v) = prefs.edit().putBoolean(KEY_ALL, v).apply()

    /** Detik di luar aplikasi sebelum terkunci lagi (0 = langsung). */
    var autoLockSeconds: Int
        get() = prefs.getInt(KEY_AUTO, 30)
        set(v) = prefs.edit().putInt(KEY_AUTO, v).apply()

    /** Sembunyikan tampilan di layar Recent dan blokir screenshot (FLAG_SECURE). */
    var hideContent: Boolean
        get() = prefs.getBoolean(KEY_HIDE, true)
        set(v) = prefs.edit().putBoolean(KEY_HIDE, v).apply()

    val enabledMethods: List<LockMethod>
        get() = buildList {
            if (hasPin) add(LockMethod.PIN)
            if (hasPattern) add(LockMethod.PATTERN)
            if (biometricOn) add(LockMethod.BIOMETRIC)
        }

    val isEnabled: Boolean get() = enabledMethods.isNotEmpty()

    fun setPin(pin: String) = save(KEY_PIN, pin)
    fun clearPin() = prefs.edit().remove(KEY_PIN).apply()
    fun verifyPin(pin: String): Boolean = verify(KEY_PIN, pin)

    fun setPattern(pattern: List<Int>) = save(KEY_PATTERN, pattern.joinToString("-"))
    fun clearPattern() = prefs.edit().remove(KEY_PATTERN).apply()
    fun verifyPattern(pattern: List<Int>): Boolean = verify(KEY_PATTERN, pattern.joinToString("-"))

    // ---- Batas percobaan: tiap 5 kali salah beruntun, tunggu 30 dtk, lalu 1, 2, 4, 8, maks 10 menit ----

    fun lockoutRemainingMs(): Long =
        (prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerFailure() {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILS, fails)
        if (fails % MAX_TRIES == 0) {
            val round = fails / MAX_TRIES
            val secs = (30L shl (round - 1).coerceAtMost(5)).coerceAtMost(600L)
            edit.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + secs * 1000L)
        }
        edit.apply()
    }

    fun registerSuccess() {
        prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
    }

    // ---- hash ----

    private fun save(key: String, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val stored = Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + hash(secret, salt)
        prefs.edit().putString(key, stored).apply()
    }

    private fun verify(key: String, secret: String): Boolean {
        val stored = prefs.getString(key, null) ?: return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(secret, salt).toByteArray(), parts[1].toByteArray())
    }

    private fun hash(secret: String, salt: ByteArray): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt, 20_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        const val KEY_PIN = "pin"
        const val KEY_PATTERN = "pattern"
        const val KEY_BIO = "biometric"
        const val KEY_ALL = "require_all"
        const val KEY_AUTO = "auto_lock_seconds"
        const val KEY_HIDE = "hide_content"
        const val KEY_FAILS = "fails"
        const val KEY_LOCK_UNTIL = "lock_until"
        const val MAX_TRIES = 5
    }
}

/** Status terkunci/tidak. Hidup selama proses app hidup, jadi rotasi layar tidak mengunci ulang. */
class LockViewModel(app: Application) : AndroidViewModel(app) {
    val store = LockStore(app)

    private val _locked = MutableStateFlow(store.isEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var leftAt = 0L

    fun onLeave() {
        leftAt = SystemClock.elapsedRealtime()
    }

    fun onReturn() {
        val left = leftAt
        leftAt = 0L
        if (left == 0L || !store.isEnabled) return
        if (SystemClock.elapsedRealtime() - left >= store.autoLockSeconds * 1000L) {
            _locked.value = true
        }
    }

    fun unlock() {
        _locked.value = false
    }

    fun lockNow() {
        if (store.isEnabled) _locked.value = true
    }
}
