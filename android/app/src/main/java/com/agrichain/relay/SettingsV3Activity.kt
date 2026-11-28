package com.agrichain.relay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrichain.databinding.ActivitySettingsV3Binding
import com.agrichain.net.LedgerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ledger host, admin token, actor registration and transport preferences. */
class SettingsV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsV3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.roleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("farmer", "buyer", "box", "gateway"),
        )
        binding.regionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            V3Dictionaries.regions.map { it.second },
        )
        binding.saveButton.setOnClickListener { save(restart = true) }
        binding.pinButton.setOnClickListener { pinLedgerKey() }
        binding.registerButton.setOnClickListener { registerActor() }
        load()
    }

    private fun load() {
        val settings = V3Settings(this)
        binding.baseUrlInput.setText(settings.baseUrl(this))
        binding.adminTokenInput.setText(settings.adminToken(this))
        binding.nameInput.setText(settings.name)
        binding.nickInput.setText(settings.nick)
        binding.phoneInput.setText(settings.phone)
        binding.ttlInput.setText(settings.meshTtl.toString())
        binding.gatewaySwitch.isChecked = settings.gatewayEnabled
        binding.smsSwitch.isChecked = settings.smsEnabled
        binding.smsGatewayInput.setText(settings.smsGateway)
        binding.smsGatewaySwitch.isChecked = settings.smsGatewayMode
        // Wi-Fi and BLE mesh always run together; keep the switch visible for
        // compatibility but make the always-on state explicit in the UI.
        binding.bleSwitch.isChecked = true
        binding.bleSwitch.isEnabled = false
        binding.loraSwitch.isChecked = settings.loraEnabled
        binding.roleSpinner.setSelection(
            listOf("farmer", "buyer", "box", "gateway").indexOf(settings.role).coerceAtLeast(0),
        )
        binding.regionSpinner.setSelection(
            (settings.region - 1).coerceIn(0, V3Dictionaries.regions.size - 1),
        )
        val identity = runCatching { V3Identity.loadOrCreate(this) }.getOrNull()
        binding.actorText.text = if (identity == null) {
            "Identity unavailable"
        } else {
            "actor ${identity.actorHex}\npubkey ${identity.pubkeyHex.take(24)}…"
        }
        binding.statusText.text = "Relay: ${RelayRuntime.status.value}"
    }

    private fun save(restart: Boolean): Boolean {
        return runCatching {
            val settings = V3Settings(this)
            LedgerSettings.setBaseUrl(this, binding.baseUrlInput.text.toString())
            LedgerSettings.setAdminToken(this, binding.adminTokenInput.text.toString())
            settings.name = binding.nameInput.text.toString()
            settings.nick = binding.nickInput.text.toString()
            settings.phone = binding.phoneInput.text.toString()
            settings.role = binding.roleSpinner.selectedItem.toString()
            settings.region = V3Dictionaries.regions[binding.regionSpinner.selectedItemPosition].first
            settings.meshTtl = binding.ttlInput.text.toString().toIntOrNull() ?: 7
            settings.gatewayEnabled = binding.gatewaySwitch.isChecked
            settings.smsEnabled = binding.smsSwitch.isChecked
            settings.smsGateway = binding.smsGatewayInput.text.toString()
            settings.smsGatewayMode = binding.smsGatewaySwitch.isChecked
            settings.bleEnabled = true
            settings.loraEnabled = binding.loraSwitch.isChecked
            settings.caps = V3Settings.CAP_WIFI or V3Settings.CAP_BLE or
                (if (settings.gatewayEnabled) V3Settings.CAP_GATEWAY else 0) or
                (if (settings.smsEnabled || settings.smsGatewayMode) V3Settings.CAP_SMS else 0) or
                (if (settings.loraEnabled) V3Settings.CAP_LORA else 0)
            binding.statusText.text = "Saved"
            if (restart && RelayRuntime.status.value == "running") {
                RelayRuntime.stopService(this)
                Handler(Looper.getMainLooper()).postDelayed(
                    { RelayRuntime.startService(this) },
                    700,
                )
            }
            true
        }.getOrElse { error ->
            binding.statusText.text = "Save failed: ${error.message}"
            false
        }
    }

    private fun pinLedgerKey() {
        if (!save(restart = false)) return
        binding.statusText.text = "Fetching ledger key..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { V3Settings(this@SettingsV3Activity).fetchAndPinLedgerKey(this@SettingsV3Activity) }
            }
            result.onSuccess { pubkey ->
                binding.statusText.text = "Pinned ledger key ${pubkey.take(24)}…"
            }.onFailure { error ->
                binding.statusText.text = "Pin failed: ${error.message}"
            }
        }
    }

    private fun registerActor() {
        if (!save(restart = false)) return
        binding.statusText.text = "Registering actor..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val settings = V3Settings(this@SettingsV3Activity)
                    val identity = V3Identity.loadOrCreate(this@SettingsV3Activity)
                    V3Registration.register(
                        baseUrl = settings.baseUrl(this@SettingsV3Activity),
                        adminToken = settings.adminToken(this@SettingsV3Activity),
                        identity = identity,
                        role = settings.role,
                        name = settings.name.ifBlank { settings.nick.ifBlank { "actor" } },
                        region = settings.region,
                        phone = settings.phone,
                    )
                    settings.registered = true
                    identity
                }
            }
            result.onSuccess { identity ->
                binding.statusText.text = "Registered ${identity.actorHex}"
            }.onFailure { error ->
                binding.statusText.text = "Registration failed: ${error.message}"
            }
        }
    }
}
