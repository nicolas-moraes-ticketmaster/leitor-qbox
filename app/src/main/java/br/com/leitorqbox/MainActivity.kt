package br.com.leitorqbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.com.leitorqbox.QboxClient.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvHeader: TextView
    private lateinit var tvSubHeader: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var tvResultExtra: TextView
    private lateinit var tvCounters: TextView
    private lateinit var tvHistory: TextView
    private lateinit var etManual: EditText

    private var busy = false
    private var lastCode: String? = null
    private var lastCodeAt = 0L
    private var okCount = 0
    private var deniedCount = 0
    private val history = ArrayDeque<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val resetToIdle = Runnable { showIdle() }
    private var tone: ToneGenerator? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(DataWedge.EXTRA_DATA)?.let { handleCode(it.trim()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        prefs.applyTestDefaultsOnce()

        tvHeader = findViewById(R.id.tvHeader)
        tvSubHeader = findViewById(R.id.tvSubHeader)
        resultPanel = findViewById(R.id.resultPanel)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultDetail = findViewById(R.id.tvResultDetail)
        tvResultExtra = findViewById(R.id.tvResultExtra)
        tvCounters = findViewById(R.id.tvCounters)
        tvHistory = findViewById(R.id.tvHistory)
        etManual = findViewById(R.id.etManual)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btnScan).setOnClickListener { DataWedge.softTrigger(this) }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendManual() }
        etManual.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendManual(); true } else false
        }

        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        DataWedge.configureProfile(this)
        updateCounters()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            scanReceiver,
            IntentFilter(DataWedge.SCAN_ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            ContextCompat.RECEIVER_EXPORTED, // o broadcast vem do app DataWedge
        )
        updateHeader()
        showIdle()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
        handler.removeCallbacks(resetToIdle)
    }

    override fun onDestroy() {
        super.onDestroy()
        tone?.release()
    }

    private fun sendManual() {
        val code = etManual.text.toString().trim()
        if (code.isNotEmpty()) {
            etManual.setText("")
            handleCode(code)
        }
    }

    private fun handleCode(code: String) {
        if (code.isEmpty() || busy) return
        if (!prefs.isConfigured()) {
            showIdle()
            return
        }
        // Evita dupla leitura acidental do mesmo QR.
        val now = SystemClock.elapsedRealtime()
        if (code == lastCode && now - lastCodeAt < 1500) return
        lastCode = code
        lastCodeAt = now

        busy = true
        handler.removeCallbacks(resetToIdle)
        showPanel(R.color.processing, "VALIDANDO…", code.take(48), "")

        val cfg = prefs.config()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { QboxClient.scan(cfg, code) }
            busy = false
            render(result, cfg.checkOnly)
        }
    }

    private fun render(result: ScanResult, checkOnly: Boolean) {
        when (result) {
            is ScanResult.Valid -> {
                val title = when {
                    result.master -> "CÓDIGO MESTRE"
                    checkOnly -> "VÁLIDO (consulta)"
                    else -> "LIBERADO"
                }
                val color = if (result.master) R.color.master else R.color.valid
                showPanel(color, title, result.info.holder ?: "", joinInfo(result.info))
                okCount++
                feedback(ok = true)
                addHistory("✓ $title", result.info.holder)
            }
            is ScanResult.Rejected -> {
                val (title, hint) = describeReason(result)
                val extra = listOfNotNull(hint, joinInfo(result.info).ifEmpty { null })
                    .joinToString("\n")
                val color = if (result.reason == "SHOW_NOT_OPEN") R.color.warning else R.color.denied
                showPanel(color, title, result.info.holder ?: "", extra)
                deniedCount++
                feedback(ok = false)
                addHistory("✗ $title", result.info.holder)
            }
            ScanResult.AuthError -> {
                val cfg = prefs.config()
                showPanel(
                    R.color.warning, "ERRO DE CONFIGURAÇÃO", "Show ID ou Token recusados pelo Q-Box",
                    "${cfg.baseUrl}\nShow '${cfg.showId}' · token ${cfg.token.length} caract. …${cfg.token.takeLast(4)}",
                )
                feedback(ok = false)
                addHistory("! AUTENTICAÇÃO", null)
            }
            is ScanResult.ServerError -> {
                if (result.httpCode == 404) {
                    showPanel(R.color.warning, "NÃO SUPORTADO", "Este Q-Box não tem essa função", if (checkOnly) "Desligue o modo consulta nas configurações" else "Verifique a versão do Q-Box")
                } else {
                    showPanel(R.color.warning, "ERRO NO Q-BOX", "HTTP ${result.httpCode}", "Tente ler novamente em alguns segundos")
                }
                feedback(ok = false)
                addHistory("! ERRO ${result.httpCode}", null)
            }
            is ScanResult.NetworkError -> {
                showPanel(R.color.warning, "SEM CONEXÃO", "Não foi possível falar com o Q-Box", "${prefs.config().baseUrl}\n${result.detail}")
                feedback(ok = false)
                addHistory("! SEM CONEXÃO", null)
                // Libera reler o mesmo código logo depois de uma falha de rede.
                lastCode = null
            }
        }
        updateCounters()
        handler.postDelayed(resetToIdle, 4000)
    }

    private fun describeReason(r: ScanResult.Rejected): Pair<String, String?> = when (r.reason) {
        "USED" -> {
            val where = listOfNotNull(r.usedDate?.let { "às $it" }, r.usedGate?.let { "em $it" })
                .joinToString(" ")
            val same = if (r.sameGate) " (neste mesmo portão)" else ""
            "JÁ UTILIZADO" to "Usado $where$same".trim()
        }
        "VOID" -> "INGRESSO ANULADO" to null
        "INVALID_SECTOR" -> "SETOR INVÁLIDO" to "Ingresso não é deste portão"
        "INVALID_QUENTRO_CODE" -> "CÓDIGO INVÁLIDO" to "Assinatura inválida: possível falsificação ou leitura ruim"
        "INVALID_ACL" -> "CÓDIGO BLOQUEADO" to "Está em lista negra do show"
        "DENIED" -> "ACESSO NEGADO" to "Negado pelas regras do show"
        "SHOW_NOT_OPEN" -> "SHOW NÃO ABERTO" to "Validação pausada ou fora do horário"
        "ACCESS_NOT_FOUND" -> "NÃO ENCONTRADO" to "Ingresso não existe no Q-Box (ainda não sincronizado?)"
        else -> "NEGADO" to r.reason
    }

    private fun joinInfo(info: QboxClient.TicketInfo) =
        listOfNotNull(info.sector, info.document).joinToString(" · ")

    private fun showIdle() {
        if (!prefs.isConfigured()) {
            showPanel(R.color.idle, "CONFIGURAR", "Toque na engrenagem para informar o Q-Box", "")
        } else {
            val mode = if (prefs.checkOnly) "Modo CONSULTA (não marca como usado)" else ""
            showPanel(R.color.idle, "PRONTO", "Aponte e aperte o gatilho", mode)
        }
    }

    private fun showPanel(colorRes: Int, title: String, detail: String, extra: String) {
        resultPanel.background = GradientDrawable().apply {
            cornerRadius = 20 * resources.displayMetrics.density
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
        }
        tvResultTitle.text = title
        tvResultDetail.text = detail
        tvResultExtra.text = extra
    }

    private fun updateHeader() {
        tvHeader.text = if (prefs.isConfigured()) {
            val mode = if (prefs.checkOnly) "CONSULTA" else "VALIDAÇÃO"
            "${prefs.gate} · $mode"
        } else {
            getString(R.string.app_name)
        }
        val selected = prefs.sectorIds
        val names = prefs.sectorList.filter { it.id in selected }.map { it.name }
        val sectors = when {
            selected.isEmpty() -> "todos os setores"
            names.isNotEmpty() -> names.joinToString(", ")
            else -> "setores ${selected.joinToString(",")}"
        }
        tvSubHeader.text = if (prefs.isConfigured()) {
            "Show ${prefs.showId} · $sectors\n${prefs.config().baseUrl}"
        } else {
            "Não configurado"
        }
    }

    private fun updateCounters() {
        tvCounters.text = "Liberados: $okCount    Negados: $deniedCount"
    }

    private fun addHistory(status: String, holder: String?) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        history.addFirst(listOfNotNull(time, status, holder).joinToString("  "))
        while (history.size > 6) history.removeLast()
        tvHistory.text = history.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    private fun feedback(ok: Boolean) {
        if (ok) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } else {
            tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 600)
        }
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val pattern = if (ok) longArrayOf(0, 80) else longArrayOf(0, 200, 100, 200, 100, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun openSettings() {
        val pin = prefs.pin
        if (pin.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN do supervisor")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == pin) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    showPanel(R.color.denied, "PIN INCORRETO", "", "")
                    handler.postDelayed(resetToIdle, 2000)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
