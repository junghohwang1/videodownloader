package com.example.videodownloader.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.videodownloader.AppContainer
import com.example.videodownloader.appContainer
import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.data.db.DownloadStatus
import com.example.videodownloader.files.MediaRow
import com.example.videodownloader.ui.common.ConfirmDialog
import com.example.videodownloader.ui.common.EmptyState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrivateViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library

    /** null = 아직 설정값을 읽지 않음 */
    val pinSet: StateFlow<Boolean?> =
        container.settings.settings.map { it.pinSet }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    val items: StateFlow<List<DownloadEntity>> = container.downloadManager.downloads
        .map { list -> list.filter { it.isPrivate && it.status == DownloadStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setPin(pin: String) {
        viewModelScope.launch {
            container.settings.setPin(pin)
            _unlocked.value = true
        }
    }

    fun unlock(pin: String) {
        viewModelScope.launch {
            if (container.settings.verifyPin(pin)) {
                _pinError.value = null
                _unlocked.value = true
            } else {
                _pinError.value = "PIN 이 올바르지 않습니다"
            }
        }
    }

    fun lock() {
        _unlocked.value = false
        _pinError.value = null
    }

    fun moveToPublic(item: DownloadEntity) {
        viewModelScope.launch {
            runCatching { library.setPrivate(item, false) }
                .onSuccess { _messages.tryEmit("파일 탭으로 옮겼습니다") }
                .onFailure { _messages.tryEmit("실패: ${it.message}") }
        }
    }

    fun delete(item: DownloadEntity) {
        viewModelScope.launch { library.delete(item) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateScreen(vm: PrivateViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()

    // 탭을 벗어나거나 앱이 백그라운드로 가면 다시 잠근다.
    DisposableEffect(Unit) { onDispose { vm.lock() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.lock() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("비공개 폴더") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = {
                if (unlocked) IconButton(onClick = vm::lock) { Icon(Icons.Default.Lock, "잠그기") }
            },
        )
        when {
            pinSet == null -> Unit
            pinSet == false -> PinSetup(onDone = vm::setPin)
            !unlocked -> PinPad(title = "PIN 을 입력하세요", error = pinError, onComplete = vm::unlock)
            else -> PrivateList(vm)
        }
    }
}

@Composable
private fun PrivateList(vm: PrivateViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val library = context.appContainer.library
    if (items.isEmpty()) {
        EmptyState(
            Icons.Outlined.Lock,
            "비공개 파일이 없습니다",
            "다운로드할 때 '비공개 폴더에 저장'을 선택하거나, 파일 탭에서 '비공개 폴더로 이동'을 누르세요.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            var menuOpen by remember { mutableStateOf(false) }
            var confirmDelete by remember { mutableStateOf(false) }
            MediaRow(
                item = item,
                onClick = { runCatching { library.open(context, item) } },
                trailing = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("파일 탭으로 이동") }, onClick = {
                                menuOpen = false
                                vm.moveToPublic(item)
                            })
                            DropdownMenuItem(text = { Text("삭제") }, onClick = {
                                menuOpen = false
                                confirmDelete = true
                            })
                        }
                    }
                },
            )
            if (confirmDelete) {
                ConfirmDialog(
                    title = "파일 삭제",
                    message = "'${item.fileName}'을(를) 삭제할까요?",
                    confirmLabel = "삭제",
                    onConfirm = { vm.delete(item) },
                    onDismiss = { confirmDelete = false },
                )
            }
        }
    }
}

@Composable
private fun PinSetup(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    PinPad(
        title = if (first == null) "비공개 폴더에 사용할 PIN 4자리를 정하세요" else "한 번 더 입력하세요",
        error = error,
        onComplete = { pin ->
            val expected = first
            when {
                expected == null -> {
                    first = pin
                    error = null
                }
                expected == pin -> onDone(pin)
                else -> {
                    first = null
                    error = "PIN 이 일치하지 않습니다. 처음부터 다시 입력하세요."
                }
            }
        },
    )
}

@Composable
fun PinPad(title: String, error: String?, onComplete: (String) -> Unit, length: Int = 4) {
    var entered by remember(title) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(length) { i ->
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entered.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { key ->
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        when (key) {
                            "" -> Unit
                            "⌫" -> TextButton(onClick = { entered = entered.dropLast(1) }, modifier = Modifier.size(72.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, "지우기")
                            }
                            else -> FilledTonalButton(
                                onClick = {
                                    if (entered.length < length) {
                                        entered += key
                                        if (entered.length == length) {
                                            val pin = entered
                                            entered = ""
                                            onComplete(pin)
                                        }
                                    }
                                },
                                modifier = Modifier.size(72.dp),
                                shape = CircleShape,
                            ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        }
    }
}
