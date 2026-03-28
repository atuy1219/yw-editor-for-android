package com.atuy.yweditor

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atuy.yweditor.yokai.ShizukuFileGateway
import com.atuy.yweditor.yokai.Stat5
import com.atuy.yweditor.yokai.StatGroup
import com.atuy.yweditor.yokai.YokaiEntry
import com.atuy.yweditor.ui.theme.YwEditorTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val gateway = ShizukuFileGateway()

    private val requestCode = 1001
    private var shizukuGranted by mutableStateOf(false)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != this.requestCode) return@OnRequestPermissionResultListener
        runOnUiThread {
            shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        shizukuGranted = gateway.hasPermission()

        enableEdgeToEdge()
        setContent {
            YwEditorTheme {
                AppScreen(
                    modifier = Modifier.fillMaxSize(),
                    mainViewModel = vm,
                    shizukuGranted = shizukuGranted,
                    onRequestPermission = {
                        if (gateway.isShizukuRunning()) {
                            gateway.requestPermission(requestCode)
                        }
                    },
                    onRefreshPermission = {
                        shizukuGranted = gateway.hasPermission()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = viewModel(),
    shizukuGranted: Boolean,
    onRequestPermission: () -> Unit,
    onRefreshPermission: () -> Unit,
) {
    val state by mainViewModel.uiState.collectAsState()
    val mainBinPath = "/data/user/0/jp.co.level5.yws1/files/save/main.bin"
    val selected = state.entries.firstOrNull { it.slot == state.selectedSlot }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("YW Editor (Shizuku)") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefreshPermission) { Text("権限状態更新") }
                Button(onClick = onRequestPermission, enabled = !shizukuGranted) {
                    Text("Shizuku許可")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("編集対象セーブ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mainViewModel.editableSections.forEach { sectionName ->
                        val selected = state.selectedSection == sectionName
                        if (selected) {
                            Button(onClick = { mainViewModel.setSection(sectionName) }) {
                                Text(sectionName.removeSuffix(".yw"))
                            }
                        } else {
                            OutlinedButton(onClick = { mainViewModel.setSection(sectionName) }) {
                                Text(sectionName.removeSuffix(".yw"))
                            }
                        }
                    }
                }
            }

            Text(if (shizukuGranted) "Shizuku: 許可済み" else "Shizuku: 未許可", fontWeight = FontWeight.Bold)
            Text("選択中: ${state.selectedSection}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { mainViewModel.load(mainBinPath) },
                    enabled = shizukuGranted && !state.loading,
                ) {
                    Text("main.bin読込")
                }
                Button(
                    onClick = { mainViewModel.save(mainBinPath) },
                    enabled = shizukuGranted && state.loaded && !state.saving,
                ) {
                    Text("保存")
                }
            }

            Text(state.message)
            HorizontalDivider()

            if (state.entries.isEmpty()) {
                Text("読み込み後に妖怪一覧が表示されます。")
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    YokaiList(
                        entries = state.entries,
                        selectedSlot = state.selectedSlot,
                        onClick = { mainViewModel.select(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    )
                    selected?.let { entry ->
                        YokaiEditor(
                            entry = entry,
                            onLevelChange = { mainViewModel.updateLevel(entry.slot, it) },
                            onStatChange = { group, index, value ->
                                mainViewModel.updateStat(entry.slot, group, index, value)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YokaiList(
    entries: List<YokaiEntry>,
    selectedSlot: Int?,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(entries) { entry ->
            val selected = entry.slot == selectedSlot
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onClick(entry.slot) }
                    .background(if (selected) Color(0xFFD7F3FF) else Color.Transparent)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("${entry.name}  Lv.${entry.level}")
                    Text("Slot ${entry.slot} / ID: 0x${entry.id.toString(16).uppercase()}")
                }
            }
        }
    }
}

@Composable
private fun YokaiEditor(
    entry: YokaiEntry,
    onLevelChange: (Int) -> Unit,
    onStatChange: (StatGroup, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("選択中: ${entry.name}", fontWeight = FontWeight.Bold)
        NumberField(
            label = "レベル",
            value = entry.level,
            max = 255,
            onValueChange = onLevelChange,
        )

        StatEditor("IVA", entry.iva, max = 255) { i, v -> onStatChange(StatGroup.IVA, i, v) }
        StatEditor("IVB1", entry.ivb1, max = 15) { i, v -> onStatChange(StatGroup.IVB1, i, v) }
        StatEditor("IVB2", entry.ivb2, max = 15) { i, v -> onStatChange(StatGroup.IVB2, i, v) }
        StatEditor("CB", entry.cb, max = 255) { i, v -> onStatChange(StatGroup.CB, i, v) }
    }
}

@Composable
private fun StatEditor(
    title: String,
    stat: Stat5,
    max: Int,
    onValueChange: (Int, Int) -> Unit,
) {
    val labels = listOf("HP", "力", "妖", "守", "速")
    val values = stat.values()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            for (i in labels.indices) {
                NumberField(
                    label = labels[i],
                    value = values[i],
                    max = max,
                    onValueChange = { onValueChange(i, it) },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) return@OutlinedTextField

            val parsed = input.toIntOrNull() ?: return@OutlinedTextField
            val clamped = when {
                parsed < 0 -> 0
                parsed > max -> max
                else -> parsed
            }
            onValueChange(clamped)
            if (clamped.toString() != input) {
                text = clamped.toString()
            }
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    val parsed = text.toIntOrNull()
                    val fixed = when {
                        parsed == null -> 0
                        parsed < 0 -> 0
                        parsed > max -> max
                        else -> parsed
                    }
                    onValueChange(fixed)
                    text = fixed.toString()
                }
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Preview(showBackground = true)
@Composable
private fun AppPreview() {
    YwEditorTheme {
        Column(Modifier.padding(16.dp)) {
            Text("プレビュー")
            Spacer(Modifier.height(8.dp))
            Text("実機で Shizuku 接続後に動作します")
        }
    }
}