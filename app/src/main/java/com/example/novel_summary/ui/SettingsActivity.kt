package com.example.novel_summary.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.novel_summary.R
import com.example.novel_summary.data.AppDatabase
import com.example.novel_summary.databinding.ActivitySettingsBinding
import com.example.novel_summary.utils.NetworkUtils
import com.example.novel_summary.utils.SyncManager
import com.example.novel_summary.utils.ToastUtils
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import com.example.novel_summary.ui.customai.CustomAiApiManagerActivity
import com.example.novel_summary.utils.customai.CustomAiStore
import kotlinx.coroutines.withTimeout
import android.widget.TextView
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val auth = FirebaseAuth.getInstance()

    // Periodic sync processor job (30-second loop)
    private var syncJob: Job? = null

    // Manual sync job (triggered by user)
    private var manualSyncJob: Job? = null

    // Restore job
    private var restoreJob: Job? = null

    companion object {
        const val PREF_AI_PROVIDER = "last_ai_selection"
        const val PREF_SUMMARY_TYPE = "last_summary_type"

        const val AI_AUTO = "Auto"
        const val AI_GROQ_PRIMARY = "Groq Primary"
        const val AI_GROQ_FALLBACK = "Groq Fallback"
        const val AI_CEREBRAS = "Cerebras"
        const val AI_GEMINI = "Google AI (Gemini)"

        const val SUMMARY_SHORT = "short"
        const val SUMMARY_DETAILED = "detailed"
        const val SUMMARY_VERY_DETAILED = "very_detailed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupClickListeners()
        setupFirebaseListeners()

        if (SyncManager.isSyncEnabled(this)) {
            startSyncProcessor()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSettings() {
        val prefs = SyncManager.getPrefs(this)

        val aiProvider = prefs.getString(PREF_AI_PROVIDER, AI_AUTO) ?: AI_AUTO
        binding.tvAiProvider.text = getProviderDisplayName(aiProvider)

        val summaryType = prefs.getString(PREF_SUMMARY_TYPE, SUMMARY_DETAILED) ?: SUMMARY_DETAILED
        binding.tvSummaryType.text = getSummaryTypeDisplayName(summaryType)

        val syncEnabled = SyncManager.isSyncEnabled(this)
        binding.switchSync.isChecked = syncEnabled
        binding.switchSyncOnSave.isEnabled = syncEnabled

        val syncOnSave = SyncManager.isSyncOnSave(this)
        binding.switchSyncOnSave.isChecked = syncOnSave

        updateAuthStatus()
        updateLastSyncDisplay()
    }

    private fun setupClickListeners() {
        binding.layoutAiProvider?.setOnClickListener {
            showAiProviderOptionsDialog()
        }
        binding.layoutSummaryType?.setOnClickListener {
            showSummaryTypeDialog()
        }

        binding.switchSync?.setOnCheckedChangeListener { _, isChecked ->
            SyncManager.getPrefs(this).edit()
                .putBoolean(SyncManager.PREF_SYNC_ENABLED, isChecked)
                .apply()

            if (isChecked) {
                if (auth.currentUser == null) {
                    showLoginDialog()
                }
                startSyncProcessor()
            } else {
                stopSyncProcessor()
            }

            binding.switchSyncOnSave?.isEnabled = isChecked
            updateAuthStatus()
        }

        binding.switchSyncOnSave?.setOnCheckedChangeListener { _, isChecked ->
            SyncManager.getPrefs(this).edit()
                .putBoolean(SyncManager.PREF_SYNC_ON_SAVE, isChecked)
                .apply()
        }

        binding.btnAuthAction?.setOnClickListener {
            if (auth.currentUser != null) {
                showLogoutDialog()
            } else {
                showLoginDialog()
            }
        }

        binding.btnManualSync?.setOnClickListener {
            if (!NetworkUtils.isNetworkAvailable(this)) {
                ToastUtils.showError(this, "No internet connection")
                return@setOnClickListener
            }
            manualSync()
        }

        binding.btnRestore?.setOnClickListener {
            if (!NetworkUtils.isNetworkAvailable(this)) {
                ToastUtils.showError(this, "No internet connection")
                return@setOnClickListener
            }
            showRestoreDialog()
        }

        binding.btnClearLocal?.setOnClickListener {
            showClearLocalDialog()
        }
    }

    private var isFirstAuthCallback = true
    private var authListener: FirebaseAuth.AuthStateListener? = null

    private fun setupFirebaseListeners() {
        authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            updateAuthStatus()

            if (isFirstAuthCallback) {
                isFirstAuthCallback = false
                // Skip auto-sync on first callback — it fires immediately
                // just to report the current auth state, not a real change
                return@AuthStateListener
            }

            // Only sync on actual login (transition from null → user)
            if (firebaseAuth.currentUser != null && SyncManager.isSyncEnabled(this)) {
                manualSync()
            }
        }

        auth.addAuthStateListener(authListener!!)
    }

    private fun showAiProviderDialog() {
        val prefs = SyncManager.getPrefs(this)

        val builtInProviders = listOf(
            AI_AUTO,
            AI_GROQ_PRIMARY,
            AI_GROQ_FALLBACK,
            AI_CEREBRAS,
            AI_GEMINI
        )

        val customApis = CustomAiStore.getApis(this)

        val rawProviders = builtInProviders + customApis.map { it.name }
        val displayProviders = rawProviders.map { getProviderDisplayName(it) }.toTypedArray()

        val currentProvider = prefs.getString(PREF_AI_PROVIDER, AI_AUTO) ?: AI_AUTO
        val checkedItem = rawProviders.indexOf(currentProvider).let { index ->
            if (index >= 0) index else 0
        }

        AlertDialog.Builder(this)
            .setTitle("Select AI Provider")
            .setSingleChoiceItems(displayProviders, checkedItem) { dialog, which ->
                val selectedProvider = rawProviders[which]

                prefs.edit()
                    .putString(PREF_AI_PROVIDER, selectedProvider)
                    .apply()

                binding.tvAiProvider.text = displayProviders[which]

                dialog.dismiss()

                ToastUtils.showSuccess(this, "AI provider updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAiProviderOptionsDialog() {
        val options = arrayOf(
            "Select AI Provider",
            "Manage Custom AI APIs"
        )

        AlertDialog.Builder(this)
            .setTitle("AI Provider")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAiProviderDialog()
                    1 -> {
                        startActivity(
                            Intent(this, CustomAiApiManagerActivity::class.java)
                        )
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getProviderDisplayName(provider: String): String {
        return when (provider) {
            AI_AUTO -> "Auto (Smart Routing)"
            AI_GROQ_PRIMARY -> "Groq Primary (Llama 3.3 70B)"
            AI_GROQ_FALLBACK -> "Groq Fallback (Llama 3.1 8B)"
            AI_CEREBRAS -> "Cerebras"
            AI_GEMINI -> "Google AI (Gemini)"
            else -> {
                val customApi = CustomAiStore.getApis(this)
                    .firstOrNull { it.name == provider }

                if (customApi != null) {
                    "${customApi.name} (Custom)"
                } else {
                    provider
                }
            }
        }
    }

    private fun showSummaryTypeDialog() {
        val summaryTypes = arrayOf(
            "Short Summary (3-5 bullet points)",
            "Detailed Summary (2-4 paragraphs)",
            "Very Detailed Summary (6-10 paragraphs)"
        )

        val prefs = SyncManager.getPrefs(this)
        val currentType = prefs.getString(PREF_SUMMARY_TYPE, SUMMARY_DETAILED) ?: SUMMARY_DETAILED
        val checkedItem = when (currentType) {
            SUMMARY_SHORT -> 0
            SUMMARY_DETAILED -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle("Select Default Summary Type")
            .setSingleChoiceItems(summaryTypes, checkedItem) { dialog, which ->
                val selectedType = when (which) {
                    0 -> SUMMARY_SHORT
                    1 -> SUMMARY_DETAILED
                    else -> SUMMARY_VERY_DETAILED
                }

                prefs.edit().putString(PREF_SUMMARY_TYPE, selectedType).apply()
                binding.tvSummaryType?.text = summaryTypes[which]
                dialog.dismiss()
                ToastUtils.showSuccess(this, "Default summary type updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSummaryTypeDisplayName(type: String): String {
        return when (type) {
            SUMMARY_SHORT -> "Short Summary (3-5 bullet points)"
            SUMMARY_DETAILED -> "Detailed Summary (2-4 paragraphs)"
            else -> "Very Detailed Summary (6-10 paragraphs)"
        }
    }

    private fun updateAuthStatus() {
        if (auth.currentUser != null) {
            binding.tvAuthStatus?.text = "Signed in as ${auth.currentUser?.email}"
            binding.btnAuthAction?.text = "Logout"
            binding.layoutManualSync?.isVisible = true
            binding.layoutRestore?.isVisible = true
        } else {
            binding.tvAuthStatus?.text = "Not signed in"
            binding.btnAuthAction?.text = "Login"
            binding.layoutManualSync?.isVisible = false
            binding.layoutRestore?.isVisible = false
        }
    }

    private fun showLoginDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_login, null)
        val emailInput = dialogView.findViewById<android.widget.EditText>(R.id.etEmail)
        val passwordInput = dialogView.findViewById<android.widget.EditText>(R.id.etPassword)
        val forgotPasswordText = dialogView.findViewById<TextView>(R.id.tvForgotPassword)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Email Login")
            .setView(dialogView)
            .setPositiveButton("Login", null)
            .setNeutralButton("Create Account", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (email.isEmpty() || password.isEmpty()) {
                    ToastUtils.showError(this, "Please enter email and password")
                    return@setOnClickListener
                }

                loginUser(email, password, dialog)
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (email.isEmpty() || password.isEmpty()) {
                    ToastUtils.showError(this, "Please enter email and password")
                    return@setOnClickListener
                }

                createUser(email, password, dialog)
            }
        }

        forgotPasswordText.setOnClickListener {
            val prefillEmail = emailInput.text.toString().trim()
            showForgotPasswordDialog(prefillEmail)
        }

        dialog.show()
    }

    private fun loginUser(email: String, password: String, parentDialog: AlertDialog) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Logging In")
            .setMessage("Authenticating...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                progressDialog.dismiss()
                parentDialog.dismiss()
                updateAuthStatus()
                ToastUtils.showSuccess(this, "Logged in successfully")
                if (SyncManager.isSyncEnabled(this)) {
                    manualSync()
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                ToastUtils.showError(this, "Login failed: ${e.message}")
            }
    }

    private fun createUser(email: String, password: String, parentDialog: AlertDialog) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Creating Account")
            .setMessage("Setting up your account...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                progressDialog.dismiss()
                parentDialog.dismiss()
                updateAuthStatus()
                ToastUtils.showSuccess(this, "Account created successfully")
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                ToastUtils.showError(this, "Account creation failed: ${e.message}")
            }
    }
    private fun showForgotPasswordDialog(prefillEmail: String) {
        val emailInput = android.widget.EditText(this).apply {
            hint = "your@email.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
            if (prefillEmail.isNotEmpty()) {
                setText(prefillEmail)
                setSelection(prefillEmail.length)
            }
        }

        val container = android.widget.FrameLayout(this).apply {
            setPadding(60, 20, 60, 20)
            addView(emailInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Reset Password")
            .setMessage("Enter your email address. We will send you a password reset link.")
            .setView(container)
            .setPositiveButton("Send Reset Link") { _, _ ->
                val email = emailInput.text.toString().trim()
                if (email.isEmpty()) {
                    ToastUtils.showError(this, "Please enter your email")
                    return@setPositiveButton
                }
                sendPasswordResetEmail(email)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendPasswordResetEmail(email: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Sending Reset Link")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                progressDialog.dismiss()
                ToastUtils.showSuccess(
                    this,
                    "Password reset email sent to $email.\nCheck your inbox (and spam folder)."
                )
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                ToastUtils.showError(this, "Failed to send reset email: ${e.message}")
            }
    }
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout? Sync will pause until you login again.")
            .setPositiveButton("Logout") { _, _ ->
                auth.signOut()
                updateAuthStatus()
                ToastUtils.showSuccess(this, "Logged out successfully")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manualSync() {
        if (auth.currentUser == null) {
            ToastUtils.showError(this, "Please login to sync your data")
            return
        }

        // Cancel any existing manual sync
        manualSyncJob?.cancel()

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Syncing Library")
            .setMessage("Preparing to sync...")
            .setCancelable(true)
            .setOnCancelListener {
                manualSyncJob?.cancel()
                ToastUtils.showShort(this, "Sync cancelled")
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                manualSyncJob?.cancel()
                dialog.dismiss()
                ToastUtils.showShort(this, "Sync cancelled")
            }
            .create()

        progressDialog.show()

        manualSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                withTimeout(90_000L) {
                    SyncManager.syncAllDataToFirebase(this@SettingsActivity) { message ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                progressDialog.setMessage(message)
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        SyncManager.updateLastSyncTime(this@SettingsActivity)
                        updateLastSyncDisplay()
                        ToastUtils.showSuccess(this@SettingsActivity, "✓ Sync completed successfully")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        ToastUtils.showError(this@SettingsActivity, "Sync timed out. Check your internet connection and Google Play Services.")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        ToastUtils.showError(this@SettingsActivity, "Sync failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showRestoreDialog() {
        AlertDialog.Builder(this)
            .setTitle("Restore from Cloud")
            .setMessage("This will merge your cloud library with local data. Existing items will be updated, new items will be added.")
            .setPositiveButton("Restore Now") { _, _ ->
                restoreFromCloud()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreFromCloud() {
        if (auth.currentUser == null) {
            ToastUtils.showError(this, "Please login first")
            return
        }

        // Cancel any existing restore
        restoreJob?.cancel()

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Restoring Library")
            .setMessage("Preparing to restore...")
            .setCancelable(true)
            .setOnCancelListener {
                restoreJob?.cancel()
                ToastUtils.showShort(this, "Restore cancelled")
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                restoreJob?.cancel()
                dialog.dismiss()
            }
            .create()

        progressDialog.show()

        restoreJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                withTimeout(120_000L) {
                    SyncManager.restoreDataFromFirebase(this@SettingsActivity) { message ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                progressDialog.setMessage(message)
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        SyncManager.updateLastSyncTime(this@SettingsActivity)
                        updateLastSyncDisplay()
                        ToastUtils.showSuccess(this@SettingsActivity, "✓ Data restored successfully!")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        ToastUtils.showError(this@SettingsActivity, "Restore timed out. Check your connection.")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressDialog.dismiss()
                        ToastUtils.showError(this@SettingsActivity, "Restore failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showClearLocalDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Local Data")
            .setMessage("This will delete ALL local novels, volumes, chapters, and bookmarks. Your cloud data will remain safe. This action cannot be undone!")
            .setPositiveButton("Clear All Data") { _, _ ->
                clearLocalData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearLocalData() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Clearing Data")
            .setMessage("Deleting all local content...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbInstance = AppDatabase.getDatabase(this@SettingsActivity)
                dbInstance.chapterDao().deleteAllChapters()
                dbInstance.volumeDao().deleteAllVolumes()
                dbInstance.novelDao().deleteAllNovels()
                dbInstance.bookmarkDao().deleteAllBookmarks()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    ToastUtils.showSuccess(this@SettingsActivity, "✓ All local data cleared")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    ToastUtils.showError(this@SettingsActivity, "Clear failed: ${e.message}")
                }
            }
        }
    }

    private fun startSyncProcessor() {
        stopSyncProcessor()
        syncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                // Process sync queue if needed
            }
        }
    }

    private fun stopSyncProcessor() {
        syncJob?.cancel()
        syncJob = null
    }

    private fun updateLastSyncDisplay() {
        val lastSync = SyncManager.getLastSyncTime(this)
        if (lastSync > 0) {
            val diff = (System.currentTimeMillis() - lastSync) / 1000
            val display = when {
                diff < 60 -> "Just now"
                diff < 3600 -> "${diff / 60} minutes ago"
                diff < 86400 -> "${diff / 3600} hours ago"
                else -> "${diff / 86400} days ago"
            }
            binding.tvLastSync?.text = "Last sync: $display"
            binding.tvLastSync?.isVisible = true
        } else {
            binding.tvLastSync?.isVisible = false
        }
    }

    override fun onDestroy() {
        authListener?.let { auth.removeAuthStateListener(it) }
        authListener = null
        stopSyncProcessor()
        manualSyncJob?.cancel()
        restoreJob?.cancel()
        super.onDestroy()
    }
}