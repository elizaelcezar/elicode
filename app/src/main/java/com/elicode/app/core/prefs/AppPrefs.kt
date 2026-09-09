package com.elicode.app.core.prefs

import android.content.Context
import android.content.SharedPreferences

/** Simple app settings backed by SharedPreferences. Secrets live in KeystoreStore. */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("elicode_prefs", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()

    var currentProjectPath: String
        get() = prefs.getString("current_project", "").orEmpty()
        set(v) = prefs.edit().putString("current_project", v).apply()

    var themeMode: String
        /** "system" | "light" | "dark" */
        get() = prefs.getString("theme_mode", "system").orEmpty().ifBlank { "system" }
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    var editorFontSize: Float
        get() = prefs.getFloat("editor_font_sp", 14f)
        set(v) = prefs.edit().putFloat("editor_font_sp", v).apply()

    var editorTabWidth: Int
        get() = prefs.getInt("editor_tab_width", 4)
        set(v) = prefs.edit().putInt("editor_tab_width", v).apply()

    var runtimeSetupVersion: Int
        get() = prefs.getInt("runtime_setup_version", 0)
        set(v) = prefs.edit().putInt("runtime_setup_version", v).apply()

    var opencodeModel: String
        get() = prefs.getString("opencode_model", "").orEmpty()
        set(v) = prefs.edit().putString("opencode_model", v).apply()

    var lastPreviewPort: Int
        get() = prefs.getInt("last_preview_port", 8080)
        set(v) = prefs.edit().putInt("last_preview_port", v).apply()

    var githubUser: String
        get() = prefs.getString("github_user", "").orEmpty()
        set(v) = prefs.edit().putString("github_user", v).apply()
}
