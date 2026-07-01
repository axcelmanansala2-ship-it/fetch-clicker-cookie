package com.smartsystem.autoclicker.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists detection targets in SharedPreferences as JSON.
 */
class TargetRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAll(): MutableList<DetectionTarget> {
        val json = prefs.getString(KEY_TARGETS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<DetectionTarget>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun save(targets: List<DetectionTarget>) {
        prefs.edit().putString(KEY_TARGETS, gson.toJson(targets)).apply()
    }

    fun add(target: DetectionTarget) {
        val list = getAll()
        list.add(target)
        save(list)
    }

    fun remove(id: String) {
        val list = getAll().filter { it.id != id }
        save(list)
    }

    companion object {
        private const val PREFS_NAME = "smart_auto_clicker_prefs"
        private const val KEY_TARGETS = "detection_targets"
    }
}
