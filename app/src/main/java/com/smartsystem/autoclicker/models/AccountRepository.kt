package com.smartsystem.autoclicker.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AccountRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAll(): MutableList<Account> {
        val json = prefs.getString(KEY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Account>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun save(list: List<Account>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun addAll(accounts: List<Account>) {
        val all = getAll()
        all.addAll(accounts)
        save(all)
    }

    fun add(account: Account) {
        val all = getAll()
        all.add(account)
        save(all)
    }

    fun remove(id: String) {
        save(getAll().filter { it.id != id })
    }

    fun setStatus(id: String, status: AccountStatus, note: String = "") {
        val all = getAll()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(status = status, note = note)
            save(all)
        }
    }

    /** Returns the first PENDING account, or null if none left. */
    fun getNextPending(): Account? = getAll().firstOrNull { it.status == AccountStatus.PENDING }

    /** Parse "user:pass" lines — each line is one account. */
    fun parseAndAdd(raw: String) {
        val accounts = raw.lines()
            .map { it.trim() }
            .filter { it.contains(':') && it.isNotBlank() }
            .map { line ->
                val parts = line.split(':', limit = 2)
                Account(username = parts[0].trim(), password = parts[1].trim())
            }
        addAll(accounts)
    }

    fun getByStatus(status: AccountStatus): List<Account> =
        getAll().filter { it.status == status }

    fun clearByStatus(status: AccountStatus) {
        save(getAll().filter { it.status != status })
    }

    companion object {
        private const val PREFS = "account_checker_prefs"
        private const val KEY = "accounts"
    }
}
