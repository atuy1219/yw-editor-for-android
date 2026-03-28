package com.atuy.yweditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atuy.yweditor.yokai.MainBinCodec
import com.atuy.yweditor.yokai.MainBinDecoded
import com.atuy.yweditor.yokai.StatGroup
import com.atuy.yweditor.yokai.YokaiEntry
import com.atuy.yweditor.yokai.YokaiParser
import com.atuy.yweditor.yokai.ShizukuFileGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String = "",
    val entries: List<YokaiEntry> = emptyList(),
    val selectedSlot: Int? = null,
    val selectedSection: String = "game0.yw",
    val loaded: Boolean = false,
)

class MainViewModel : ViewModel() {

    val editableSections = listOf("game0.yw", "game1.yw", "game2.yw", "game3.yw")

    private val gateway = ShizukuFileGateway()
    private val codec = MainBinCodec()
    private val parser = YokaiParser()

    private var decodedMainBin: MainBinDecoded? = null
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun load(path: String) {
        val sectionName = _uiState.value.selectedSection
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = "main.bin 読み込み中...") }
            runCatching {
                val raw = gateway.readBytes(path)
                val decoded = codec.decode(raw)
                val section = decoded.sections[sectionName] ?: error("$sectionName が見つかりません")
                val entries = parser.parse(section.decryptedData)

                decodedMainBin = decoded

                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        entries = entries,
                        selectedSlot = entries.firstOrNull()?.slot,
                        message = "$sectionName から${entries.size}体の妖怪を読み込みました",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = false,
                        entries = emptyList(),
                        selectedSlot = null,
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

                _uiState.update {
                    it.copy(
                        saving = false,
                        entries = refreshedEntries,
                        selectedSlot = refreshedEntries.firstOrNull()?.slot,
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
                selectedSlot = entries.firstOrNull()?.slot,
                message = "$sectionName に切り替えました",
            )
        }
    }

    fun select(slot: Int) {
        _uiState.update { it.copy(selectedSlot = slot) }
    }

    fun updateLevel(slot: Int, level: Int) {
        updateEntry(slot) { it.copy(level = clampToRange(level, 255)) }
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
}

