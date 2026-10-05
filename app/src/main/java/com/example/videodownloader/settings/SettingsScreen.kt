package com.example.videodownloader.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.videodownloader.AppContainer
import com.example.videodownloader.BuildConfig
import com.example.videodownloader.data.settings.AppSettings
import com.example.videodownloader.data.settings.SearchEngine
import com.example.videodownloader.ui.common.ConfirmDialog
import com.example.videodownloader.ui.common.TextInputDialog
import com.example.videodownloader.util.AppShare
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.settings

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val downloadDir: String = container.storage.publicDir.absolutePath

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setMaxConcurrent(v: Int) = launch { repo.setMaxConcurrent(v) }
    fun setWifiOnly(v: Boolean) = launch { repo.setWifiOnly(v) }
    fun setNotify(v: Boolean) = launch { repo.setNotifyOnComplete(v) }
    fun setSaveHistory(v: Boolean) = launch { repo.setSaveHistory(v) }
    fun setHideFixedBars(v: Boolean) = launch { repo.setHideFixedBars(v) }
    fun setOfferTranslation(v: Boolean) = launch { repo.setOfferTranslation(v) }
    fun setSearchEngine(v: SearchEngine) = launch { repo.setSearchEngine(v) }

    fun clearHistory() = launch {
        container.database.historyDao().clear()
        _messages.tryEmit("방문 기록을 삭제했습니다")
    }

    fun resetPin(current: String) = launch {
        if (repo.verifyPin(current)) {
            repo.clearPin()
            _messages.tryEmit("PIN 을 초기화했습니다. 비공개 탭에서 새 PIN 을 정하세요.")
        } else {
            _messages.tryEmit("PIN 이 올바르지 않습니다")
        }
    }

    fun notify(text: String) {
        _messages.tryEmit(text)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var engineDialog by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmBrowsingData by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("설정") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("다운로드")
            var slider by remember(s.maxConcurrent) { mutableFloatStateOf(s.maxConcurrent.toFloat()) }
            ListItem(
                headlineContent = { Text("동시 다운로드 수: ${slider.toInt()}개") },
                supportingContent = {
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = { vm.setMaxConcurrent(slider.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                },
            )
            SwitchItem("Wi-Fi 에서만 다운로드", "모바일 데이터 사용 시 다운로드를 시작하지 않습니다", s.wifiOnly, vm::setWifiOnly)
            SwitchItem("완료 알림", "다운로드가 끝나면 알림을 표시합니다", s.notifyOnComplete, vm::setNotify)
            ListItem(
                headlineContent = { Text("저장 위치") },
                supportingContent = { Text(vm.downloadDir) },
            )

            Section("브라우저")
            ListItem(
                modifier = Modifier.clickable { engineDialog = true },
                headlineContent = { Text("검색 엔진") },
                supportingContent = { Text(s.searchEngine.label) },
            )
            SwitchItem(
                "페이지 하단 고정 메뉴 숨기기",
                "웹사이트가 화면 아래에 띄우는 메뉴·배너·맨 위로 버튼을 숨깁니다",
                s.hideFixedBars,
                vm::setHideFixedBars,
            )
            SwitchItem(
                "외국어 페이지 번역 제안",
                "한국어가 아닌 페이지를 열면 한국어로 번역할지 묻습니다",
                s.offerTranslation,
                vm::setOfferTranslation,
            )
            SwitchItem("방문 기록 저장", null, s.saveHistory, vm::setSaveHistory)
            ListItem(
                modifier = Modifier.clickable { confirmHistory = true },
                headlineContent = { Text("방문 기록 삭제") },
            )
            ListItem(
                modifier = Modifier.clickable { confirmBrowsingData = true },
                headlineContent = { Text("쿠키 및 캐시 삭제") },
                supportingContent = { Text("로그인 상태가 해제됩니다") },
            )

            if (s.pinSet) {
                Section("비공개 폴더")
                ListItem(
                    modifier = Modifier.clickable { pinDialog = true },
                    headlineContent = { Text("PIN 초기화") },
                    supportingContent = { Text("현재 PIN 을 확인한 뒤 초기화합니다") },
                )
            }

            Section("정보")
            ListItem(headlineContent = { Text("버전") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
            ListItem(
                modifier = Modifier.clickable { runCatching { AppShare.shareApp(context) } },
                headlineContent = { Text("친구에게 앱 공유") },
                supportingContent = { Text("다운로드 링크와 설치 방법을 카카오톡 등으로 보냅니다") },
            )
            ListItem(
                headlineContent = { Text("이용 안내") },
                supportingContent = {
                    Text(
                        "저작권이 있는 콘텐츠는 권리자의 허락 없이 다운로드하거나 배포하지 마세요. " +
                            "각 사이트의 이용약관을 따라야 하며, DRM 으로 보호된 영상은 지원하지 않습니다.",
                    )
                },
            )
        }
    }

    if (engineDialog) {
        AlertDialog(
            onDismissRequest = { engineDialog = false },
            title = { Text("검색 엔진") },
            text = {
                Column {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setSearchEngine(engine)
                                    engineDialog = false
                                },
                        ) {
                            RadioButton(selected = s.searchEngine == engine, onClick = null)
                            Text(engine.label, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { engineDialog = false }) { Text("닫기") } },
        )
    }
    if (confirmHistory) {
        ConfirmDialog("방문 기록 삭제", "모든 방문 기록을 삭제할까요?", "삭제", vm::clearHistory) { confirmHistory = false }
    }
    if (confirmBrowsingData) {
        ConfirmDialog(
            title = "쿠키 및 캐시 삭제",
            message = "브라우저의 쿠키, 저장 데이터, 캐시를 모두 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebView(context).apply {
                    clearCache(true)
                    destroy()
                }
                vm.notify("브라우저 데이터를 삭제했습니다")
            },
            onDismiss = { confirmBrowsingData = false },
        )
    }
    if (pinDialog) {
        TextInputDialog(
            title = "PIN 초기화",
            initial = "",
            label = "현재 PIN",
            confirmLabel = "초기화",
            password = true,
            onConfirm = vm::resetPin,
            onDismiss = { pinDialog = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
