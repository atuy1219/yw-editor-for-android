package com.atuy.yweditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atuy.yweditor.yokai.MainBinCodec
import com.atuy.yweditor.yokai.MainBinDecoded
import com.atuy.yweditor.yokai.YokaiAttitude
import com.atuy.yweditor.yokai.StatGroup
import com.atuy.yweditor.yokai.YokaiEntry
import com.atuy.yweditor.yokai.YokaiMasterData
import com.atuy.yweditor.yokai.YokaiParser
import com.atuy.yweditor.yokai.ShizukuFileGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppScreen {
    Startup,
    Editor,
}

enum class EditorTopTab(val label: String) {
    Yokai("妖怪"),
    Item("どうぐ"),
    Equipment("そうび"),
    KeyItem("だいじなもの"),
    Info("情報"),
    Encyclopedia("妖怪大辞典"),
}

data class SaveSlotCard(
    val sectionName: String,
    val title: String,
    val subtitle: String,
    val displayName: String? = null,
    val playTimeText: String? = null,
    val lastUpdatedEpochMillis: Long? = null,
    val yokaiCount: Int? = null,
)

private val DEFAULT_STARTUP_SLOTS = listOf(
    SaveSlotCard(sectionName = "game0.yw", title = "オートセーブ", subtitle = "game0.yw"),
    SaveSlotCard(sectionName = "game1.yw", title = "にっき1", subtitle = "game1.yw"),
    SaveSlotCard(sectionName = "game2.yw", title = "にっき2", subtitle = "game2.yw"),
)

data class EditorUiState(
    val currentScreen: AppScreen = AppScreen.Startup,
    val selectedTopTab: EditorTopTab = EditorTopTab.Yokai,
    val expandedYokaiSlot: Int? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String = "",
    val entries: List<YokaiEntry> = emptyList(),
    val selectedSlot: Int? = null,
    val selectedSection: String = "game0.yw",
    val loaded: Boolean = false,
    val attitudes: List<YokaiAttitude> = emptyList(),
    val startupSaveSlots: List<SaveSlotCard> = DEFAULT_STARTUP_SLOTS,
)

class MainViewModel : ViewModel() {

    val editableSections = listOf("game0.yw", "game1.yw", "game2.yw")

    private val gateway = ShizukuFileGateway()
    private val codec = MainBinCodec()
    private var masterData = YokaiMasterData.EMPTY
    private var parser = YokaiParser(masterData)

    private var decodedMainBin: MainBinDecoded? = null
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun setMasterData(data: YokaiMasterData) {
        masterData = data
        parser = YokaiParser(masterData)

        _uiState.update { it.copy(attitudes = masterData.attitudes) }

        val decoded = decodedMainBin ?: return
        val sectionName = _uiState.value.selectedSection
        val section = decoded.sections[sectionName] ?: return
        val currentSlot = _uiState.value.selectedSlot
        val refreshedEntries = parser.parse(section.decryptedData)

        _uiState.update {
            it.copy(
                entries = refreshedEntries,
                attitudes = masterData.attitudes,
                selectedSlot = currentSlot?.takeIf { slot ->
                    refreshedEntries.any { entry -> entry.slot == slot }
                } ?: refreshedEntries.firstOrNull()?.slot,
            )
        }
    }

    fun load(path: String) {
        val sectionName = _uiState.value.selectedSection
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = "main.bin 読み込み中...") }
            runCatching {
                val raw = gateway.readBytes(path)
                val decoded = codec.decode(raw)
                val section = decoded.sections[sectionName] ?: error("$sectionName が見つかりません")
                val entries = parser.parse(section.decryptedData)
                val startupSlots = buildStartupSlots(decoded, path)

                decodedMainBin = decoded

                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        entries = entries,
                        attitudes = masterData.attitudes,
                        expandedYokaiSlot = null,
                        selectedSlot = entries.firstOrNull()?.slot,
                        startupSaveSlots = startupSlots,
                        message = "$sectionName を読み込みました",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = false,
                        entries = emptyList(),
                        expandedYokaiSlot = null,
                        selectedSlot = null,
                        startupSaveSlots = DEFAULT_STARTUP_SLOTS,
                        message = "読み込み失敗: ${e.message}",
                    )
                }
            }
        }
    }

    fun save(path: String) {
        val decoded = decodedMainBin ?: return
        val sectionName = _uiState.value.selectedSection
        val section = decoded.sections[sectionName] ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, message = "保存中...") }
            runCatching {
                val patchedSection = parser.applyEntries(section.decryptedData, _uiState.value.entries)
                val updatedMainBin = codec.replaceSection(decoded, sectionName, patchedSection)
                gateway.backup(path)
                gateway.writeBytes(path, updatedMainBin)

                decodedMainBin = codec.decode(updatedMainBin)
                val refreshedSection = decodedMainBin?.sections?.get(sectionName)
                    ?: error("保存後に $sectionName を再読み込みできません")
                val refreshedEntries = parser.parse(refreshedSection.decryptedData)
                val startupSlots = decodedMainBin?.let { buildStartupSlots(it, path) } ?: DEFAULT_STARTUP_SLOTS

                _uiState.update {
                    it.copy(
                        saving = false,
                        entries = refreshedEntries,
                        expandedYokaiSlot = null,
                        selectedSlot = refreshedEntries.firstOrNull()?.slot,
                        startupSaveSlots = startupSlots,
                        message = "$sectionName を保存しました（バックアップ作成済み）",
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(saving = false, message = "保存失敗: ${e.message}") }
            }
        }
    }

    fun setSection(sectionName: String) {
        if (sectionName !in editableSections) return

        val decoded = decodedMainBin
        if (decoded == null) {
            _uiState.update { it.copy(selectedSection = sectionName) }
            return
        }

        val target = decoded.sections[sectionName]
        if (target == null) {
            _uiState.update {
                it.copy(
                    selectedSection = sectionName,
                    loaded = false,
                    entries = emptyList(),
                    expandedYokaiSlot = null,
                    selectedSlot = null,
                    message = "$sectionName は main.bin 内に見つかりません",
                )
            }
            return
        }

        val entries = parser.parse(target.decryptedData)
        _uiState.update {
            it.copy(
                selectedSection = sectionName,
                loaded = true,
                entries = entries,
                attitudes = masterData.attitudes,
                expandedYokaiSlot = null,
                selectedSlot = entries.firstOrNull()?.slot,
                message = "$sectionName に切り替えました",
            )
        }
    }

    fun openEditorForSection(path: String, sectionName: String) {
        if (sectionName !in editableSections) return
        setSection(sectionName)
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Editor,
                selectedTopTab = EditorTopTab.Yokai,
                expandedYokaiSlot = null,
            )
        }
        load(path)
    }

    fun backToStartup() {
        _uiState.update { it.copy(currentScreen = AppScreen.Startup) }
    }

    fun selectTopTab(tab: EditorTopTab) {
        _uiState.update {
            it.copy(
                selectedTopTab = tab,
                expandedYokaiSlot = if (tab == EditorTopTab.Yokai) it.expandedYokaiSlot else null,
            )
        }
    }

    fun toggleYokaiExpanded(slot: Int) {
        _uiState.update {
            it.copy(expandedYokaiSlot = if (it.expandedYokaiSlot == slot) null else slot)
        }
    }

    fun select(slot: Int) {
        _uiState.update { it.copy(selectedSlot = slot) }
    }

    fun updateLevel(slot: Int, level: Int) {
        updateEntry(slot) { it.copy(level = clampToRange(level, 255)) }
    }

    fun updateAttackLevel(slot: Int, attackLevel: Int) {
        updateEntry(slot) { it.copy(attackLevel = clampToRange(attackLevel, 99)) }
    }

    fun updateTechniqueLevel(slot: Int, techniqueLevel: Int) {
        updateEntry(slot) { it.copy(techniqueLevel = clampToRange(techniqueLevel, 99)) }
    }

    fun updateSoultimateLevel(slot: Int, soultimateLevel: Int) {
        updateEntry(slot) { it.copy(soultimateLevel = clampToRange(soultimateLevel, 99)) }
    }

    fun updateAttitude(slot: Int, attitudeId: Int) {
        updateEntry(slot) { entry ->
            entry.copy(attitudeId = clampToRange(attitudeId, 255))
        }
    }

    fun updateStat(slot: Int, group: StatGroup, index: Int, value: Int) {
        updateEntry(slot) { entry ->
            when (group) {
                StatGroup.IVA -> entry.copy(iva = entry.iva.update(index, clampToRange(value, 255)))
                StatGroup.IVB1 -> entry.copy(ivb1 = entry.ivb1.update(index, clampToRange(value, 15)))
                StatGroup.IVB2 -> entry.copy(ivb2 = entry.ivb2.update(index, clampToRange(value, 15)))
                StatGroup.CB -> entry.copy(cb = entry.cb.update(index, clampToRange(value, 255)))
            }
        }
    }

    private fun updateEntry(slot: Int, updater: (YokaiEntry) -> YokaiEntry) {
        _uiState.update { state ->
            val updated = state.entries.map { if (it.slot == slot) updater(it) else it }
            state.copy(entries = updated)
        }
    }

    private fun clampToRange(value: Int, max: Int): Int {
        return when {
            value < 0 -> 0
            value > max -> max
            else -> value
        }
    }

    private fun buildStartupSlots(decoded: MainBinDecoded, path: String): List<SaveSlotCard> {
        val lastUpdated = runCatching { gateway.lastModifiedMillis(path) }.getOrNull()
        return DEFAULT_STARTUP_SLOTS.map { base ->
            val section = decoded.sections[base.sectionName]
            if (section == null) {
                base.copy(lastUpdatedEpochMillis = lastUpdated)
            } else {
                val entries = parser.parse(section.decryptedData)
                val firstName = entries.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                base.copy(
                    displayName = firstName,
                    playTimeText = null,
                    lastUpdatedEpochMillis = lastUpdated,
                    yokaiCount = entries.size,
                )
            }
        }
    }
}

