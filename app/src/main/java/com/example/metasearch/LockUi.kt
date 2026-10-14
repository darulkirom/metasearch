package com.example.metasearch

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay

/**
 * Pintu masuk aplikasi. Dipasang di MainActivity:
 *
 *   private val lock by lazy { AppLock(this) }
 *   ... lock.applyWindowFlags() di onCreate, lock.Gate { SearchScreen(vm) } di setContent,
 *   lock.onReturn() di onStart, lock.onLeave(isChangingConfigurations) di onStop.
 */
class AppLock(private val activity: FragmentActivity) {
    private val vm = ViewModelProvider(activity)[LockViewModel::class.java]
    private val store: LockStore get() = vm.store

    /** Sembunyikan tampilan di Recent + blokir screenshot, sesuai pengaturan. */
    fun applyWindowFlags() = setSecure(store.hideContent)

    private fun setSecure(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun onReturn() = vm.onReturn()

    fun onLeave(changingConfigurations: Boolean) {
        // Rotasi layar bukan "keluar dari app"
        if (!changingConfigurations) vm.onLeave()
    }

    @Composable
    fun Gate(content: @Composable () -> Unit) {
        val locked by vm.locked.collectAsState()
        var settings by rememberSaveable { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        val lockEnabled = remember(tick, locked) { store.isEnabled }

        LaunchedEffect(locked) { if (locked) settings = false }

        when {
            locked -> LockScreen(store, activity, onUnlocked = vm::unlock)
            settings -> LockSettingsScreen(
                store = store,
                activity = activity,
                onBack = {
                    settings = false
                    tick++
                },
                onHideChanged = { setSecure(it) }
            )
            else -> Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (lockEnabled) TextButton(onClick = vm::lockNow) { Text("🔒 Kunci") }
                    TextButton(onClick = { settings = true }) { Text("⚙ Kunci & privasi") }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

object Biometrics {
    private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onCancel: (String) -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onCancel(errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Batal")
            .setAllowedAuthenticators(AUTH)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

// ---------------------------------------------------------------------------------------------
// Layar buka kunci
// ---------------------------------------------------------------------------------------------

@Composable
fun LockScreen(store: LockStore, activity: FragmentActivity, onUnlocked: () -> Unit) {
    val bioOk = remember { Biometrics.available(activity) }
    // Sidik jari yang sudah tidak bisa dipakai (mis. dihapus dari HP) dilewati
    val methods = remember {
        store.enabledMethods.filter { it != LockMethod.BIOMETRIC || bioOk }
    }
    val requireAll = store.requireAll && methods.size > 1

    var step by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(methods.firstOrNull()) }
    var remaining by remember { mutableLongStateOf(store.lockoutRemainingMs()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var bioTry by remember { mutableIntStateOf(0) }

    val current: LockMethod? = if (requireAll) methods.getOrNull(step) else chosen

    LaunchedEffect(remaining > 0L) {
        while (store.lockoutRemainingMs() > 0L) {
            remaining = store.lockoutRemainingMs()
            delay(500)
        }
        remaining = 0L
    }

    fun passed() {
        error = null
        attempt++
        if (requireAll && step + 1 < methods.size) {
            step++
        } else {
            store.registerSuccess()
            onUnlocked()
        }
    }

    fun failed() {
        store.registerFailure()
        remaining = store.lockoutRemainingMs()
        error = "Salah, coba lagi"
        attempt++
        step = 0 // mode "wajib semua": salah di tengah = ulang dari awal
    }

    LaunchedEffect(current, bioTry) {
        if (current == LockMethod.BIOMETRIC && store.lockoutRemainingMs() == 0L) {
            Biometrics.prompt(
                activity,
                title = "Buka MetaSearch",
                onSuccess = { passed() },
                onCancel = { error = it }
            )
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MetaSearch terkunci", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when {
            methods.isEmpty() -> Text(
                "Metode kunci yang aktif tidak bisa dipakai di HP ini (misalnya sidik jari sudah " +
                    "dihapus). Untuk mereset, hapus data aplikasi lewat Pengaturan HP."
            )
            remaining > 0L -> Text(
                "Terlalu banyak percobaan salah. Coba lagi dalam ${(remaining + 999) / 1000} detik."
            )
            else -> {
                if (requireAll) {
                    Text(
                        "Langkah ${step + 1} dari ${methods.size}: ${current?.label}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when (current) {
                    LockMethod.PIN -> {
                        var pin by remember(attempt) { mutableStateOf("") }
                        val submit = { if (pin.length >= 4) { if (store.verifyPin(pin)) passed() else failed() } }
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                            label = { Text("PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() })
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { submit() }, enabled = pin.length >= 4) { Text("Buka") }
                    }
                    LockMethod.PATTERN -> PatternPad { seq ->
                        when {
                            seq.size < 4 -> error = "Minimal 4 titik"
                            store.verifyPattern(seq) -> passed()
                            else -> failed()
                        }
                    }
                    LockMethod.BIOMETRIC -> Button(onClick = { bioTry++ }) { Text("Pakai sidik jari") }
                    null -> {}
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // Mode "salah satu cukup": boleh pindah metode
        if (!requireAll && methods.size > 1 && remaining == 0L) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.filter { it != current }.forEach { m ->
                    TextButton(onClick = {
                        chosen = m
                        error = null
                    }) { Text("Pakai ${m.label}") }
                }
            }
        }
    }
}

/** Papan pola 3x3. Mengembalikan urutan titik (0..8) yang dilewati jari. */
@Composable
fun PatternPad(modifier: Modifier = Modifier, boxSize: Dp = 260.dp, onComplete: (List<Int>) -> Unit) {
    var selected by remember { mutableStateOf(emptyList<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val lineColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline
    val onDone by rememberUpdatedState(onComplete)

    Canvas(
        modifier
            .size(boxSize)
            .pointerInput(Unit) {
                fun cell() = size.width / 3f
                fun center(i: Int) = Offset((i % 3 + 0.5f) * cell(), (i / 3 + 0.5f) * cell())
                fun nodeAt(p: Offset): Int? =
                    (0..8).firstOrNull { (p - center(it)).getDistance() < cell() * 0.3f }

                var last = Offset.Zero
                detectDragGestures(
                    onDragStart = { p ->
                        last = p
                        finger = p
                        selected = listOfNotNull(nodeAt(p))
                    },
                    onDrag = { change, _ ->
                        val cur = change.position
                        // Cek titik-titik di sepanjang jalur supaya swipe cepat tidak melewatkan titik
                        val steps = maxOf(1, ((cur - last).getDistance() / 8f).toInt())
                        for (s in 1..steps) {
                            val pt = last + (cur - last) * (s / steps.toFloat())
                            nodeAt(pt)?.let { n -> if (n !in selected) selected = selected + n }
                        }
                        last = cur
                        finger = cur
                    },
                    onDragEnd = {
                        val result = selected
                        selected = emptyList()
                        finger = null
                        if (result.isNotEmpty()) onDone(result)
                    },
                    onDragCancel = {
                        selected = emptyList()
                        finger = null
                    }
                )
            }
    ) {
        val cell = this.size.width / 3f
        fun center(i: Int) = Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
        val stroke = 6.dp.toPx()

        for (k in 0 until selected.size - 1) {
            drawLine(lineColor, center(selected[k]), center(selected[k + 1]), strokeWidth = stroke)
        }
        val f = finger
        if (selected.isNotEmpty() && f != null) {
            drawLine(lineColor, center(selected.last()), f, strokeWidth = stroke)
        }
        for (i in 0..8) {
            val on = i in selected
            drawCircle(
                color = if (on) lineColor else idleColor,
                radius = if (on) 14.dp.toPx() else 9.dp.toPx(),
                center = center(i)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pengaturan
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockSettingsScreen(
    store: LockStore,
    activity: FragmentActivity,
    onBack: () -> Unit,
    onHideChanged: (Boolean) -> Unit
) {
    BackHandler(onBack = onBack)

    var pinOn by remember { mutableStateOf(store.hasPin) }
    var patternOn by remember { mutableStateOf(store.hasPattern) }
    var bioOn by remember { mutableStateOf(store.biometricOn) }
    var requireAll by remember { mutableStateOf(store.requireAll) }
    var autoLock by remember { mutableIntStateOf(store.autoLockSeconds) }
    var hide by remember { mutableStateOf(store.hideContent) }
    var setupPin by remember { mutableStateOf(false) }
    var setupPattern by remember { mutableStateOf(false) }
    var bioMessage by remember { mutableStateOf<String?>(null) }
    val bioAvailable = remember { Biometrics.available(activity) }
    val enabledCount = listOf(pinOn, patternOn, bioOn).count { it }

    if (setupPin) {
        PinSetupDialog { pin ->
            if (pin != null) {
                store.setPin(pin)
                pinOn = true
            }
            setupPin = false
        }
    }
    if (setupPattern) {
        PatternSetupDialog { pattern ->
            if (pattern != null) {
                store.setPattern(pattern)
                patternOn = true
            }
            setupPattern = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Kembali") }
            Text("Kunci & privasi", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        SettingRow("PIN", "4–8 angka", pinOn) { on ->
            if (on) setupPin = true else {
                store.clearPin()
                pinOn = false
            }
        }
        SettingRow("Pola", "Gambar pola di 9 titik", patternOn) { on ->
            if (on) setupPattern = true else {
                store.clearPattern()
                patternOn = false
            }
        }
        SettingRow(
            "Sidik jari",
            if (bioAvailable) "Pakai biometrik yang terdaftar di HP"
            else "Belum ada sidik jari terdaftar di HP",
            bioOn,
            enabled = bioAvailable || bioOn
        ) { on ->
            if (on) {
                bioMessage = null
                Biometrics.prompt(
                    activity,
                    title = "Aktifkan sidik jari",
                    onSuccess = {
                        store.biometricOn = true
                        bioOn = true
                    },
                    onCancel = { bioMessage = it }
                )
            } else {
                store.biometricOn = false
                bioOn = false
            }
        }
        bioMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (enabledCount >= 2) {
            SettingRow(
                "Wajib semua metode",
                "Harus lolos semua metode yang aktif, berurutan. Kalau mati, salah satu saja cukup.",
                requireAll
            ) {
                store.requireAll = it
                requireAll = it
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Kunci otomatis setelah keluar dari app", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0 to "Langsung", 30 to "30 dtk", 60 to "1 mnt", 300 to "5 mnt", 900 to "15 mnt")
                .forEach { (secs, label) ->
                    FilterChip(
                        selected = autoLock == secs,
                        onClick = {
                            autoLock = secs
                            store.autoLockSeconds = secs
                        },
                        label = { Text(label) }
                    )
                }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SettingRow(
            "Sembunyikan di Recent & blokir screenshot",
            "Tampilan app jadi kosong di daftar aplikasi terakhir, dan screenshot/rekam layar diblokir.",
            hide
        ) {
            store.hideContent = it
            hide = it
            onHideChanged(it)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Catatan: kalau lupa PIN/pola dan sidik jari tidak bisa dipakai, satu-satunya jalan " +
                "adalah hapus data aplikasi lewat Pengaturan HP (riwayat pencarian ikut terhapus).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PinSetupDialog(onDone: (String?) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val valid = a.length in 4..8 && a == b

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Atur PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = a,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) a = it },
                    label = { Text("PIN (4–8 angka)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = b,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) b = it },
                    label = { Text("Ulangi PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (b.isNotEmpty() && a != b) {
                    Text("PIN tidak sama", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(a) }, enabled = valid) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Batal") } }
    )
}

@Composable
private fun PatternSetupDialog(onDone: (List<Int>?) -> Unit) {
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var message by remember { mutableStateOf("Gambar pola (minimal 4 titik)") }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Atur pola") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                Spacer(Modifier.height(12.dp))
                PatternPad(boxSize = 240.dp) { seq ->
                    val f = first
                    when {
                        seq.size < 4 -> message = "Minimal 4 titik, coba lagi"
                        f == null -> {
                            first = seq
                            message = "Gambar lagi untuk konfirmasi"
                        }
                        f == seq -> onDone(seq)
                        else -> {
                            first = null
                            message = "Tidak sama, ulangi dari awal"
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Batal") } }
    )
}
