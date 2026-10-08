package com.gabrielifrim.brut.library

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

enum class TakeSort { DATE, NAME, DURATION }

/** Message de la bibliothèque ; le texte est résolu par l'interface. */
sealed interface LibraryMessage {
    data class Trashed(val take: Take) : LibraryMessage
    data class Renamed(val name: String) : LibraryMessage
    data object Failed : LibraryMessage
}

data class LibraryState(
    val takes: List<Take> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val sort: TakeSort = TakeSort.DATE,
    val selectedKey: String? = null,
    val waveforms: Map<String, Waveform> = emptyMap(),
    val message: LibraryMessage? = null,
    /** Action système à faire confirmer (prise d'une installation précédente). */
    val consent: IntentSender? = null,
) {
    /** Prises affichées : filtrées par la recherche puis triées. */
    val visible: List<Take>
        get() {
            val q = fold(query)
            val filtered = if (q.isBlank()) takes else takes.filter { t ->
                fold(t.name).contains(q) || fold(t.info?.description.orEmpty()).contains(q)
            }
            return when (sort) {
                TakeSort.DATE -> filtered.sortedByDescending { it.dateMillis }
                TakeSort.NAME -> filtered.sortedBy { fold(it.name) }
                TakeSort.DURATION -> filtered.sortedByDescending { it.info?.frames?.toDouble()?.div(it.info.sampleRate) ?: 0.0 }
            }
        }

    companion object {
        /** Recherche insensible à la casse et aux accents : « entree » trouve « Entrée ». */
        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}

class LibraryController(context: Context) {

    private val repository = TakeRepository(context)
    private val waveforms = WaveformCache(context)
    val player = TakePlayer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var waveJob: Job? = null
    /** Action à rejouer une fois l'accord du système obtenu. */
    private var pendingRetry: (suspend () -> Unit)? = null

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            val takes = repository.list()
            _state.update { s ->
                s.copy(
                    takes = takes,
                    loading = false,
                    selectedKey = s.selectedKey?.takeIf { key -> takes.any { it.key == key } },
                )
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setSort(sort: TakeSort) = _state.update { it.copy(sort = sort) }

    fun select(take: Take) {
        if (_state.value.selectedKey == take.key) {
            player.stop()
            _state.update { it.copy(selectedKey = null) }
            return
        }
        _state.update { it.copy(selectedKey = take.key) }
        player.load(take)
        if (take.key !in _state.value.waveforms) {
            waveJob?.cancel()
            waveJob = scope.launch {
                waveforms.get(take)?.let { w -> _state.update { it.copy(waveforms = it.waveforms + (take.key to w)) } }
            }
        }
    }

    fun rename(take: Take, newName: String) {
        val retry: suspend () -> Unit = { rename(take, newName) }
        scope.launch {
            handle(repository.rename(take, newName), retry) {
                _state.update { it.copy(message = LibraryMessage.Renamed(TakeRepository.sanitize(newName))) }
                refresh()
            }
        }
    }

    fun trash(take: Take) {
        if (_state.value.selectedKey == take.key) {
            player.stop()
            _state.update { it.copy(selectedKey = null) }
        }
        // Android 11+ : la demande système met elle-même à la corbeille, il suffit de recharger.
        // Android 10 : l'accord ne fait que débloquer l'écriture, il faut rejouer.
        val retry: suspend () -> Unit = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            { refresh() }
        } else {
            { trash(take) }
        }
        scope.launch {
            handle(repository.trash(take), retry) {
                _state.update { s -> s.copy(takes = s.takes - take, message = LibraryMessage.Trashed(take)) }
            }
        }
    }

    fun undoTrash(take: Take) {
        scope.launch {
            if (repository.restore(take)) refresh()
            _state.update { it.copy(message = null) }
        }
    }

    fun shareIntent(take: Take): Intent = repository.shareIntent(take)

    /** Résultat de la confirmation système : on rejoue l'action si l'utilisateur a accepté. */
    fun onConsentResult(granted: Boolean) {
        val retry = pendingRetry
        pendingRetry = null
        _state.update { it.copy(consent = null) }
        if (granted && retry != null) scope.launch { retry() } else refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun handle(result: EditResult, retry: suspend () -> Unit, onDone: () -> Unit) {
        when (result) {
            EditResult.Done -> onDone()
            is EditResult.NeedsConsent -> {
                pendingRetry = retry
                _state.update { it.copy(consent = result.intent) }
            }
            EditResult.Failed -> _state.update { it.copy(message = LibraryMessage.Failed) }
        }
    }
}
