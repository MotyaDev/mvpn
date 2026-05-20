package com.motya.mvpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.motya.mvpn.core.XrayCore
import com.motya.mvpn.core.ConnectionState
import com.motya.mvpn.core.VpnStatus
import com.motya.mvpn.data.ProfileStore
import com.motya.mvpn.data.SubscriptionUpdater
import com.motya.mvpn.data.VlessParser
import com.motya.mvpn.data.VlessProfile
import com.motya.mvpn.service.MvpnVpnService
import com.motya.mvpn.ui.theme.MvpnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MvpnVpnService.ensureNotificationChannel(this)
        setContent { MvpnTheme { MvpnApp() } }
    }
}

class MainViewModel(private val context: Context, private val store: ProfileStore) : ViewModel() {
    private val _profiles = MutableStateFlow(store.load())
    val profiles = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow(store.loadSubscriptions())
    val subscriptions = _subscriptions.asStateFlow()

    private val _pings = MutableStateFlow<Map<String, String>>(emptyMap())
    val pings = _pings.asStateFlow()

    fun add(raw: String): Result<Unit> = runCatching {
        val profile = VlessParser.parse(raw)
        setProfiles(_profiles.value.mergeProfiles(listOf(profile)))
    }

    fun addSubscription(url: String): Result<Unit> = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Нужна http(s)-ссылка подписки" }
        val updated = (_subscriptions.value + url.trim()).distinct()
        _subscriptions.value = updated
        store.saveSubscriptions(updated)
    }

    fun refreshSubscriptions(): Result<Int> = runCatching {
        val fetched = _subscriptions.value.flatMap { SubscriptionUpdater.fetch(it) }
        setProfiles(_profiles.value.mergeProfiles(fetched))
        store.markSubscriptionsUpdated()
        fetched.size
    }

    fun shouldAutoRefreshSubscriptions(): Boolean {
        val day = 24L * 60L * 60L * 1000L
        return _subscriptions.value.isNotEmpty() && System.currentTimeMillis() - store.lastSubscriptionUpdate() > day
    }

    fun setPing(profile: VlessProfile, value: String) {
        _pings.value = _pings.value + (profile.key() to value)
    }

    fun ping(profile: VlessProfile): Result<Long> = runCatching { XrayCore.measureOutboundDelay(context, profile) }

    fun remove(profile: VlessProfile) {
        setProfiles(_profiles.value - profile)
    }

    private fun setProfiles(items: List<VlessProfile>) {
        _profiles.value = items
        store.save(items)
    }

    private fun List<VlessProfile>.mergeProfiles(newItems: List<VlessProfile>): List<VlessProfile> =
        (this + newItems).distinctBy { it.key() }

    fun VlessProfile.key(): String = "$id@$host:$port"
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(context.applicationContext, ProfileStore(context.applicationContext)) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MvpnApp() {
    val context = LocalContext.current
    val model: MainViewModel = viewModel(factory = MainViewModelFactory(context))
    val profiles by model.profiles.collectAsState()
    val subscriptions by model.subscriptions.collectAsState()
    val pings by model.pings.collectAsState()
    val vpnState by VpnStatus.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selected by remember(profiles) { mutableStateOf(profiles.firstOrNull()) }
    var dialogOpen by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<VlessProfile?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingStart?.let { startVpn(context, it) }
        pendingStart = null
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (model.shouldAutoRefreshSubscriptions()) {
            withContext(Dispatchers.IO) { model.refreshSubscriptions() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("MVPN", fontWeight = FontWeight.Black)
                        Text("VLESS + Xray-core + Material You", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialogOpen = true }, shape = CircleShape) {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(
                    connected = vpnState.state == ConnectionState.Connected,
                    title = when (vpnState.state) {
                        ConnectionState.Connected -> "Подключено"
                        ConnectionState.Connecting -> "Подключаюсь"
                        ConnectionState.Error -> "Нужен фикс"
                        ConnectionState.Disconnected -> "Отключено"
                    },
                    message = vpnState.message,
                    traffic = "↑ ${formatBytes(vpnState.uploadBytes)}  ↓ ${formatBytes(vpnState.downloadBytes)}",
                    onPower = {
                        val active = vpnState.state == ConnectionState.Connected || vpnState.state == ConnectionState.Connecting
                        if (active) stopVpn(context) else selected?.let { profile ->
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) {
                                pendingStart = profile
                                vpnPermission.launch(prepare)
                            } else {
                                startVpn(context, profile)
                            }
                        }
                    },
                )
            }


            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { model.refreshSubscriptions() }
                            snackbar.showMessage(result.fold({ "Обновлено серверов: $it" }, { it.message.orEmpty() }))
                        }
                    }, enabled = subscriptions.isNotEmpty()) {
                        Text("Обновить подписки")
                    }
                    Text("Подписок: ${subscriptions.size}", modifier = Modifier.align(Alignment.CenterVertically))
                }
            }

            if (profiles.isEmpty()) {
                item {
                    EmptyCard(onPaste = {
                        pasteVless(context)?.let { raw ->
                            model.add(raw).onFailure { error -> snackbar.showMessage(error.message.orEmpty()) }
                        } ?: snackbar.showMessage("В буфере нет vless:// ссылки")
                    })
                }
            } else {
                items(profiles, key = { it.id + it.host + it.port }) { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = profile == selected,
                        ping = pings[with(model) { profile.key() }],
                        onClick = { selected = profile },
                        onPing = {
                            model.setPing(profile, "…")
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { model.ping(profile) }
                                model.setPing(profile, result.fold({ "${it} ms" }, { "ошибка" }))
                            }
                        },
                        onDelete = { model.remove(profile) },
                    )
                }
            }
        }
    }

    if (dialogOpen) {
        AddProfileDialog(
            onDismiss = { dialogOpen = false },
            onAdd = { raw ->
                val text = raw.trim()
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    model.addSubscription(text)
                        .onSuccess {
                            dialogOpen = false
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { model.refreshSubscriptions() }
                                snackbar.showMessage(result.fold({ "Обновлено серверов: $it" }, { it.message.orEmpty() }))
                            }
                        }
                        .onFailure { snackbar.showMessage(it.message.orEmpty()) }
                } else {
                    model.add(text)
                        .onSuccess { dialogOpen = false }
                        .onFailure { snackbar.showMessage(it.message.orEmpty()) }
                }
            },
            onPaste = { pasteVless(context).orEmpty() },
        )
    }
}

@Composable
private fun StatusCard(connected: Boolean, title: String, message: String, traffic: String, onPower: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(32.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text(traffic, style = MaterialTheme.typography.labelLarge)
            }
            ElevatedButton(onClick = onPower, shape = CircleShape) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                Text(if (connected) " Stop" else " Go")
            }
        }
    }
}

@Composable
private fun EmptyCard(onPaste: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Серверов пока нет", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Добавь vless:// ссылку кнопкой снизу или вставь из буфера. UI милый, но без сервера даже капибара не телепортируется.")
            FilledTonalButton(onClick = onPaste) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Text(" Вставить из буфера")
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: VlessProfile, selected: Boolean, ping: String?, onClick: () -> Unit, onPing: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${profile.host}:${profile.port}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${profile.security.uppercase()} · ${profile.transport}${ping?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(onClick = onPing) { Text("Пинг") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Удалить") }
        }
    }
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit, onPaste: () -> String) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить VLESS / подписку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    label = { Text("vless://… или https://…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = { text = onPaste() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                    Text(" Вставить")
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(text) }) { Text("Сохранить") } },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text("Отмена") } },
    )
}


private fun startVpn(context: Context, profile: VlessProfile) {
    val intent = Intent(context, MvpnVpnService::class.java)
        .setAction(MvpnVpnService.ACTION_START)
        .putExtra(MvpnVpnService.EXTRA_VLESS, profile.raw)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpn(context: Context) {
    context.startService(Intent(context, MvpnVpnService::class.java).setAction(MvpnVpnService.ACTION_STOP))
}

private fun pasteVless(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trim().startsWith("vless://", true) }
}

private fun SnackbarHostState.showMessage(message: String) {
    kotlinx.coroutines.MainScope().launch { showSnackbar(message.ifBlank { "Что-то пошло не так" }) }
}

private fun formatBytes(value: Long): String = when {
    value > 1024L * 1024L * 1024L -> "%.1f GB".format(value / 1024f / 1024f / 1024f)
    value > 1024L * 1024L -> "%.1f MB".format(value / 1024f / 1024f)
    value > 1024L -> "%.1f KB".format(value / 1024f)
    else -> "$value B"
}
