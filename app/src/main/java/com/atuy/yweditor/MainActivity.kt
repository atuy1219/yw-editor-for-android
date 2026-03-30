package com.atuy.yweditor

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atuy.yweditor.ui.theme.YwEditorTheme
import com.atuy.yweditor.yokai.MainBinBackupInfo
import com.atuy.yweditor.yokai.ShizukuFileGateway
import com.atuy.yweditor.yokai.Stat5
import com.atuy.yweditor.yokai.StatGroup
import com.atuy.yweditor.yokai.YokaiAttitude
import com.atuy.yweditor.yokai.YokaiEntry
import com.atuy.yweditor.yokai.YokaiMasterLoader
import com.atuy.yweditor.yokai.YokaiStatusCalculator
import com.atuy.yweditor.yokai.yokaiClassLabel
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        vm.setMasterData(YokaiMasterLoader.load(this))
        shizukuGranted = gateway.hasPermission()
        if (!shizukuGranted && gateway.isShizukuRunning()) {
            gateway.requestPermission(requestCode)
        }

        enableEdgeToEdge()
        setContent {
            YwEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppScreen(
                        modifier = Modifier.fillMaxSize(),
                        mainViewModel = vm,
                        shizukuGranted = shizukuGranted,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@Composable
private fun AppScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = viewModel(),
    shizukuGranted: Boolean,
) {
    val state by mainViewModel.uiState.collectAsState()
    val mainBinPath = "/data/user/0/jp.co.level5.yws1/files/save/main.bin"

    BackHandler(enabled = state.currentScreen == AppScreen.Editor) {
        mainViewModel.backToStartup()
    }

    LaunchedEffect(state.currentScreen, shizukuGranted, state.startupSlotsLoaded) {
        if (
            state.currentScreen == AppScreen.Startup &&
            shizukuGranted &&
            !state.startupSlotsLoaded
        ) {
            mainViewModel.loadStartupSlots(mainBinPath)
        }
    }

    when (state.currentScreen) {
        AppScreen.Startup -> StartupScreen(
            slots = state.startupSaveSlots,
            selectedSection = state.selectedSection,
            shizukuGranted = shizukuGranted,
            loading = state.loading,
            message = state.message,
            onSelectSlot = { sectionName ->
                mainViewModel.openEditorForSection(mainBinPath, sectionName)
            },
            modifier = modifier,
        )

        AppScreen.Editor -> EditorScreen(
            state = state,
            shizukuGranted = shizukuGranted,
            onBack = mainViewModel::backToStartup,
            onSave = { mainViewModel.save(mainBinPath) },
            onCreateBackup = { name, epochMillis ->
                mainViewModel.createBackup(mainBinPath, name, epochMillis)
            },
            onRefreshBackups = { mainViewModel.refreshBackups(mainBinPath) },
            onRestoreBackup = { backupFileName ->
                mainViewModel.restoreBackup(mainBinPath, backupFileName)
            },
            onTabSelect = mainViewModel::selectTopTab,
            onYokaiCardClick = { slot ->
                mainViewModel.select(slot)
                mainViewModel.toggleYokaiExpanded(slot)
            },
            onLevelChange = { slot, value -> mainViewModel.updateLevel(slot, value) },
            onAttitudeChange = { slot, value -> mainViewModel.updateAttitude(slot, value) },
            onAttackLevelChange = { slot, value -> mainViewModel.updateAttackLevel(slot, value) },
            onTechniqueLevelChange = { slot, value -> mainViewModel.updateTechniqueLevel(slot, value) },
            onSoultimateLevelChange = { slot, value -> mainViewModel.updateSoultimateLevel(slot, value) },
            onMajimeCorrectionChange = { slot, value -> mainViewModel.updateMajimeCorrection(slot, value) },
            onStateFlagChange = { slot, mask, enabled -> mainViewModel.setStateFlag(slot, mask, enabled) },
            onStatChange = { slot, group, index, value ->
                mainViewModel.updateStat(slot, group, index, value)
            },
            onPlayHoursChange = mainViewModel::updatePlayHours,
            onPlayMinutesChange = mainViewModel::updatePlayMinutes,
            onPlayerNameChange = mainViewModel::updatePlayerName,
            modifier = modifier,
        )
    }
}

@Composable
private fun StartupScreen(
    slots: List<SaveSlotCard>,
    selectedSection: String,
    shizukuGranted: Boolean,
    loading: Boolean,
    message: String,
    onSelectSlot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("セーブデータ選択", fontWeight = FontWeight.Bold)
        Text("編集するセーブデータを選んでください")
        if (!shizukuGranted) {
            Text("Shizuku 権限が必要です。権限を許可してから再度選択してください。")
        }
        if (message.isNotBlank()) {
            Text(message)
        }

        slots.forEach { slot ->
            val isSelected = slot.sectionName == selectedSection
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = shizukuGranted && !loading && slot.hasData) {
                        onSelectSlot(slot.sectionName)
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = slot.title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(slot.subtitle)
                    if (!slot.hasData) {
                        Text("データがありません")
                    } else {
                        Text("プレイヤー名: ${slot.displayName ?: "-"}")
                        Text("プレイ時間: ${slot.playTimeText ?: "未解析"}")
                    }
                    Text("最終更新: ${formatDateTime(slot.lastUpdatedEpochMillis)}")
                    Text("妖怪数: ${slot.yokaiCount?.toString() ?: "-"}")
                    if (isSelected) {
                        Text("現在の選択")
                    }
                }
            }
        }


        if (loading) {
            Text("読み込み中...")
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorScreen(
    state: EditorUiState,
    shizukuGranted: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onCreateBackup: (String, Long) -> Unit,
    onRefreshBackups: () -> Unit,
    onRestoreBackup: (String) -> Unit,
    onTabSelect: (EditorTopTab) -> Unit,
    onYokaiCardClick: (Int) -> Unit,
    onLevelChange: (Int, Int) -> Unit,
    onAttitudeChange: (Int, Int) -> Unit,
    onAttackLevelChange: (Int, Int) -> Unit,
    onTechniqueLevelChange: (Int, Int) -> Unit,
    onSoultimateLevelChange: (Int, Int) -> Unit,
    onMajimeCorrectionChange: (Int, Int) -> Unit,
    onStateFlagChange: (Int, Int, Boolean) -> Unit,
    onStatChange: (Int, StatGroup, Int, Int) -> Unit,
    onPlayHoursChange: (Int) -> Unit,
    onPlayMinutesChange: (Int) -> Unit,
    onPlayerNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showBackupListDialog by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<MainBinBackupInfo?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("編集: ${state.selectedSection.removeSuffix(".yw")}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showCreateBackupDialog = true },
                        enabled = shizukuGranted && state.loaded && !state.backupsCreating && !state.saving,
                    ) {
                        Text("バックアップ")
                    }
                    TextButton(
                        onClick = {
                            onRefreshBackups()
                            showBackupListDialog = true
                        },
                        enabled = shizukuGranted && state.loaded && !state.backupsLoading && !state.backupsRestoring,
                    ) {
                        Text("リストア")
                    }
                    IconButton(
                        onClick = onSave,
                        enabled = shizukuGranted && state.loaded && !state.saving,
                    ) {
                        Icon(imageVector = Icons.Filled.Save, contentDescription = "保存")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            PrimaryTabRow(selectedTabIndex = state.selectedTopTab.ordinal) {
                EditorTopTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTopTab == tab,
                        onClick = { onTabSelect(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            when (state.selectedTopTab) {
                EditorTopTab.Yokai -> YokaiTabContent(
                    entries = state.entries,
                    expandedSlot = state.expandedYokaiSlot,
                    attitudes = state.attitudes,
                    onCardClick = onYokaiCardClick,
                    onLevelChange = onLevelChange,
                    onAttitudeChange = onAttitudeChange,
                    onAttackLevelChange = onAttackLevelChange,
                    onTechniqueLevelChange = onTechniqueLevelChange,
                    onSoultimateLevelChange = onSoultimateLevelChange,
                    onMajimeCorrectionChange = onMajimeCorrectionChange,
                    onStateFlagChange = onStateFlagChange,
                    onStatChange = onStatChange,
                    modifier = Modifier.fillMaxSize(),
                )

                EditorTopTab.Info -> SaveInfoEditorSection(
                    title = "セーブ情報",
                    playHours = state.playHours,
                    playMinutes = state.playMinutes,
                    playerName = state.playerName,
                    playerNameError = state.playerNameError,
                    onPlayHoursChange = onPlayHoursChange,
                    onPlayMinutesChange = onPlayMinutesChange,
                    onPlayerNameChange = onPlayerNameChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                )

                else -> PlaceholderTabContent(
                    tab = state.selectedTopTab,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showCreateBackupDialog) {
        CreateBackupDialog(
            creating = state.backupsCreating,
            onDismiss = { showCreateBackupDialog = false },
            onConfirm = { name, epochMillis ->
                onCreateBackup(name, epochMillis)
                showCreateBackupDialog = false
            },
        )
    }

    if (showBackupListDialog) {
        BackupListDialog(
            backupItems = state.backupItems,
            loading = state.backupsLoading,
            restoring = state.backupsRestoring,
            onDismiss = { showBackupListDialog = false },
            onRefresh = onRefreshBackups,
            onRequestRestore = { restoreTarget = it },
        )
    }

    val target = restoreTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("バックアップを復元") },
            text = { Text("${target.displayName} (${formatDateTime(target.backupEpochMillis)}) を復元します。現在の main.bin は上書きされます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRestoreBackup(target.fileName)
                        restoreTarget = null
                        showBackupListDialog = false
                    },
                    enabled = !state.backupsRestoring,
                ) {
                    Text("復元")
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun SaveInfoEditorSection(
    title: String,
    playHours: Int,
    playMinutes: Int,
    playerName: String,
    playerNameError: String?,
    onPlayHoursChange: (Int) -> Unit,
    onPlayMinutesChange: (Int) -> Unit,
    onPlayerNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nameBytes = playerName.toByteArray(Charsets.UTF_8).size

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("プレイ時間")
                CompactNumberField(
                    value = playHours,
                    max = 999_999,
                    modifier = Modifier.width(100.dp),
                    onValueChange = onPlayHoursChange,
                )
                Text("時間")
                CompactNumberField(
                    value = playMinutes,
                    max = 59,
                    modifier = Modifier.width(84.dp),
                    onValueChange = onPlayMinutesChange,
                )
                Text("分")
            }

            OutlinedTextField(
                value = playerName,
                onValueChange = onPlayerNameChange,
                singleLine = true,
                label = { Text("プレイヤー名") },
                isError = playerNameError != null,
                supportingText = {
                    Text(playerNameError ?: "UTF-8 ${nameBytes}/23 バイト")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CreateBackupDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(formatDateForInput(System.currentTimeMillis())) }
    var dateError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!creating) onDismiss()
        },
        title = { Text("バックアップ作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = {
                        dateText = it
                        dateError = false
                    },
                    singleLine = true,
                    label = { Text("日時(yyyyMMddHHmm)") },
                    isError = dateError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dateError) {
                    Text("日時は yyyyMMddHHmm 形式で入力してください")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseDateFromInput(dateText)
                    if (parsed == null) {
                        dateError = true
                        return@TextButton
                    }
                    onConfirm(name, parsed)
                },
                enabled = !creating,
            ) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun BackupListDialog(
    backupItems: List<MainBinBackupInfo>,
    loading: Boolean,
    restoring: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRequestRestore: (MainBinBackupInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("バックアップ一覧") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (backupItems.isEmpty()) {
                    Text(if (loading) "読み込み中..." else "バックアップはありません")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(backupItems, key = { it.fileName }) { backup ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !restoring) { onRequestRestore(backup) },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                ) {
                                    Text(backup.displayName, fontWeight = FontWeight.Medium)
                                    Text(formatDateTime(backup.backupEpochMillis))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !loading && !restoring) {
                Text("再読込")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

private fun formatDateTime(epochMillis: Long?): String {
    if (epochMillis == null) return "未取得"
    val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    return formatter.format(Date(epochMillis))
}

private fun formatDateForInput(epochMillis: Long): String {
    val formatter = SimpleDateFormat("yyyyMMddHHmm", Locale.JAPAN)
    return formatter.format(Date(epochMillis))
}

private fun parseDateFromInput(value: String): Long? {
    if (!Regex("^\\d{12}$").matches(value)) return null
    val formatter = SimpleDateFormat("yyyyMMddHHmm", Locale.JAPAN)
    formatter.isLenient = false
    return runCatching { formatter.parse(value)?.time }.getOrNull()
}

@Composable
private fun PlaceholderTabContent(
    tab: EditorTopTab,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text("${tab.label}は未実装です")
    }
}

@Composable
private fun YokaiTabContent(
    entries: List<YokaiEntry>,
    expandedSlot: Int?,
    attitudes: List<YokaiAttitude>,
    onCardClick: (Int) -> Unit,
    onLevelChange: (Int, Int) -> Unit,
    onAttitudeChange: (Int, Int) -> Unit,
    onAttackLevelChange: (Int, Int) -> Unit,
    onTechniqueLevelChange: (Int, Int) -> Unit,
    onSoultimateLevelChange: (Int, Int) -> Unit,
    onMajimeCorrectionChange: (Int, Int) -> Unit,
    onStateFlagChange: (Int, Int, Boolean) -> Unit,
    onStatChange: (Int, StatGroup, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    val filtered = entries.filter {
        searchText.isBlank() ||
            it.name.contains(searchText, ignoreCase = true) ||
            it.slot.toString().contains(searchText)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            singleLine = true,
            label = { Text("妖怪検索") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (entries.isEmpty()) {
            Text("セーブが未読込です。起動画面からセーブを選択してください。")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.slot }) { entry ->
                val expanded = expandedSlot == entry.slot
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCardClick(entry.slot) },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(entry.name, fontWeight = FontWeight.Bold)
                                Text("${yokaiClassLabel(entry.yokaiClass)} / Slot ${entry.slot}")
                            }
                            Text("Lv.${entry.level}")
                        }

                        if (expanded) {
                            HorizontalDivider()
                            YokaiStatusEditorPanel(
                                entry = entry,
                                attitudes = attitudes,
                                onLevelChange = { onLevelChange(entry.slot, it) },
                                onAttitudeChange = { onAttitudeChange(entry.slot, it) },
                                onAttackLevelChange = { onAttackLevelChange(entry.slot, it) },
                                onTechniqueLevelChange = { onTechniqueLevelChange(entry.slot, it) },
                                onSoultimateLevelChange = { onSoultimateLevelChange(entry.slot, it) },
                                onMajimeCorrectionChange = { onMajimeCorrectionChange(entry.slot, it) },
                                onStateFlagChange = { mask, enabled -> onStateFlagChange(entry.slot, mask, enabled) },
                                onStatChange = { group, index, value ->
                                    onStatChange(entry.slot, group, index, value)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ZERO_STAT = listOf(0, 0, 0, 0, 0)
private val STATUS_LABEL_WIDTH = 44.dp
private val STATUS_CELL_WIDTH = 52.dp

@Composable
private fun YokaiStatusEditorPanel(
    entry: YokaiEntry,
    attitudes: List<YokaiAttitude>,
    onLevelChange: (Int) -> Unit,
    onAttitudeChange: (Int) -> Unit,
    onAttackLevelChange: (Int) -> Unit,
    onTechniqueLevelChange: (Int) -> Unit,
    onSoultimateLevelChange: (Int) -> Unit,
    onMajimeCorrectionChange: (Int) -> Unit,
    onStateFlagChange: (Int, Boolean) -> Unit,
    onStatChange: (StatGroup, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val finalStatus = YokaiStatusCalculator.calculate(entry)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusHeadRow(
            name = entry.name,
            classLabel = yokaiClassLabel(entry.yokaiClass),
            level = entry.level,
            attitudes = attitudes,
            selectedAttitude = entry.attitudeId,
            onLevelChange = onLevelChange,
            onAttitudeChange = onAttitudeChange,
        )
        StatusHeaderRow()
        StatusReadOnlyRow(label = "BS", values = entry.baseStats?.values() ?: ZERO_STAT)
        StatusEditableRow(label = "IVA", stat = entry.iva, max = 255, onValueChange = { i, v -> onStatChange(StatGroup.IVA, i, v) })
        StatusEditableRow(label = "IVB1", stat = entry.ivb1, max = 15, onValueChange = { i, v -> onStatChange(StatGroup.IVB1, i, v) })
        StatusEditableRow(label = "IVB2", stat = entry.ivb2, max = 15, onValueChange = { i, v -> onStatChange(StatGroup.IVB2, i, v) })
        StatusEditableRow(label = "CB", stat = entry.cb, max = 255, onValueChange = { i, v -> onStatChange(StatGroup.CB, i, v) })
        StatusReadOnlyRow(label = "最終", values = finalStatus?.values() ?: ZERO_STAT)
        TechniqueRow(
            attackLevel = entry.attackLevel,
            techniqueLevel = entry.techniqueLevel,
            soultimateLevel = entry.soultimateLevel,
            onAttackLevelChange = onAttackLevelChange,
            onTechniqueLevelChange = onTechniqueLevelChange,
            onSoultimateLevelChange = onSoultimateLevelChange,
        )
        MajimeCorrectionRow(
            correction = entry.majimeCorrection,
            onCorrectionChange = onMajimeCorrectionChange,
        )
        StateFlagRow(
            stateFlags = entry.stateFlags,
            onFlagChange = onStateFlagChange,
        )
    }
}

@Composable
private fun StatusHeadRow(
    name: String,
    classLabel: String,
    level: Int,
    attitudes: List<YokaiAttitude>,
    selectedAttitude: Int,
    onLevelChange: (Int) -> Unit,
    onAttitudeChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(classLabel)
        CompactNumberField(
            value = level,
            max = 99,
            modifier = Modifier.width(64.dp),
            onValueChange = onLevelChange,
        )
        AttitudeDropdown(
            attitudes = attitudes,
            selectedId = selectedAttitude,
            onSelected = onAttitudeChange,
            modifier = Modifier.width(132.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttitudeDropdown(
    attitudes: List<YokaiAttitude>,
    selectedId: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = attitudes.firstOrNull { it.id == selectedId }?.name ?: "性格ID:$selectedId"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            attitudes.forEach { attitude ->
                DropdownMenuItem(
                    text = { Text(attitude.name) },
                    onClick = {
                        onSelected(attitude.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("", modifier = Modifier.width(STATUS_LABEL_WIDTH))
        listOf("HP", "力", "妖", "守", "速").forEach { title ->
            Box(modifier = Modifier.width(STATUS_CELL_WIDTH), contentAlignment = Alignment.Center) {
                Text(title, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatusReadOnlyRow(label: String, values: List<Int>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(STATUS_LABEL_WIDTH), fontWeight = FontWeight.Medium)
        values.forEach { value ->
            Box(modifier = Modifier.width(STATUS_CELL_WIDTH), contentAlignment = Alignment.Center) {
                Text(value.toString())
            }
        }
    }
}

@Composable
private fun StatusEditableRow(
    label: String,
    stat: Stat5,
    max: Int,
    onValueChange: (Int, Int) -> Unit,
) {
    val values = stat.values()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(STATUS_LABEL_WIDTH), fontWeight = FontWeight.Medium)
        values.forEachIndexed { index, value ->
            CompactNumberField(
                value = value,
                max = max,
                modifier = Modifier
                    .width(STATUS_CELL_WIDTH)
                    .padding(horizontal = 1.dp),
                onValueChange = { onValueChange(index, it) },
            )
        }
    }
}

@Composable
private fun TechniqueRow(
    attackLevel: Int,
    techniqueLevel: Int,
    soultimateLevel: Int,
    onAttackLevelChange: (Int) -> Unit,
    onTechniqueLevelChange: (Int) -> Unit,
    onSoultimateLevelChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("技Lv", modifier = Modifier.width(STATUS_LABEL_WIDTH), fontWeight = FontWeight.Medium)
        Text("攻")
        CompactNumberField(
            value = attackLevel,
            max = 99,
            modifier = Modifier.width(56.dp),
            onValueChange = onAttackLevelChange,
        )
        Text("妖")
        CompactNumberField(
            value = techniqueLevel,
            max = 99,
            modifier = Modifier.width(56.dp),
            onValueChange = onTechniqueLevelChange,
        )
        Text("必")
        CompactNumberField(
            value = soultimateLevel,
            max = 99,
            modifier = Modifier.width(56.dp),
            onValueChange = onSoultimateLevelChange,
        )
    }
}

@Composable
private fun MajimeCorrectionRow(
    correction: Int,
    onCorrectionChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("まじめ度", modifier = Modifier.width(STATUS_LABEL_WIDTH), fontWeight = FontWeight.Medium)
        CompactNumberField(
            value = correction,
            max = 255,
            modifier = Modifier.width(72.dp),
            onValueChange = onCorrectionChange,
        )
        Text("補正値(0x76)")
    }
}

@Composable
private fun StateFlagRow(
    stateFlags: Int,
    onFlagChange: (Int, Boolean) -> Unit,
) {
    val lockMask = 0x03
    val bookMask = 0x04
    val newMask = 0x08

    val lockEnabled = stateFlags and lockMask == lockMask
    val bookEnabled = stateFlags and bookMask != 0
    val newEnabled = stateFlags and newMask != 0

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("状態フラグ 0x77 = 0x${(stateFlags and 0xFF).toString(16).uppercase().padStart(2, '0')}")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = lockEnabled, onCheckedChange = { onFlagChange(lockMask, it) })
                Text("おわかれ不可(0x03)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bookEnabled, onCheckedChange = { onFlagChange(bookMask, it) })
                Text("本使用済み(0x04)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = newEnabled, onCheckedChange = { onFlagChange(newMask, it) })
                Text("NEW!(0x08)")
            }
        }
    }
}


@Composable
private fun CompactNumberField(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
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
        modifier = modifier.onFocusChanged { state ->
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