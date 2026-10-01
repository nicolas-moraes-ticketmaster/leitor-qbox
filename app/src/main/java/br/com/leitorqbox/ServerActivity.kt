package br.com.leitorqbox

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Conexão com o servidor: IP padrão do Q-Box, teste de conexão e aplicar o IP aos eventos. */
class ServerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etHost: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        prefs = Prefs(this)

        etHost = findViewById(R.id.etHost)
        tvStatus = findViewById(R.id.tvStatus)
        etHost.setText(prefs.hostForNewEvent())

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { test() }
        findViewById<MaterialButton>(R.id.btnApplyAll).setOnClickListener { confirmApplyAll() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.defaultHost = etHost.text.toString().trim()
            Toast.makeText(this, "Servidor salvo", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun test() {
        val url = Prefs.normalizeBaseUrl(etHost.text.toString())
        if (url.isEmpty()) {
            show(false, "Informe o IP do Q-Box")
            return
        }
        show(null, "Conectando em $url…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.ping(url) } }
            result.onSuccess { show(true, "Q-Box respondeu em $it ms\n$url") }
                .onFailure { show(false, "Sem resposta de $url\n${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun confirmApplyAll() {
        val host = etHost.text.toString().trim()
        val count = prefs.events.size
        if (host.isEmpty() || count == 0) {
            show(false, if (host.isEmpty()) "Informe o IP do Q-Box" else "Nenhum evento cadastrado")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Aplicar a todos os eventos")
            .setMessage("Os $count evento(s) cadastrados passam a usar $host.")
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.events = prefs.events.map { it.copy(host = host) }
                prefs.defaultHost = host
                show(true, "IP aplicado a $count evento(s)")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun show(ok: Boolean?, message: String) {
        tvStatus.setTextColor(ContextCompat.getColor(this, when (ok) {
            true -> R.color.valid
            false -> R.color.denied
            null -> R.color.text_secondary
        }))
        tvStatus.text = message
    }
}
