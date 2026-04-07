package com.atuy.yweditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atuy.yweditor.yokai.MainBinCodec
import com.atuy.yweditor.yokai.MainBinBackupInfo
import com.atuy.yweditor.yokai.MainBinDecoded
import com.atuy.yweditor.yokai.SaveInfo
import com.atuy.yweditor.yokai.SaveInfoCodec
import com.atuy.yweditor.yokai.Stat5
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
    val saveDateText: String? = null,
    val yokaiCount: Int? = null,
)

data class YokaiOption(
    val id: Long,
    val name: String,
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
    val yokaiOptions: List<YokaiOption> = emptyList(),
    val startupSaveSlots: List<SaveSlotCard> = DEFAULT_STARTUP_SLOTS,
    val backupItems: List<MainBinBackupInfo> = emptyList(),
    val backupsLoading: Boolean = false,
    val backupsCreating: Boolean = false,
    val backupsRestoring: Boolean = false,
    val playHours: Int = 0,
    val playMinutes: Int = 0,
    val money: Int = 0,
    val playerName: String = "",
    val playerNameError: String? = null,
    val saveYear: Int = 2026,
    val saveMonth: Int = 1,
    val saveDay: Int = 1,
    val saveHour: Int = 0,
    val saveMinute: Int = 0,
    val startupSlotsLoaded: Boolean = false,
    val isCheatMode: Boolean = false,
)

class MainViewModel : ViewModel() {

    companion object {
        private const val CHEAT_STAT_MAX = 255
        private const val NORMAL_LEVEL_MAX = 99
        private const val NORMAL_IVA_TOTAL_MAX = 8
        private const val NORMAL_IVB1_TOTAL_MAX = 10
        private const val NORMAL_CB_TOTAL_MAX = 20
        private const val NORMAL_IVB_MAX = 15

        // H, A, M, D, S
        private val IVA_EDITABLE_BY_CLASS: Map<Int, List<Boolean>> = mapOf(
            0 to listOf(false, false, false, false, false),
            1 to listOf(false, true, false, false, false),
            2 to listOf(false, false, true, false, false),
            3 to listOf(false, false, false, true, false),
            4 to listOf(false, false, false, false, true),
            5 to listOf(false, false, true, true, false),
            6 to listOf(true, false, false, false, true),
            7 to listOf(false, true, true, false, false),
            8 to listOf(true, false, false, false, false),
        )
    }

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

        _uiState.update {
            it.copy(
                attitudes = masterData.attitudes,
                yokaiOptions = buildYokaiOptions(masterData),
            )
        }

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
                val headSection = decoded.sections["head.yw"] ?: error("head.yw が見つかりません")
                val entries = parser.parse(section.decryptedData)
                val saveInfo = SaveInfoCodec.parse(section.decryptedData, headSection.decryptedData, sectionName)
                val startupSlots = buildStartupSlots(decoded)
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
                        money = saveInfo.money,
                        playerName = saveInfo.playerName,
                        playerNameError = null,
                        saveYear = saveInfo.saveYear,
                        saveMonth = saveInfo.saveMonth,
                        saveDay = saveInfo.saveDay,
                        saveHour = saveInfo.saveHour,
                        saveMinute = saveInfo.saveMinute,
                        message = "",
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
                        money = 0,
                        playerName = "",
                        playerNameError = null,
                        saveYear = 2026,
                        saveMonth = 1,
                        saveDay = 1,
                        saveHour = 0,
                        saveMinute = 0,
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
                val startupSlots = buildStartupSlots(decoded)
                val selectedSection = _uiState.value.selectedSection
                val headData = decoded.sections["head.yw"]?.decryptedData
                val selectedInfo = decoded.sections[selectedSection]?.let { section ->
                    if (headData == null) null else SaveInfoCodec.parse(section.decryptedData, headData, selectedSection)
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
                        money = selectedInfo?.money ?: it.money,
                        playerName = selectedInfo?.playerName ?: it.playerName,
                        playerNameError = null,
                        saveYear = selectedInfo?.saveYear ?: it.saveYear,
                        saveMonth = selectedInfo?.saveMonth ?: it.saveMonth,
                        saveDay = selectedInfo?.saveDay ?: it.saveDay,
                        saveHour = selectedInfo?.saveHour ?: it.saveHour,
                        saveMinute = selectedInfo?.saveMinute ?: it.saveMinute,
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
        val headSection = decoded.sections["head.yw"] ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, message = "保存中...") }
            runCatching {
                val saveInfo = SaveInfo(
                    playHours = _uiState.value.playHours,
                    playMinutes = _uiState.value.playMinutes,
                    money = _uiState.value.money,
                    playerName = _uiState.value.playerName,
                    saveYear = _uiState.value.saveYear,
                    saveMonth = _uiState.value.saveMonth,
                    saveDay = _uiState.value.saveDay,
                    saveHour = _uiState.value.saveHour,
                    saveMinute = _uiState.value.saveMinute,
                )
                val withYokai = parser.applyEntries(section.decryptedData, _uiState.value.entries)
                val writeResult = SaveInfoCodec.apply(
                    baseGameData = withYokai,
                    baseHeadData = headSection.decryptedData,
                    sectionName = sectionName,
                    info = saveInfo,
                )
                val withGamePatched = codec.replaceSection(decoded, sectionName, writeResult.gameData)
                val decodedWithGame = codec.decode(withGamePatched)
                val updatedMainBin = codec.replaceSection(decodedWithGame, "head.yw", writeResult.headData)
                gateway.backup(path)
                gateway.writeBytes(path, updatedMainBin)

                decodedMainBin = codec.decode(updatedMainBin)
                val refreshedSection = decodedMainBin?.sections?.get(sectionName)
                    ?: error("保存後に $sectionName を再読み込みできません")
                val refreshedHead = decodedMainBin?.sections?.get("head.yw")
                    ?: error("保存後に head.yw を再読み込みできません")
                val refreshedEntries = parser.parse(refreshedSection.decryptedData)
                val refreshedSaveInfo = SaveInfoCodec.parse(
                    refreshedSection.decryptedData,
                    refreshedHead.decryptedData,
                    sectionName,
                )
                val startupSlots = decodedMainBin?.let { buildStartupSlots(it) } ?: DEFAULT_STARTUP_SLOTS
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
                        money = refreshedSaveInfo.money,
                        playerName = refreshedSaveInfo.playerName,
                        playerNameError = null,
                        saveYear = refreshedSaveInfo.saveYear,
                        saveMonth = refreshedSaveInfo.saveMonth,
                        saveDay = refreshedSaveInfo.saveDay,
                        saveHour = refreshedSaveInfo.saveHour,
                        saveMinute = refreshedSaveInfo.saveMinute,
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
        val headSection = decoded.sections["head.yw"]
        val saveInfo = headSection?.let {
            SaveInfoCodec.parse(target.decryptedData, it.decryptedData, sectionName)
        }
        _uiState.update {
            it.copy(
                selectedSection = sectionName,
                loaded = true,
                entries = entries,
                attitudes = masterData.attitudes,
                expandedYokaiSlot = null,
                selectedSlot = entries.firstOrNull()?.slot,
                playHours = saveInfo?.playHours ?: it.playHours,
                playMinutes = saveInfo?.playMinutes ?: it.playMinutes,
                money = saveInfo?.money ?: it.money,
                playerName = saveInfo?.playerName ?: it.playerName,
                playerNameError = null,
                saveYear = saveInfo?.saveYear ?: it.saveYear,
                saveMonth = saveInfo?.saveMonth ?: it.saveMonth,
                saveDay = saveInfo?.saveDay ?: it.saveDay,
                saveHour = saveInfo?.saveHour ?: it.saveHour,
                saveMinute = saveInfo?.saveMinute ?: it.saveMinute,
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
                val headSection = decoded.sections["head.yw"] ?: error("head.yw が見つかりません")
                val entries = parser.parse(section.decryptedData)
                val saveInfo = SaveInfoCodec.parse(section.decryptedData, headSection.decryptedData, sectionName)
                val startupSlots = buildStartupSlots(decoded)
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
                        money = saveInfo.money,
                        playerName = saveInfo.playerName,
                        playerNameError = null,
                        saveYear = saveInfo.saveYear,
                        saveMonth = saveInfo.saveMonth,
                        saveDay = saveInfo.saveDay,
                        saveHour = saveInfo.saveHour,
                        saveMinute = saveInfo.saveMinute,
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

    fun setCheatMode(enabled: Boolean) {
        _uiState.update { state ->
            val normalizedEntries = if (enabled) {
                state.entries
            } else {
                state.entries.map { normalizeForNormalMode(it) }
            }
            state.copy(isCheatMode = enabled, entries = normalizedEntries)
        }
    }

    fun updateLevel(slot: Int, level: Int) {
        val max = if (_uiState.value.isCheatMode) CHEAT_STAT_MAX else NORMAL_LEVEL_MAX
        updateEntry(slot) { it.copy(level = clampToRange(level, max)) }
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

    fun updateYokai(slot: Int, yokaiId: Long) {
        updateEntry(slot) { entry ->
            val detail = masterData.detailById[yokaiId]
            entry.copy(
                id = yokaiId,
                name = masterData.nameById[yokaiId] ?: entry.name,
                baseStats = detail?.baseStats,
                growPattern = detail?.growPattern,
                yokaiClass = detail?.yokaiClass,
            )
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
        val isCheatMode = _uiState.value.isCheatMode
        updateEntry(slot) { entry ->
            when (group) {
                StatGroup.IVA -> {
                    if (isCheatMode) {
                        val updated = applyStatUpdate(
                            stat = entry.iva,
                            index = index,
                            requested = value,
                            cellMax = CHEAT_STAT_MAX,
                            totalMax = null,
                        )
                        return@updateEntry entry.copy(iva = updated)
                    }

                    val editableMask = ivaEditableMask(entry.yokaiClass)
                    val masked = applyIvaMask(entry.iva, editableMask)
                    if (!editableMask.getOrElse(index) { false }) {
                        return@updateEntry entry.copy(iva = masked)
                    }
                    val updated = applyStatUpdate(
                        stat = masked,
                        index = index,
                        requested = value,
                        cellMax = CHEAT_STAT_MAX,
                        totalMax = NORMAL_IVA_TOTAL_MAX,
                    )
                    entry.copy(iva = applyIvaMask(updated, editableMask))
                }

                StatGroup.IVB1 -> {
                    val updated = applyStatUpdate(
                        stat = entry.ivb1,
                        index = index,
                        requested = value,
                        cellMax = NORMAL_IVB_MAX,
                        totalMax = if (isCheatMode) null else NORMAL_IVB1_TOTAL_MAX,
                    )
                    entry.copy(ivb1 = updated)
                }

                StatGroup.IVB2 -> {
                    val updated = applyStatUpdate(
                        stat = entry.ivb2,
                        index = index,
                        requested = value,
                        cellMax = NORMAL_IVB_MAX,
                        totalMax = null,
                    )
                    entry.copy(ivb2 = updated)
                }

                StatGroup.CB -> {
                    val updated = applyStatUpdate(
                        stat = entry.cb,
                        index = index,
                        requested = value,
                        cellMax = CHEAT_STAT_MAX,
                        totalMax = if (isCheatMode) null else NORMAL_CB_TOTAL_MAX,
                    )
                    entry.copy(cb = updated)
                }
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

    fun updateMoney(value: Int) {
        _uiState.update { state ->
            state.copy(money = value.coerceIn(0, SaveInfoCodec.MONEY_MAX))
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

    fun updateSaveYear(value: Int) {
        _uiState.update { state -> state.copy(saveYear = value.coerceIn(0, 9999)) }
    }

    fun updateSaveMonth(value: Int) {
        _uiState.update { state -> state.copy(saveMonth = value.coerceIn(1, 12)) }
    }

    fun updateSaveDay(value: Int) {
        _uiState.update { state -> state.copy(saveDay = value.coerceIn(1, 31)) }
    }

    fun updateSaveHour(value: Int) {
        _uiState.update { state -> state.copy(saveHour = value.coerceIn(0, 23)) }
    }

    fun updateSaveMinute(value: Int) {
        _uiState.update { state -> state.copy(saveMinute = value.coerceIn(0, 59)) }
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

    private fun applyStatUpdate(
        stat: Stat5,
        index: Int,
        requested: Int,
        cellMax: Int,
        totalMax: Int?,
    ): Stat5 {
        if (index !in 0..4) return stat
        val clampedRequested = clampToRange(requested, cellMax)
        if (totalMax == null) return stat.update(index, clampedRequested)

        val currentValues = stat.values()
        val otherTotal = currentValues
            .filterIndexed { i, _ -> i != index }
            .sum()
        val allowed = (totalMax - otherTotal).coerceIn(0, cellMax)
        return stat.update(index, clampedRequested.coerceAtMost(allowed))
    }

    private fun normalizeForNormalMode(entry: YokaiEntry): YokaiEntry {
        val ivaMask = ivaEditableMask(entry.yokaiClass)
        val normalizedIva = normalizeStatForNormal(
            applyIvaMask(entry.iva, ivaMask),
            CHEAT_STAT_MAX,
            NORMAL_IVA_TOTAL_MAX,
        )
        val normalizedIvb1 = normalizeStatForNormal(entry.ivb1, NORMAL_IVB_MAX, NORMAL_IVB1_TOTAL_MAX)
        val normalizedIvb2 = normalizeStatForNormal(entry.ivb2, NORMAL_IVB_MAX, totalMax = null)
        val normalizedCb = normalizeStatForNormal(entry.cb, CHEAT_STAT_MAX, NORMAL_CB_TOTAL_MAX)

        return entry.copy(
            level = clampToRange(entry.level, NORMAL_LEVEL_MAX),
            iva = normalizedIva,
            ivb1 = normalizedIvb1,
            ivb2 = normalizedIvb2,
            cb = normalizedCb,
        )
    }

    private fun normalizeStatForNormal(stat: Stat5, cellMax: Int, totalMax: Int?): Stat5 {
        val values = stat.values().map { clampToRange(it, cellMax) }
        if (totalMax == null) {
            return statFromValues(values)
        }

        var remaining = totalMax
        val normalized = values.map { value ->
            val allowed = value.coerceAtMost(remaining)
            remaining -= allowed
            allowed
        }
        return statFromValues(normalized)
    }

    private fun statFromValues(values: List<Int>): Stat5 {
        return Stat5(
            hp = values.getOrElse(0) { 0 },
            power = values.getOrElse(1) { 0 },
            spirit = values.getOrElse(2) { 0 },
            defense = values.getOrElse(3) { 0 },
            speed = values.getOrElse(4) { 0 },
        )
    }

    private fun ivaEditableMask(yokaiClass: Int?): List<Boolean> {
        return IVA_EDITABLE_BY_CLASS[yokaiClass] ?: listOf(true, true, true, true, true)
    }

    private fun applyIvaMask(stat: Stat5, editableMask: List<Boolean>): Stat5 {
        val masked = stat.values().mapIndexed { index, value ->
            if (editableMask.getOrElse(index) { false }) value else 0
        }
        return statFromValues(masked)
    }

    private fun buildYokaiOptions(data: YokaiMasterData): List<YokaiOption> {
        return data.nameById
            .map { (id, name) -> YokaiOption(id = id, name = name) }
    }

    private fun buildStartupSlots(decoded: MainBinDecoded): List<SaveSlotCard> {
        return DEFAULT_STARTUP_SLOTS.map { base ->
            val section = decoded.sections[base.sectionName]
            if (section == null) {
                base.copy(
                    hasData = false,
                    displayName = "データがありません",
                    playTimeText = "データがありません",
                    saveDateText = "データがありません",
                    yokaiCount = null,
                )
            } else {
                val entries = parser.parse(section.decryptedData)
                val headData = decoded.sections["head.yw"]?.decryptedData
                val saveInfo = headData?.let { SaveInfoCodec.parse(section.decryptedData, it, base.sectionName) }
                val playTimeText = saveInfo?.let { "%d時間%02d分".format(it.playHours, it.playMinutes) }
                    ?: "未解析"
                val saveDateText = saveInfo?.let {
                    "%04d/%02d/%02d %02d:%02d".format(
                        it.saveYear,
                        it.saveMonth,
                        it.saveDay,
                        it.saveHour,
                        it.saveMinute,
                    )
                } ?: "未解析"
                base.copy(
                    hasData = true,
                    displayName = saveInfo?.playerName?.ifBlank { null },
                    playTimeText = playTimeText,
                    saveDateText = saveDateText,
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

