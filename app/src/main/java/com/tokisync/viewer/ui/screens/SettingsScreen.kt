package com.tokisync.viewer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tokisync.viewer.data.NovelTheme
import com.tokisync.viewer.data.ReaderMode
import com.tokisync.viewer.ui.AppViewModel
import com.tokisync.viewer.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState, canGoBack: Boolean) {
    val savedGas by vm.settings.gasId.collectAsState()
    val savedFolder by vm.settings.folderId.collectAsState()
    val savedKey by vm.settings.apiKey.collectAsState()
    val prefs by vm.settings.viewer.collectAsState()

    var gas by rememberSaveable { mutableStateOf(savedGas) }
    var folder by rememberSaveable { mutableStateOf(savedFolder) }
    var key by rememberSaveable { mutableStateOf(savedKey) }
    var showKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var usageTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val usage by produceState(0L, usageTick) {
        value = withContext(Dispatchers.IO) { vm.cacheUsageBytes() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("서버 연결")
            Text(
                "UserScript / 웹 뷰어에 넣은 값과 같은 GAS 배포 URL(또는 ID), 루트 폴더 ID, API Key를 입력하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = gas, onValueChange = { gas = it },
                label = { Text("GAS 배포 URL 또는 ID") },
                placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = folder, onValueChange = { folder = it },
                label = { Text("루트 폴더 ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("API Key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "숨김" else "보기") }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !testing && gas.isNotBlank() && folder.isNotBlank(),
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = vm.testConnection(gas, folder, key)
                            testing = false
                        }
                    },
                ) { Text(if (testing) "확인 중…" else "연결 테스트") }
                Button(
                    enabled = gas.isNotBlank() && folder.isNotBlank(),
                    onClick = { vm.saveConnection(gas, folder, key) },
                ) { Text("저장") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            SectionTitle("뷰어")
            Text("기본 보기 방식 (분류로 판단할 수 없을 때)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.defaultMode == ReaderMode.SCROLL,
                    onClick = { vm.settings.updateViewer { it.copy(defaultMode = ReaderMode.SCROLL) } },
                    label = { Text("세로 스크롤") },
                )
                FilterChip(
                    selected = prefs.defaultMode == ReaderMode.PAGE,
                    onClick = { vm.settings.updateViewer { it.copy(defaultMode = ReaderMode.PAGE) } },
                    label = { Text("페이지 넘김") },
                )
            }
            SwitchRow("페이지 모드 오른쪽→왼쪽 (일본 만화)", prefs.pageRtl) { v ->
                vm.settings.updateViewer { it.copy(pageRtl = v) }
            }
            SwitchRow("볼륨키로 넘기기", prefs.volumeKeys) { v ->
                vm.settings.updateViewer { it.copy(volumeKeys = v) }
            }
            SwitchRow("다음 화 미리 받기", prefs.preloadNext) { v ->
                vm.settings.updateViewer { it.copy(preloadNext = v) }
            }

            HorizontalDivider()
            SectionTitle("소설")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelTheme.entries.forEach { t ->
                    FilterChip(
                        selected = prefs.novelTheme == t,
                        onClick = { vm.settings.updateViewer { it.copy(novelTheme = t) } },
                        label = { Text(t.label) },
                    )
                }
            }
            Text("글자 크기 ${prefs.novelFontSp}sp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.novelFontSp.toFloat(),
                onValueChange = { v -> vm.settings.updateViewer { it.copy(novelFontSp = v.toInt()) } },
                valueRange = 12f..32f, steps = 19,
            )
            Text("줄 간격 ${"%.1f".format(prefs.novelLineHeight)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.novelLineHeight,
                onValueChange = { v -> vm.settings.updateViewer { it.copy(novelLineHeight = (v * 10).toInt() / 10f) } },
                valueRange = 1.2f..2.6f, steps = 13,
            )

            HorizontalDivider()
            SectionTitle("다운로드 · 저장공간")
            SwitchRow("Drive 직접 다운로드 (빠름, 실패 시 GAS 경유)", prefs.directDrive) { v ->
                vm.settings.updateViewer { it.copy(directDrive = v) }
            }
            Text("GAS 경유 동시 요청 수: ${prefs.downloadThreads}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.downloadThreads.toFloat(),
                onValueChange = { v -> vm.settings.updateViewer { it.copy(downloadThreads = v.toInt()) } },
                valueRange = 1f..4f, steps = 2,
            )
            Text(
                "저장 한도: ${formatBytes(prefs.cacheLimitMb.toLong() * 1024 * 1024)} (초과 시 오래된 회차부터 자동 삭제)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = prefs.cacheLimitMb.toFloat(),
                onValueChange = { v -> vm.settings.updateViewer { it.copy(cacheLimitMb = (v / 512).toInt() * 512) } },
                valueRange = 512f..20480f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("현재 사용량: ${formatBytes(usage)}", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { confirmClear = true }) { Text("전체 삭제") }
            }

            HorizontalDivider()
            SectionTitle("읽기 기록")
            OutlinedButton(
                enabled = vm.settings.isConfigured,
                onClick = { vm.syncHistory(showResult = true) },
            ) { Text("지금 Drive와 동기화") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("저장된 회차 삭제") },
            text = { Text("기기에 받아둔 모든 회차 파일을 삭제합니다. 읽기 기록은 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearDownloads()
                    usageTick++
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
