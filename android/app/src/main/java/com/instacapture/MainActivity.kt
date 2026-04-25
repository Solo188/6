package com.instacapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MainActivity — главный экран приложения.
 * Проверяет статус Accessibility Service, показывает инструкцию,
 * позволяет тестировать соединение с сервером и управлять списком аккаунтов.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InstaCapture:Main"
        private const val REQUEST_ACCESSIBILITY = 1001
        private const val SERVICE_NAME = "com.instacapture/.InstagramAccessibilityService"
    }

    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvInstructions: MaterialTextView
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnOpenAccessibilitySettings: MaterialButton
    private lateinit var btnLogoutInstagram: MaterialButton
    private lateinit var rvAccounts: RecyclerView
    private lateinit var tvQueueSize: MaterialTextView
    private lateinit var networkManager: NetworkManager
    private lateinit var queueManager: QueueManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        networkManager = NetworkManager(this)
        queueManager = QueueManager(this)

        initViews()
        updateServiceStatus()
        updateQueueSize()
        setupPeriodicSync()

        if (!isAccessibilityServiceEnabled()) {
            showEnableAccessibilityDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateQueueSize()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvInstructions = findViewById(R.id.tvInstructions)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        btnLogoutInstagram = findViewById(R.id.btnLogoutInstagram)
        rvAccounts = findViewById(R.id.rvAccounts)
        tvQueueSize = findViewById(R.id.tvQueueSize)

        tvInstructions.text = buildInstructions()

        btnTestConnection.setOnClickListener { testServerConnection() }
        btnOpenAccessibilitySettings.setOnClickListener { openAccessibilitySettings() }
        btnLogoutInstagram.setOnClickListener { showLogoutWarning() }

        rvAccounts.layoutManager = LinearLayoutManager(this)
        rvAccounts.adapter = AccountsAdapter(loadMockAccounts())
    }

    /**
     * Проверяет, включён ли InstaCapture в настройках Accessibility.
     * Проверяет по полному имени сервиса.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Формат: com.instacapture/com.instacapture.InstagramAccessibilityService
        return enabledServices.contains(SERVICE_NAME) || enabledServices.contains(packageName)
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        tvStatus.text = if (enabled) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_inactive)
        }
        val colorRes = if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateQueueSize() {
        val size = queueManager.getQueueSize()
        tvQueueSize.text = getString(R.string.queue_size_label, size)
    }

    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_accessibility_title)
            .setMessage(R.string.dialog_accessibility_message)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.btn_later) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, R.string.toast_service_required, Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivityForResult(intent, REQUEST_ACCESSIBILITY)
    }

    private fun testServerConnection() {
        btnTestConnection.isEnabled = false
        btnTestConnection.text = getString(R.string.btn_checking)

        networkManager.testConnection { success, message ->
            runOnUiThread {
                btnTestConnection.isEnabled = true
                btnTestConnection.text = getString(R.string.btn_test_connection)

                val title = if (success) R.string.dialog_success else R.string.dialog_error
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showLogoutWarning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                launchInstagramForLogout()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchInstagramForLogout() {
        val intent = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (intent != null) {
            startActivity(intent)
            Toast.makeText(this, R.string.toast_instagram_opened, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.toast_instagram_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "instacapture_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )
    }

    private fun buildInstructions(): String {
        return getString(R.string.instruction_text)
    }

    private fun loadMockAccounts(): List<AccountListItem> {
        return listOf(
            AccountListItem(
                "demo_account",
                "demo@company.com",
                null,
                formatTime(System.currentTimeMillis())
            )
        )
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
