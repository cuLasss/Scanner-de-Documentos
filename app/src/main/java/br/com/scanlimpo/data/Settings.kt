package br.com.scanlimpo.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import br.com.scanlimpo.core.imaging.PageFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Escuro"),
}

class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val themeState = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null).orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = themeState.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        themeState.value = mode
        // A partir do Android 12 o sistema guarda a escolha, e a tela de abertura também já sai na cor certa.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    var autoOcr: Boolean
        get() = prefs.getBoolean("auto_ocr", true)
        set(value) = prefs.edit().putBoolean("auto_ocr", value).apply()

    var autoCapture: Boolean
        get() = prefs.getBoolean("auto_capture", true)
        set(value) = prefs.edit().putBoolean("auto_capture", value).apply()

    var defaultFilter: PageFilter
        get() = runCatching { PageFilter.valueOf(prefs.getString("default_filter", null).orEmpty()) }
            .getOrDefault(PageFilter.ORIGINAL)
        set(value) = prefs.edit().putString("default_filter", value.name).apply()

    var lastFormat: String?
        get() = prefs.getString("last_format", null)
        set(value) = prefs.edit().putString("last_format", value).apply()

    /** Documentos por linha na tela inicial. */
    var homeColumns: Int
        get() = prefs.getInt("home_columns", 2)
        set(value) = prefs.edit().putInt("home_columns", value).apply()

    /** Páginas por linha dentro de um documento. */
    var pageColumns: Int
        get() = prefs.getInt("page_columns_v2", 1)
        set(value) = prefs.edit().putInt("page_columns_v2", value).apply()
}
