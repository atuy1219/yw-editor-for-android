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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
            onStatChange = { slot, group, index, value ->
                mainViewModel.updateStat(slot, group, index, value)
            },
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
                    .clickable(enabled = shizukuGranted && !loading) {
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
                    Text("名前(先頭妖怪): ${slot.displayName ?: "-"}")
                    Text("プレイ時間: ${slot.playTimeText ?: "未解析"}")
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

private fun formatDateTime(epochMillis: Long?): String {
    if (epochMillis == null) return "未取得"
    val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    return formatter.format(Date(epochMillis))
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorScreen(
    state: EditorUiState,
    shizukuGranted: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onTabSelect: (EditorTopTab) -> Unit,
    onYokaiCardClick: (Int) -> Unit,
    onLevelChange: (Int, Int) -> Unit,
    onAttitudeChange: (Int, Int) -> Unit,
    onAttackLevelChange: (Int, Int) -> Unit,
    onTechniqueLevelChange: (Int, Int) -> Unit,
    onSoultimateLevelChange: (Int, Int) -> Unit,
    onStatChange: (Int, StatGroup, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    onStatChange = onStatChange,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> PlaceholderTabContent(
                    tab = state.selectedTopTab,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
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