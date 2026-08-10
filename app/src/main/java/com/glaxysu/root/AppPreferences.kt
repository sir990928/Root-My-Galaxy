package com.glaxysu.root

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes

enum class AccentColor(val storedValue: String) {
    Dynamic("dynamic"),
    Glaxy("glaxy"),
    Blue("blue"),
    Violet("violet"),
    Green("green"),
    Orange("orange");

    companion object {
        fun fromStoredValue(value: String?): AccentColor =
            entries.firstOrNull { it.storedValue == value } ?: Glaxy
    }
}

enum class AppThemeMode(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

enum class RootManager(
    val storedValue: String,
    val manifestKey: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    KernelSU(
        storedValue = "KernelSU",
        manifestKey = "kernelsu",
        labelRes = R.string.root_manager_kernelsu,
        descriptionRes = R.string.root_manager_kernelsu_description,
    ),
    SukiSU(
        storedValue = "SukiSU-Ultra",
        manifestKey = "sukisu",
        labelRes = R.string.root_manager_sukisu,
        descriptionRes = R.string.root_manager_sukisu_description,
    );

    companion object {
        fun fromStoredValue(value: String?): RootManager =
            entries.firstOrNull { it.storedValue == value } ?: KernelSU
    }
}

object AppPreferences {
    private const val PREFERENCES = "appearance"
    private const val ACCENT_COLOR = "accent_color"
    private const val THEME_MODE = "theme_mode"
    private const val ADVANCED_MODE = "advanced_mode"
    private const val ROOT_MANAGER = "root_manager"
    private const val WIRELESS_ADB_PAIRED = "wireless_adb_paired"
    private const val WIRELESS_ADB_MODE = "wireless_adb_mode"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"

    fun accentColor(context: Context): AccentColor = AccentColor.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ACCENT_COLOR, null),
    )

    fun setAccentColor(context: Context, color: AccentColor) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(ACCENT_COLOR, color.storedValue).apply()
    }

    fun themeMode(context: Context): AppThemeMode = AppThemeMode.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(THEME_MODE, null),
    )

    fun setThemeMode(context: Context, themeMode: AppThemeMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(THEME_MODE, themeMode.storedValue).apply()
    }

    fun advancedMode(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ADVANCED_MODE, false)

    fun setAdvancedMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ADVANCED_MODE, enabled).apply()
    }

    fun rootManager(context: Context): RootManager = RootManager.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ROOT_MANAGER, RootManager.KernelSU.storedValue),
    )

    fun setRootManager(context: Context, manager: RootManager) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(ROOT_MANAGER, manager.storedValue).apply()
    }

    fun wirelessAdbPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(WIRELESS_ADB_PAIRED, false)

    fun setWirelessAdbPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(WIRELESS_ADB_PAIRED, paired).commit()
    }

    fun wirelessAdbMode(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(WIRELESS_ADB_MODE, false)

    fun setWirelessAdbMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(WIRELESS_ADB_MODE, enabled).apply()
    }

    @Synchronized
    fun consumeInstallRequest(context: Context, requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(CONSUMED_INSTALL_REQUEST, null) == requestId) return false
        return preferences.edit().putString(CONSUMED_INSTALL_REQUEST, requestId).commit()
    }

    fun languageTag(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    fun setLanguage(context: Context, languageTag: String) {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(languageTag)
    }
}