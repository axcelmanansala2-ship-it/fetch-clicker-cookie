package com.smartsystem.autoclicker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.smartsystem.autoclicker.databinding.ActivityAccountCheckerBinding
import com.smartsystem.autoclicker.databinding.ItemAccountBinding
import com.smartsystem.autoclicker.models.Account
import com.smartsystem.autoclicker.models.AccountRepository
import com.smartsystem.autoclicker.models.AccountStatus

class AccountCheckerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountCheckerBinding
    private lateinit var repo: AccountRepository
    private lateinit var adapter: AccountAdapter

    private var currentTab = AccountStatus.PENDING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountCheckerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Account Checker"
        }

        repo = AccountRepository(this)
        setupTabs()
        setupList()
        setupAddPanel()
        setupButtons()
        loadCurrentTab()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentTab()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupTabs() {
        listOf("Pending", "Banned", "New Account", "Good").forEach { label ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(label))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    0 -> AccountStatus.PENDING
                    1 -> AccountStatus.BANNED
                    2 -> AccountStatus.NEW_ACCOUNT
                    else -> AccountStatus.GOOD
                }
                binding.addPanel.visibility = if (currentTab == AccountStatus.PENDING) View.VISIBLE else View.GONE
                loadCurrentTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupList() {
        adapter = AccountAdapter(emptyList()) { account ->
            showAccountOptions(account)
        }
        binding.recyclerAccounts.layoutManager = LinearLayoutManager(this)
        binding.recyclerAccounts.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerAccounts.adapter = adapter
    }

    private fun setupAddPanel() {
        binding.btnAddAccounts.setOnClickListener {
            val raw = binding.etAccounts.text?.toString()?.trim() ?: ""
            if (raw.isBlank()) {
                Toast.makeText(this, "Enter accounts (user:pass per line)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repo.parseAndAdd(raw)
            binding.etAccounts.text?.clear()
            loadCurrentTab()
            Toast.makeText(this, "Accounts added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        binding.btnStartChecker.setOnClickListener {
            if (!AutoClickAccessibilityService.isConnected) {
                Toast.makeText(this, "Enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this,
                Intent(this, AccountCheckerService::class.java).apply {
                    action = AccountCheckerService.ACTION_START
                })
            Toast.makeText(this, "Account checker started — check the floating overlay", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnStopChecker.setOnClickListener {
            startService(Intent(this, AccountCheckerService::class.java).apply {
                action = AccountCheckerService.ACTION_STOP
            })
            Toast.makeText(this, "Account checker stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearTab.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear ${currentTab.name.replace('_', ' ')} accounts?")
                .setMessage("This will remove all ${currentTab.name.replace('_', ' ')} accounts from the list.")
                .setPositiveButton("Clear") { _, _ ->
                    repo.clearByStatus(currentTab)
                    loadCurrentTab()
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadCurrentTab() {
        val accounts = repo.getByStatus(currentTab)
        adapter.items = accounts
        adapter.notifyDataSetChanged()

        binding.tvEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = when (currentTab) {
            AccountStatus.PENDING -> "No pending accounts.\nAdd accounts below (user:pass per line)."
            AccountStatus.BANNED -> "No banned accounts yet."
            AccountStatus.NEW_ACCOUNT -> "No new accounts yet."
            AccountStatus.GOOD -> "No good accounts yet."
            else -> ""
        }

        // Update tab badges
        binding.tabLayout.getTabAt(0)?.text = "Pending (${repo.getByStatus(AccountStatus.PENDING).size})"
        binding.tabLayout.getTabAt(1)?.text = "Banned (${repo.getByStatus(AccountStatus.BANNED).size})"
        binding.tabLayout.getTabAt(2)?.text = "New (${repo.getByStatus(AccountStatus.NEW_ACCOUNT).size})"
        binding.tabLayout.getTabAt(3)?.text = "Good (${repo.getByStatus(AccountStatus.GOOD).size})"
    }

    private fun showAccountOptions(account: Account) {
        val options = mutableListOf("Delete")
        if (currentTab != AccountStatus.PENDING) options.add(0, "Move to Pending")

        AlertDialog.Builder(this)
            .setTitle(account.username)
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when (choice) {
                    "Delete" -> {
                        repo.remove(account.id)
                        loadCurrentTab()
                    }
                    "Move to Pending" -> {
                        repo.setStatus(account.id, AccountStatus.PENDING)
                        loadCurrentTab()
                    }
                }
            }
            .show()
    }
}

class AccountAdapter(
    var items: List<Account>,
    private val onClick: (Account) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    inner class VH(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val acc = items[position]
        with(holder.binding) {
            tvUsername.text = acc.username
            tvPasswordMasked.text = "●".repeat(minOf(acc.password.length, 8))
            tvNote.text = acc.note.take(40)
            tvNote.visibility = if (acc.note.isNotBlank()) View.VISIBLE else View.GONE
            val statusColor = when (acc.status) {
                AccountStatus.BANNED -> 0xFFFF5252.toInt()
                AccountStatus.NEW_ACCOUNT -> 0xFFFFD740.toInt()
                AccountStatus.GOOD -> 0xFF69F0AE.toInt()
                AccountStatus.IN_PROGRESS -> 0xFF00E5FF.toInt()
                else -> 0xAAFFFFFF.toInt()
            }
            tvStatus.text = acc.status.name.replace('_', ' ')
            tvStatus.setTextColor(statusColor)
        }
        holder.binding.root.setOnClickListener { onClick(acc) }
    }

    override fun getItemCount() = items.size
}
