package com.atuy.yweditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atuy.yweditor.yokai.MainBinCodec
import com.atuy.yweditor.yokai.MainBinBackupInfo
import com.atuy.yweditor.yokai.MainBinDecoded
import com.atuy.yweditor.yokai.SaveInfo
import com.atuy.yweditor.yokai.SaveInfoCodec
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
    val hasData: Boolean = true,
    val displayName: String? = null,
    val playTimeText: String? = null,
    val lastUpdatedEpochMillis: Long? = null,
    val yokaiCount: Int? = null,
)

private val DEFAULT_STARTUP_SLOTS = listOf(
    SaveSlotCard(sectionName = "game0.yw", title = "オートセーブ", subtitle = "game0.yw"),
    SaveSlotCard(sectionName = "game1.yw", title = "にっき1", subtitle = "game1.yw"),
    SaveSlotCard(sectionName = "game2.yw", title = "にっき2", subtitle = "game2.yw"),
    SaveSlotCard(sectionName = "game3.yw", title = "にっき3", subtitle = "game3.yw"),
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
    val backupItems: List<MainBinBackupInfo> = emptyList(),
    val backupsLoading: Boolean = false,
    val backupsCreating: Boolean = false,
    val backupsRestoring: Boolean = false,
    val playHours: Int = 0,
    val playMinutes: Int = 0,
    val playerName: String = "",
    val playerNameError: String? = null,
    val startupSlotsLoaded: Boolean = false,
)

class MainViewModel : ViewModel() {

    val editableSections = listOf("game0.yw", "game1.yw", "game2.yw", "game3.yw")

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
                val saveInfo = SaveInfoCodec.parse(section.decryptedData)
                val startupSlots = buildStartupSlots(decoded, path)
                val backupItems = gateway.listManagedBackups(path)

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
                        backupItems = backupItems,
                        playHours = saveInfo.playHours,
                        playMinutes = saveInfo.playMinutes,
                        playerName = saveInfo.playerName,
                        playerNameError = null,
                        startupSlotsLoaded = true,
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
                        backupItems = emptyList(),
                        playHours = 0,
                        playMinutes = 0,
                        playerName = "",
                        playerNameError = null,
                        startupSlotsLoaded = false,
                        message = "読み込み失敗: ${e.message}",
                    )
                }
            }
        }
    }

    fun loadStartupSlots(path: String) {
        if (_uiState.value.loading) return

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = "セーブ情報を読み込み中...") }
            runCatching {
                val raw = gateway.readBytes(path)
                val decoded = codec.decode(raw)
                val startupSlots = buildStartupSlots(decoded, path)
                val selectedSection = _uiState.value.selectedSection
                val selectedInfo = decoded.sections[selectedSection]?.let { section ->
                    SaveInfoCodec.parse(section.decryptedData)
                }
                Triple(decoded, startupSlots, selectedInfo)
            }.onSuccess { (decoded, startupSlots, selectedInfo) ->
                decodedMainBin = decoded
                _uiState.update {
                    it.copy(
                        loading = false,
                        startupSaveSlots = startupSlots,
                        playHours = selectedInfo?.playHours ?: it.playHours,
                        playMinutes = selectedInfo?.playMinutes ?: it.playMinutes,
                        playerName = selectedInfo?.playerName ?: it.playerName,
                        startupSlotsLoaded = true,
                        message = "セーブ情報を更新しました",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        startupSlotsLoaded = false,
                        message = "セーブ情報の読み込み失敗: ${e.message}",
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
                val saveInfo = SaveInfo(
                    playHours = _uiState.value.playHours,
                    playMinutes = _uiState.value.playMinutes,
                    playerName = _uiState.value.playerName,
                )
                val withYokai = parser.applyEntries(section.decryptedData, _uiState.value.entries)
                val patchedSection = SaveInfoCodec.apply(withYokai, saveInfo)
                val updatedMainBin = codec.replaceSection(decoded, sectionName, patchedSection)
                gateway.backup(path)
                gateway.writeBytes(path, updatedMainBin)

                decodedMainBin = codec.decode(updatedMainBin)
                val refreshedSection = decodedMainBin?.sections?.get(sectionName)
                    ?: error("保存後に $sectionName を再読み込みできません")
                val refreshedEntries = parser.parse(refreshedSection.decryptedData)
                val refreshedSaveInfo = SaveInfoCodec.parse(refreshedSection.decryptedData)
                val startupSlots = decodedMainBin?.let { buildStartupSlots(it, path) } ?: DEFAULT_STARTUP_SLOTS
                val backupItems = gateway.listManagedBackups(path)

                _uiState.update {
                    it.copy(
                        saving = false,
                        entries = refreshedEntries,
                        expandedYokaiSlot = null,
                        selectedSlot = refreshedEntries.firstOrNull()?.slot,
                        startupSaveSlots = startupSlots,
                        backupItems = backupItems,
                        playHours = refreshedSaveInfo.playHours,
                        playMinutes = refreshedSaveInfo.playMinutes,
                        playerName = refreshedSaveInfo.playerName,
                        playerNameError = null,
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
        val saveInfo = SaveInfoCodec.parse(target.decryptedData)
        _uiState.update {
            it.copy(
                selectedSection = sectionName,
                loaded = true,
                entries = entries,
                attitudes = masterData.attitudes,
                expandedYokaiSlot = null,
                selectedSlot = entries.firstOrNull()?.slot,
                playHours = saveInfo.playHours,
                playMinutes = saveInfo.playMinutes,
                playerName = saveInfo.playerName,
                playerNameError = null,
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
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Startup,
                startupSlotsLoaded = false,
            )
        }
    }

    fun refreshBackups(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupsLoading = true, message = "バックアップ一覧を取得中...") }
            runCatching {
                gateway.listManagedBackups(path)
            }.onSuccess { items ->
                _uiState.update {
                    it.copy(
                        backupsLoading = false,
                        backupItems = items,
                        message = "バックアップ一覧を更新しました",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        backupsLoading = false,
                        message = "バックアップ一覧取得失敗: ${e.message}",
                    )
                }
            }
        }
    }

    fun createBackup(path: String, backupName: String, backupEpochMillis: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupsCreating = true, message = "バックアップ作成中...") }
            runCatching {
                gateway.createManagedBackup(path, backupEpochMillis, backupName)
                gateway.listManagedBackups(path)
            }.onSuccess { items ->
                _uiState.update {
                    it.copy(
                        backupsCreating = false,
                        backupItems = items,
                        message = "バックアップを作成しました",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        backupsCreating = false,
                        message = "バックアップ作成失敗: ${e.message}",
                    )
                }
            }
        }
    }

    fun restoreBackup(path: String, backupFileName: String) {
        val sectionName = _uiState.value.selectedSection
        val currentDecoded = decodedMainBin ?: run {
            _uiState.update { it.copy(message = "先に main.bin を読み込んでください") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(backupsRestoring = true, message = "バックアップを復元中...") }
            runCatching {
                gateway.restoreManagedBackup(path, backupFileName)
                val raw = gateway.readBytes(path)
                val decoded = codec.decode(raw)
                val section = decoded.sections[sectionName] ?: error("$sectionName が見つかりません")
                val entries = parser.parse(section.decryptedData)
                val saveInfo = SaveInfoCodec.parse(section.decryptedData)
                val startupSlots = buildStartupSlots(decoded, path)
                val backups = gateway.listManagedBackups(path)
                decoded to Quadruple(entries, saveInfo, startupSlots, backups)
            }.onSuccess { (decoded, payload) ->
                decodedMainBin = decoded
                val (entries, saveInfo, startupSlots, backups) = payload
                _uiState.update {
                    it.copy(
                        backupsRestoring = false,
                        loaded = true,
                        entries = entries,
                        expandedYokaiSlot = null,
                        selectedSlot = entries.firstOrNull()?.slot,
                        startupSaveSlots = startupSlots,
                        backupItems = backups,
                        playHours = saveInfo.playHours,
                        playMinutes = saveInfo.playMinutes,
                        playerName = saveInfo.playerName,
                        playerNameError = null,
                        message = "バックアップを復元しました",
                    )
                }
            }.onFailure { e ->
                decodedMainBin = currentDecoded
                _uiState.update {
                    it.copy(
                        backupsRestoring = false,
                        message = "バックアップ復元失敗: ${e.message}",
                    )
                }
            }
        }
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

    fun updateMajimeCorrection(slot: Int, correction: Int) {
        updateEntry(slot) { entry ->
            entry.copy(majimeCorrection = clampToRange(correction, 255))
        }
    }

    fun setStateFlag(slot: Int, mask: Int, enabled: Boolean) {
        updateEntry(slot) { entry ->
            val current = entry.stateFlags and 0xFF
            val normalizedMask = mask and 0xFF
            val updated = if (enabled) {
                current or normalizedMask
            } else {
                current and normalizedMask.inv()
            }
            entry.copy(stateFlags = updated and 0xFF)
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

    fun updatePlayHours(value: Int) {
        _uiState.update { state ->
            state.copy(playHours = value.coerceAtLeast(0))
        }
    }

    fun updatePlayMinutes(value: Int) {
        _uiState.update { state ->
            state.copy(playMinutes = value.coerceIn(0, 59))
        }
    }

    fun updatePlayerName(value: String) {
        val truncated = SaveInfoCodec.truncatePlayerName(value)
        val limited = truncated == value
        _uiState.update { state ->
            state.copy(
                playerName = truncated,
                playerNameError = if (limited) null else "プレイヤー名はUTF-8で最大23バイトです",
            )
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
                base.copy(
                    hasData = false,
                    displayName = "データがありません",
                    playTimeText = "データがありません",
                    lastUpdatedEpochMillis = lastUpdated,
                    yokaiCount = null,
                )
            } else {
                val entries = parser.parse(section.decryptedData)
                val saveInfo = SaveInfoCodec.parse(section.decryptedData)
                val playTimeText = "%d時間%02d分".format(saveInfo.playHours, saveInfo.playMinutes)
                base.copy(
                    hasData = true,
                    displayName = saveInfo.playerName.ifBlank { null },
                    playTimeText = playTimeText,
                    lastUpdatedEpochMillis = lastUpdated,
                    yokaiCount = entries.size,
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

