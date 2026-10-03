package com.ruyolidus.multiplayerbridge

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ruyolidus.multiplayerbridge.data.ImportedGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val games: List<ImportedGame> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val unreadableCount: Int = 0,
    val error: String? = null,
)

class BridgeModel(application: Application) : AndroidViewModel(application) {
    private val applicationContext = application as BridgeApplication
    var state by mutableStateOf(LibraryUiState())
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    init { refresh() }

    fun refresh() {
        if (state.importing) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                loadLibrary()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state = state.copy(loading = false, error = "Your library could not be opened. Your files have not been removed.")
            }
        }
    }

    fun importGame(uri: Uri) {
        if (state.importing || state.loading) return
        state = state.copy(importing = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val resolver = applicationContext.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "game.apk"
                    requireNotNull(resolver.openInputStream(uri)) { "This file could not be opened. Select it again." }.use {
                        applicationContext.gameVault.importGame(it, name)
                    }
                }
                loadLibrary()
                notice = if (result.alreadyPresent) "${result.game.app.label} is already in your library."
                    else "${result.game.app.label} was added."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice = when (e) {
                    is IllegalArgumentException -> e.message ?: "This APK could not be imported."
                    is SecurityException -> "Access to this file was lost. Select it again."
                    else -> "The game could not be saved. Check your free space and try again."
                }
            } finally {
                state = state.copy(importing = false)
            }
        }
    }

    fun remove(game: ImportedGame) {
        if (state.importing) return
        state = state.copy(importing = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { applicationContext.gameVault.remove(game.id) }
                loadLibrary()
                notice = "${game.app.label} was removed from Bridge."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice = "The saved copy could not be removed. Please try again."
            } finally {
                state = state.copy(importing = false)
            }
        }
    }

    fun clearNotice() { notice = null }

    private suspend fun loadLibrary() {
        val snapshot = withContext(Dispatchers.IO) { applicationContext.gameVault.load() }
        state = state.copy(games = snapshot.games, unreadableCount = snapshot.unreadableCount, loading = false, error = null)
    }
}
