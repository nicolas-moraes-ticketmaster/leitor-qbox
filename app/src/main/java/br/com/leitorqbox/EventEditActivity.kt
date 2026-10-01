package br.com.leitorqbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cadastro/edição de um evento. Show ID e Token podem vir do QR (câmera ou gatilho). */
class EventEditActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etShowId: EditText
    private lateinit var etToken: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvSectors: TextView

    private var editing: Event? = null
    private var sectors: List<QboxClient.Sector> = emptyList()

    private val cameraScan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyQr(it) }
    }

    // O gatilho físico do TC22 também lê o QR do evento nesta tela.
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(DataWedge.EXTRA_DATA)?.let { applyQr(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_edit)
        prefs = Prefs(this)

        etName = findViewById(R.id.etName)
        etHost = findViewById(R.id.etHost)
        etShowId = findViewById(R.id.etShowId)
        etToken = findViewById(R.id.etToken)
        tvStatus = findViewById(R.id.tvStatus)
        tvSectors = findViewById(R.id.tvSectors)

        editing = intent.getStringExtra(EXTRA_EVENT_ID)?.let { id -> prefs.events.firstOrNull { it.id == id } }
        val e = editing
        findViewById<TextView>(R.id.tvTitle).text = if (e == null) "Cadastrar evento" else "Editar evento"
        if (e != null) {
            etName.setText(e.name)
            etHost.setText(e.host)
            etShowId.setText(e.showId)
            etToken.setText(e.token)
            sectors = e.sectors
        } else {
            // Novo evento: normalmente é o mesmo Q-Box do evento atual.
            etHost.setText(prefs.activeEvent?.host.orEmpty())
        }
        renderSectors()

        findViewById<Button>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SCAN_NOW, false)) openCamera()
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

    private fun openCamera() {
        cameraScan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Aponte para o QR do evento")
                .setBeepEnabled(true)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
        )
    }

    private fun applyQr(raw: String) {
        when (val r = EventQr.parse(raw)) {
            is EventQr.Result.Ok -> {
                val p = r.parsed
                etShowId.setText(p.showId)
                etToken.setText(p.token)
                p.host?.let { etHost.setText(it) }
                if (!p.name.isNullOrBlank()) etName.setText(p.name)
                else if (etName.text.isBlank()) etName.setText(defaultName(p.showId))
                showStatus(null, "QR lido:\nShow ID: ${p.showId}\nToken: ${p.token.take(6)}…${p.token.takeLast(4)} (${p.token.length} caract.)")
                if (etHost.text.isNotBlank()) testConnection()
            }
            is EventQr.Result.Error -> showStatus(false, r.message)
        }
    }

    private fun formEvent(): Event {
        val base = editing ?: Event(name = "", host = "", showId = "", token = "")
        val showId = Prefs.cleanShowId(etShowId.text.toString())
        return base.copy(
            name = etName.text.toString().trim().ifEmpty { defaultName(showId) },
            host = etHost.text.toString().trim(),
            showId = showId,
            token = Prefs.cleanToken(etToken.text.toString()),
        ).withSectors(sectors)
    }

    private fun testConnection() {
        val event = formEvent()
        if (!event.isComplete()) {
            showStatus(false, "Preencha IP do Q-Box, Show ID e Token")
            return
        }
        val cfg = prefs.config(event)
        showStatus(null, "Conectando em ${cfg.baseUrl}…")
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.fetchSectors(cfg) } }
            val ms = SystemClock.elapsedRealtime() - start
            result.onSuccess {
                sectors = it
                renderSectors()
                val kind = if (event.isVirtual) "Evento VIRTUAL" else "Show"
                showStatus(true, "Conectado em $ms ms. $kind com ${it.size} setor(es).")
            }.onFailure {
                showStatus(false, "Falhou: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun renderSectors() {
        tvSectors.text = if (sectors.isEmpty()) {
            "Setores: ainda não carregados (toque em Testar conexão)"
        } else {
            "Setores:\n" + sectors.joinToString("\n") { "• ${it.name}  (#${it.id})" }
        }
    }

    private fun save() {
        val event = formEvent()
        if (!event.isComplete()) {
            Toast.makeText(this, "IP do Q-Box, Show ID e Token são obrigatórios", Toast.LENGTH_LONG).show()
            return
        }
        prefs.upsert(event)
        prefs.activeEventId = event.id
        Toast.makeText(this, "Evento salvo e ativado", Toast.LENGTH_SHORT).show()
        // Volta direto para a tela de leitura.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun showStatus(ok: Boolean?, message: String) {
        val color = when (ok) {
            true -> R.color.valid
            false -> R.color.denied
            null -> R.color.text_secondary
        }
        tvStatus.setTextColor(ContextCompat.getColor(this, color))
        tvStatus.text = message
    }

    private fun defaultName(showId: String) =
        if (showId.startsWith("V-", ignoreCase = true)) "Evento $showId" else "Show $showId"

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SCAN_NOW = "scan_now"
    }
}
