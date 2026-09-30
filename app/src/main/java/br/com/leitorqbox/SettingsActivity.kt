package br.com.leitorqbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etHost: EditText
    private lateinit var etShowId: EditText
    private lateinit var etToken: EditText
    private lateinit var etGate: EditText
    private lateinit var etPin: EditText
    private lateinit var swCheckOnly: SwitchCompat
    private lateinit var tvTestResult: TextView
    private lateinit var sectorsBox: LinearLayout

    private var sectors: List<QboxClient.Sector> = emptyList()

    // Lendo o QR de pareamento da Crowder (QTR__<id>__<token>) preenche Show ID e Token.
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra(DataWedge.EXTRA_DATA)?.trim() ?: return
            val parts = data.split("__")
            if (parts.size == 3 && parts[0] == "QTR") {
                etShowId.setText(parts[1])
                etToken.setText(parts[2])
                showTestResult(null, "Show ${parts[1]} lido do QR. Toque em Testar conexão.")
            } else {
                showTestResult(false, "QR não é de pareamento (esperado QTR__id__token)")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            scanReceiver,
            IntentFilter(DataWedge.SCAN_ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        etHost = findViewById(R.id.etHost)
        etShowId = findViewById(R.id.etShowId)
        etToken = findViewById(R.id.etToken)
        etGate = findViewById(R.id.etGate)
        etPin = findViewById(R.id.etPin)
        swCheckOnly = findViewById(R.id.swCheckOnly)
        tvTestResult = findViewById(R.id.tvTestResult)
        sectorsBox = findViewById(R.id.sectorsBox)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.tvTitle).text = "Configurações · v$version"

        etHost.setText(prefs.host)
        etShowId.setText(prefs.showId)
        etToken.setText(prefs.token)
        etGate.setText(prefs.gate)
        etPin.setText(prefs.pin)
        swCheckOnly.isChecked = prefs.checkOnly

        sectors = prefs.sectorList
        renderSectors(prefs.sectorIds.toSet())

        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun formConfig() = QboxClient.Config(
        baseUrl = Prefs.normalizeBaseUrl(etHost.text.toString()),
        showId = Prefs.cleanShowId(etShowId.text.toString()),
        token = Prefs.cleanToken(etToken.text.toString()),
        gate = etGate.text.toString().trim(),
        sectorIds = emptyList(),
        checkOnly = true,
    )

    private fun testConnection() {
        val cfg = formConfig()
        if (cfg.baseUrl.isEmpty() || cfg.showId.isEmpty() || cfg.token.isEmpty()) {
            showTestResult(false, "Preencha endereço, Show ID e Token")
            return
        }
        showTestResult(null, "Conectando em ${cfg.baseUrl}…")
        val keepChecked = checkedSectorIds().toSet()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.fetchSectors(cfg) } }
            result.onSuccess {
                sectors = it
                renderSectors(keepChecked)
                showTestResult(true, "Conectado! ${it.size} setor(es) carregado(s).")
            }.onFailure {
                showTestResult(false, "Falhou: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun renderSectors(checked: Set<Int>) {
        sectorsBox.removeAllViews()
        if (sectors.isEmpty()) {
            sectorsBox.addView(TextView(this).apply {
                text = "Toque em \"Testar conexão\" para carregar os setores do show."
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            })
            return
        }
        sectors.forEach { s ->
            sectorsBox.addView(CheckBox(this).apply {
                text = "${s.name}  (#${s.id})"
                tag = s.id
                isChecked = s.id in checked
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            })
        }
    }

    private fun checkedSectorIds(): List<Int> =
        (0 until sectorsBox.childCount)
            .mapNotNull { sectorsBox.getChildAt(it) as? CheckBox }
            .filter { it.isChecked }
            .map { it.tag as Int }

    private fun showTestResult(ok: Boolean?, message: String) {
        val color = when (ok) {
            true -> R.color.valid
            false -> R.color.denied
            null -> R.color.text_secondary
        }
        tvTestResult.setTextColor(ContextCompat.getColor(this, color))
        tvTestResult.text = message
    }

    private fun save() {
        val cfg = formConfig()
        if (cfg.baseUrl.isEmpty() || cfg.showId.isEmpty() || cfg.token.isEmpty() || cfg.gate.isEmpty()) {
            Toast.makeText(this, "Endereço, Show ID, Token e Nome do leitor são obrigatórios", Toast.LENGTH_LONG).show()
            return
        }
        prefs.host = etHost.text.toString().trim()
        prefs.showId = cfg.showId
        prefs.token = cfg.token
        prefs.gate = cfg.gate
        prefs.pin = etPin.text.toString().trim()
        prefs.checkOnly = swCheckOnly.isChecked
        prefs.sectorList = sectors
        prefs.sectorIdsRaw = checkedSectorIds().joinToString(",")
        val msg = if (sectors.isEmpty()) {
            "Salvo, mas sem setores carregados: toque em Testar conexão"
        } else {
            "Configuração salva"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
