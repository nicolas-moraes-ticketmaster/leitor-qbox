package br.com.leitorqbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvEvent: TextView
    private lateinit var tvHeader: TextView
    private lateinit var tvSync: TextView
    private lateinit var chipSectors: ChipGroup
    private lateinit var tvSectorsHint: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var tvResultExtra: TextView
    private lateinit var tvCounters: TextView
    private lateinit var tvHistory: TextView
    private lateinit var etManual: EditText

    private var busy = false
    private var syncing = false
    private var lastCode: String? = null
    private var lastCodeAt = 0L
    private var okCount = 0
    private var deniedCount = 0
    private val history = ArrayDeque<String>()
    // Evento (e conexão) já sincronizado; muda ao trocar de evento ou editar IP/ID/Token.
    private var syncedKey: String? = null
    private var unlockedUntil = 0L

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
        prefs.seedOnce()

        tvEvent = findViewById(R.id.tvEvent)
        tvHeader = findViewById(R.id.tvHeader)
        tvSync = findViewById(R.id.tvSync)
        chipSectors = findViewById(R.id.chipSectors)
        tvSectorsHint = findViewById(R.id.tvSectorsHint)
        resultPanel = findViewById(R.id.resultPanel)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultDetail = findViewById(R.id.tvResultDetail)
        tvResultExtra = findViewById(R.id.tvResultExtra)
        tvCounters = findViewById(R.id.tvCounters)
        tvHistory = findViewById(R.id.tvHistory)
        etManual = findViewById(R.id.etManual)

        tvEvent.setOnClickListener { withPin { startActivity(Intent(this, EventsActivity::class.java)) } }
        findViewById<ImageButton>(R.id.btnSync).setOnClickListener { syncWithServer(manual = true) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            withPin { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
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
        renderEvent()
        showIdle()
        // Sincroniza ao abrir o app e sempre que o evento ativo mudar.
        val active = prefs.activeEvent
        if (active != null && syncKey(active) != syncedKey) syncWithServer(manual = false)
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

    // ---------- Sincronização com o Q-Box ----------

    /** Testa a conexão e recarrega os setores do evento ativo (GET /api/access/sectors). */
    private fun syncWithServer(manual: Boolean) {
        val event = prefs.activeEvent
        if (event == null || !event.isComplete()) {
            setSyncStatus(false, "Nenhum evento completo cadastrado")
            return
        }
        if (syncing) return
        syncing = true
        val cfg = prefs.config(event)
        setSyncStatus(null, "Sincronizando com ${cfg.baseUrl}…")
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.fetchSectors(cfg) } }
            val ms = SystemClock.elapsedRealtime() - start
            syncing = false
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            result.onSuccess { list ->
                syncedKey = syncKey(event)
                prefs.events.firstOrNull { it.id == event.id }?.let { prefs.upsert(it.withSectors(list)) }
                renderEvent()
                setSyncStatus(true, "Conectado · ${list.size} setor(es) · $ms ms · $time")
                if (manual) {
                    feedback(ok = true)
                    showPanel(R.color.valid, "CONECTADO", "Q-Box respondeu em $ms ms", "${list.size} setor(es) carregado(s)")
                    handler.removeCallbacks(resetToIdle)
                    handler.postDelayed(resetToIdle, 2500)
                }
            }.onFailure {
                val msg = it.message ?: it.javaClass.simpleName
                setSyncStatus(false, "Falha na sincronização · $time · ${msg.lineSequence().first()}")
                if (manual) {
                    feedback(ok = false)
                    showPanel(R.color.warning, "SEM SINCRONIZAR", msg.lineSequence().first(), msg.lineSequence().drop(1).joinToString("\n"))
                    handler.removeCallbacks(resetToIdle)
                    handler.postDelayed(resetToIdle, 8000)
                }
            }
        }
    }

    private fun syncKey(e: Event) = "${e.id}|${e.host}|${e.showId}|${e.token}"

    private fun setSyncStatus(ok: Boolean?, message: String) {
        val (dot, color) = when (ok) {
            true -> "●" to R.color.valid
            false -> "●" to R.color.denied
            null -> "○" to R.color.text_secondary
        }
        tvSync.text = "$dot $message"
        tvSync.setTextColor(ContextCompat.getColor(this, color))
    }

    // ---------- Evento e setores ----------

    private fun renderEvent() {
        val event = prefs.activeEvent
        val mode = if (prefs.checkOnly) "CONSULTA" else "VALIDAÇÃO"
        if (event == null) {
            tvEvent.text = "Toque para cadastrar um evento ▾"
            tvHeader.text = "${prefs.gate} · $mode"
        } else {
            val tag = if (event.isVirtual) "  [VIRTUAL]" else ""
            tvEvent.text = "${event.name}$tag ▾"
            tvHeader.text = "${prefs.gate} · $mode · ${event.showId} · ${event.baseUrl()}"
        }

        chipSectors.removeAllViews()
        val sectors = event?.sectors.orEmpty()
        val selected = event?.selectedSectorIds.orEmpty().toSet()
        sectors.forEachIndexed { i, s ->
            val on = s.id in selected
            chipSectors.addView(Chip(this).apply {
                text = s.name
                isCheckable = false
                chipBackgroundColor = ColorStateList.valueOf(
                    if (on) SECTOR_COLORS[i % SECTOR_COLORS.size] else Color.parseColor("#263238")
                )
                setTextColor(Color.WHITE)
                chipStrokeWidth = 0f
                setOnClickListener { toggleSector(s.id) }
            })
        }
        tvSectorsHint.text = when {
            event == null -> ""
            sectors.isEmpty() -> "Setores ainda não carregados: toque em sincronizar ⟳"
            selected.isEmpty() -> "Aceitando TODOS os setores. Toque para restringir."
            else -> "Aceitando só: " + sectors.filter { it.id in selected }.joinToString(", ") { it.name }
        }
        tvSectorsHint.setTextColor(
            ContextCompat.getColor(this, if (selected.isEmpty()) R.color.warning else R.color.text_secondary)
        )
    }

    private fun toggleSector(id: Int) = withPin {
        val event = prefs.activeEvent ?: return@withPin
        val selected = event.selectedSectorIds.toMutableList()
        if (id in selected) selected.remove(id) else selected.add(id)
        prefs.upsert(event.copy(selectedSectorIds = selected))
        renderEvent()
        showIdle()
    }

    // ---------- Leitura ----------

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
            render(result, cfg)
        }
    }

    private fun render(result: ScanResult, cfg: QboxClient.Config) {
        when (result) {
            is ScanResult.Valid -> {
                val title = when {
                    result.master -> "CÓDIGO MESTRE"
                    cfg.checkOnly -> "VÁLIDO (consulta)"
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
                showPanel(
                    R.color.warning, "ERRO DE CONFIGURAÇÃO", "Show ID ou Token recusados pelo Q-Box",
                    "${cfg.baseUrl}\nShow '${cfg.showId}' · token ${cfg.token.length} caract. …${cfg.token.takeLast(4)}",
                )
                feedback(ok = false)
                addHistory("! AUTENTICAÇÃO", null)
            }
            is ScanResult.ServerError -> {
                if (result.httpCode == 404) {
                    showPanel(R.color.warning, "NÃO SUPORTADO", "Este Q-Box não tem essa função", if (cfg.checkOnly) "Desligue o modo consulta nas configurações" else "Verifique a versão do Q-Box")
                } else {
                    showPanel(R.color.warning, "ERRO NO Q-BOX", "HTTP ${result.httpCode}", "Tente ler novamente em alguns segundos")
                }
                feedback(ok = false)
                addHistory("! ERRO ${result.httpCode}", null)
            }
            is ScanResult.NetworkError -> {
                showPanel(R.color.warning, "SEM CONEXÃO", "Não foi possível falar com o Q-Box", "${cfg.baseUrl}\n${result.detail}")
                feedback(ok = false)
                addHistory("! SEM CONEXÃO", null)
                setSyncStatus(false, "Sem conexão com o Q-Box")
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
        "INVALID_SECTOR" -> {
            val event = prefs.activeEvent
            val here = event?.sectors?.filter { it.id in event.selectedSectorIds }?.joinToString(", ") { it.name }
            "SETOR NÃO PERMITIDO" to (if (here.isNullOrEmpty()) "Ingresso não é deste ponto" else "Este ponto aceita: $here")
        }
        "INVALID_QUENTRO_CODE" -> "CÓDIGO INVÁLIDO" to "Assinatura inválida: possível falsificação ou leitura ruim"
        "INVALID_ACL" -> "CÓDIGO BLOQUEADO" to "Está em lista negra do show"
        "DENIED" -> "ACESSO NEGADO" to "Negado pelas regras do show"
        "SHOW_NOT_OPEN" -> "SHOW NÃO ABERTO" to "Validação pausada ou fora do horário"
        "ACCESS_NOT_FOUND" -> "NÃO ENCONTRADO" to "Ingresso não é deste evento, ainda não sincronizou ou o show dele está pausado"
        else -> "NEGADO" to r.reason
    }

    private fun joinInfo(info: QboxClient.TicketInfo) =
        listOfNotNull(info.sector, info.document).joinToString(" · ")

    // ---------- UI ----------

    private fun showIdle() {
        if (!prefs.isConfigured()) {
            showPanel(R.color.idle, "CADASTRAR EVENTO", "Toque no nome do evento, no topo", "Dá para ler o QR do evento com a câmera")
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

    private fun updateCounters() {
        tvCounters.text = "Liberados: $okCount    Negados: $deniedCount"
    }

    private fun addHistory(status: String, holder: String?) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        history.addFirst(listOfNotNull(time, status, holder).joinToString("  "))
        while (history.size > 6) history.removeLast()
        tvHistory.text = history.joinToString("\n")
    }

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

    /** Executa [action] pedindo o PIN do supervisor (se houver). Depois de digitado, vale por 2 min. */
    private fun withPin(action: () -> Unit) {
        val pin = prefs.pin
        if (pin.isEmpty() || SystemClock.elapsedRealtime() < unlockedUntil) {
            action()
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
                    unlockedUntil = SystemClock.elapsedRealtime() + 2 * 60 * 1000
                    action()
                } else {
                    showPanel(R.color.denied, "PIN INCORRETO", "", "")
                    handler.postDelayed(resetToIdle, 2000)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        private val SECTOR_COLORS = listOf(
            Color.parseColor("#1565C0"), Color.parseColor("#6A1B9A"), Color.parseColor("#00838F"),
            Color.parseColor("#AD1457"), Color.parseColor("#EF6C00"), Color.parseColor("#2E7D32"),
        )
    }
}
