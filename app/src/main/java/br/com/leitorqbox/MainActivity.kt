package br.com.leitorqbox

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

    /** Tipo de resultado: define cor, ícone, som e vibração. */
    private enum class Kind(val colorRes: Int, val icon: String) {
        VALID(R.color.valid, "✓"),
        MASTER(R.color.master, "★"),
        DENIED(R.color.denied, "✕"),
        ERROR(R.color.warning, "!"),
    }

    /** Uma leitura do histórico, com tudo o que aparece no detalhe. */
    private data class ReadEntry(
        val time: String,
        val kind: Kind,
        val title: String,
        val holder: String?,
        val details: List<String>,
        val code: String,
    )

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var tvEvent: TextView
    private lateinit var tvHeader: TextView
    private lateinit var tvSync: TextView
    private lateinit var chipSectors: ChipGroup
    private lateinit var tvSectorsHint: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var tvResultIcon: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var tvResultExtra: TextView
    private lateinit var tvOk: TextView
    private lateinit var tvDenied: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvPct: TextView
    private lateinit var historyBox: LinearLayout

    private var busy = false
    private var syncing = false
    private var lastCode: String? = null
    private var lastCodeAt = 0L
    private var okCount = 0
    private var deniedCount = 0
    private val history = ArrayDeque<ReadEntry>()
    private val readTimes = ArrayDeque<Long>()
    // Evento (e conexão) já sincronizado; muda ao trocar de evento ou editar IP/ID/Token.
    private var syncedKey: String? = null
    private var unlockedUntil = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val resetToIdle = Runnable { showIdle() }
    private val refreshRate = object : Runnable {
        override fun run() {
            updateMetrics()
            handler.postDelayed(this, 10_000)
        }
    }
    private var tone: ToneGenerator? = null
    private var flash: ValueAnimator? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(DataWedge.EXTRA_DATA)?.let { handleCode(it.trim()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        prefs.seedOnce()

        root = findViewById(R.id.root)
        tvEvent = findViewById(R.id.tvEvent)
        tvHeader = findViewById(R.id.tvHeader)
        tvSync = findViewById(R.id.tvSync)
        chipSectors = findViewById(R.id.chipSectors)
        tvSectorsHint = findViewById(R.id.tvSectorsHint)
        resultPanel = findViewById(R.id.resultPanel)
        tvResultIcon = findViewById(R.id.tvResultIcon)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultDetail = findViewById(R.id.tvResultDetail)
        tvResultExtra = findViewById(R.id.tvResultExtra)
        tvOk = findViewById(R.id.tvOk)
        tvDenied = findViewById(R.id.tvDenied)
        tvRate = findViewById(R.id.tvRate)
        tvPct = findViewById(R.id.tvPct)
        historyBox = findViewById(R.id.historyBox)

        findViewById<View>(R.id.eventBox).setOnClickListener {
            withPin { startActivity(Intent(this, EventsActivity::class.java)) }
        }
        findViewById<ImageButton>(R.id.btnSync).setOnClickListener { syncWithServer(manual = true) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            withPin { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { DataWedge.softTrigger(this) }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { askManualCode() }

        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        DataWedge.configureProfile(this)
        updateMetrics()
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
        handler.post(refreshRate)
        // Sincroniza ao abrir o app e sempre que o evento ativo mudar.
        val active = prefs.activeEvent
        if (active != null && syncKey(active) != syncedKey) syncWithServer(manual = false)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
        handler.removeCallbacks(resetToIdle)
        handler.removeCallbacks(refreshRate)
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
                    showResult(Kind.VALID, "CONECTADO", "Q-Box respondeu em $ms ms", "${list.size} setor(es) carregado(s)")
                    handler.removeCallbacks(resetToIdle)
                    handler.postDelayed(resetToIdle, 2500)
                }
            }.onFailure {
                val msg = it.message ?: it.javaClass.simpleName
                setSyncStatus(false, "Falha na sincronização · $time · ${msg.lineSequence().first()}")
                if (manual) {
                    showResult(Kind.ERROR, "SEM SINCRONIZAR", msg.lineSequence().first(), msg.lineSequence().drop(1).joinToString("\n"))
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
            val tag = if (event.isVirtual) "  · VIRTUAL" else ""
            tvEvent.text = "${event.name}$tag ▾"
            tvHeader.text = "${prefs.gate} · $mode · ${event.showId} · ${event.baseUrl()}"
        }

        chipSectors.removeAllViews()
        val sectors = event?.sectors.orEmpty()
        val selected = event?.selectedSectorIds.orEmpty().toSet()
        sectors.forEachIndexed { i, s ->
            val on = s.id in selected
            chipSectors.addView(Chip(this).apply {
                text = if (on) "✓ ${s.name}" else s.name
                isCheckable = false
                chipBackgroundColor = ColorStateList.valueOf(
                    if (on) SECTOR_COLORS[i % SECTOR_COLORS.size] else ContextCompat.getColor(context, R.color.card)
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

    private fun askManualCode() {
        val input = EditText(this).apply {
            hint = "Código do ingresso"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Digitar código")
            .setView(input)
            .setPositiveButton("Validar") { _, _ -> handleCode(input.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                dialog.dismiss()
                handleCode(input.text.toString().trim())
                true
            } else false
        }
        dialog.show()
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
        showPanel(R.color.processing, "…", "VALIDANDO", code.take(48), "")

        val cfg = prefs.config()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { QboxClient.scan(cfg, code) }
            busy = false
            render(result, cfg, code)
        }
    }

    private fun render(result: ScanResult, cfg: QboxClient.Config, code: String) {
        when (result) {
            is ScanResult.Valid -> {
                val kind = if (result.master) Kind.MASTER else Kind.VALID
                val title = when {
                    result.master -> "CÓDIGO MESTRE"
                    cfg.checkOnly -> "VÁLIDO (consulta)"
                    else -> "LIBERADO"
                }
                val info = joinInfo(result.info)
                showResult(kind, title, result.info.holder ?: "", info)
                okCount++
                record(kind, title, result.info.holder, listOfNotNull(result.info.sector, result.info.document), code)
            }
            is ScanResult.Rejected -> {
                val (title, hint) = describeReason(result)
                val extra = listOfNotNull(hint, joinInfo(result.info).ifEmpty { null }).joinToString("\n")
                val kind = if (result.reason == "SHOW_NOT_OPEN") Kind.ERROR else Kind.DENIED
                showResult(kind, title, result.info.holder ?: "", extra)
                deniedCount++
                record(kind, title, result.info.holder, listOfNotNull(hint, result.info.sector, result.info.document, "Motivo: ${result.reason}"), code)
            }
            ScanResult.AuthError -> {
                val detail = "${cfg.baseUrl}\nShow '${cfg.showId}' · token ${cfg.token.length} caract. …${cfg.token.takeLast(4)}"
                showResult(Kind.ERROR, "ERRO DE CONFIGURAÇÃO", "Show ID ou Token recusados pelo Q-Box", detail)
                record(Kind.ERROR, "AUTENTICAÇÃO", null, detail.lines(), code)
            }
            is ScanResult.ServerError -> {
                if (result.httpCode == 404) {
                    showResult(Kind.ERROR, "NÃO SUPORTADO", "Este Q-Box não tem essa função", if (cfg.checkOnly) "Desligue o modo consulta nas configurações" else "Verifique a versão do Q-Box")
                } else {
                    showResult(Kind.ERROR, "ERRO NO Q-BOX", "HTTP ${result.httpCode}", "Tente ler novamente em alguns segundos")
                }
                record(Kind.ERROR, "ERRO ${result.httpCode}", null, listOf("HTTP ${result.httpCode}"), code)
            }
            is ScanResult.NetworkError -> {
                showResult(Kind.ERROR, "SEM CONEXÃO", "Não foi possível falar com o Q-Box", "${cfg.baseUrl}\n${result.detail}")
                record(Kind.ERROR, "SEM CONEXÃO", null, listOf(cfg.baseUrl, result.detail), code)
                setSyncStatus(false, "Sem conexão com o Q-Box")
                // Libera reler o mesmo código logo depois de uma falha de rede.
                lastCode = null
            }
        }
        updateMetrics()
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

    // ---------- Métricas e histórico ----------

    private fun record(kind: Kind, title: String, holder: String?, details: List<String>, code: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        history.addFirst(ReadEntry(time, kind, title, holder, details, code))
        while (history.size > 30) history.removeLast()
        if (kind == Kind.VALID || kind == Kind.MASTER || kind == Kind.DENIED) {
            readTimes.addLast(SystemClock.elapsedRealtime())
        }
        renderHistory()
    }

    private fun updateMetrics() {
        val now = SystemClock.elapsedRealtime()
        while (readTimes.isNotEmpty() && now - readTimes.first() > 60_000) readTimes.removeFirst()
        tvOk.text = okCount.toString()
        tvDenied.text = deniedCount.toString()
        tvRate.text = readTimes.size.toString()
        val total = okCount + deniedCount
        tvPct.text = if (total == 0) "–" else "${okCount * 100 / total}%"
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val dp = resources.displayMetrics.density
        history.take(4).forEach { entry ->
            historyBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (5 * dp).toInt(), (8 * dp).toInt(), (5 * dp).toInt())
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                setOnClickListener { showDetails(entry) }

                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply {
                        marginEnd = (10 * dp).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(context, entry.kind.colorRes))
                    }
                })
                addView(TextView(context).apply {
                    text = listOfNotNull(entry.time, entry.title, entry.holder).joinToString("   ")
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "›"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 16f
                })
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            })
        }
    }

    private fun showDetails(e: ReadEntry) {
        val body = buildString {
            e.holder?.let { append("Titular: $it\n") }
            e.details.forEach { append("$it\n") }
            append("\nHorário: ${e.time}\nCódigo lido:\n${e.code}")
        }
        AlertDialog.Builder(this)
            .setTitle("${e.kind.icon}  ${e.title}")
            .setMessage(body)
            .setPositiveButton("Fechar", null)
            .show()
    }

    // ---------- Visual do resultado ----------

    private fun showIdle() {
        if (!prefs.isConfigured()) {
            showPanel(R.color.idle, "+", "CADASTRAR EVENTO", "Toque no nome do evento, no topo", "Dá para ler o QR do evento com a câmera")
        } else {
            val mode = if (prefs.checkOnly) "Modo CONSULTA (não marca como usado)" else ""
            showPanel(R.color.idle, "◎", "PRONTO", "Aponte e aperte o gatilho", mode)
        }
    }

    /** Resultado de leitura: painel + animação + flash na tela + som + vibração. */
    private fun showResult(kind: Kind, title: String, detail: String, extra: String) {
        showPanel(kind.colorRes, kind.icon, title, detail, extra)
        animateResult(kind)
        feedback(kind)
    }

    private fun showPanel(colorRes: Int, icon: String, title: String, detail: String, extra: String) {
        val color = ContextCompat.getColor(this, colorRes)
        val dp = resources.displayMetrics.density
        resultPanel.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color, darken(color)),
        ).apply { cornerRadius = 24 * dp }
        tvResultIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(46, 255, 255, 255))
        }
        tvResultIcon.text = icon
        tvResultTitle.text = title
        tvResultDetail.text = detail
        tvResultExtra.text = extra
    }

    private fun animateResult(kind: Kind) {
        resultPanel.apply {
            scaleX = 0.94f; scaleY = 0.94f; alpha = 0.5f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }
        tvResultIcon.apply {
            scaleX = 0.3f; scaleY = 0.3f
            animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(2.5f)).start()
        }
        // Flash da cor do resultado na tela inteira, visível de longe.
        val from = ContextCompat.getColor(this, kind.colorRes)
        val to = ContextCompat.getColor(this, R.color.background)
        flash?.cancel()
        flash = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 700
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun darken(color: Int) = Color.rgb(
        (Color.red(color) * 0.72).toInt(),
        (Color.green(color) * 0.72).toInt(),
        (Color.blue(color) * 0.72).toInt(),
    )

    /** Som e vibração diferentes por resultado, para reconhecer sem olhar a tela. */
    private fun feedback(kind: Kind) {
        val (toneType, toneMs, pattern) = when (kind) {
            Kind.VALID -> Triple(ToneGenerator.TONE_PROP_ACK, 200, longArrayOf(0, 70))
            Kind.MASTER -> Triple(ToneGenerator.TONE_PROP_BEEP2, 300, longArrayOf(0, 60, 80, 60))
            Kind.DENIED -> Triple(ToneGenerator.TONE_SUP_ERROR, 700, longArrayOf(0, 250, 120, 250, 120, 250))
            Kind.ERROR -> Triple(ToneGenerator.TONE_SUP_CONGESTION, 900, longArrayOf(0, 600))
        }
        tone?.startTone(toneType, toneMs)
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
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
                    showResult(Kind.DENIED, "PIN INCORRETO", "", "")
                    handler.postDelayed(resetToIdle, 2000)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        private val SECTOR_COLORS = listOf(
            Color.parseColor("#026CDF"), Color.parseColor("#7B3FE4"), Color.parseColor("#00838F"),
            Color.parseColor("#AD1457"), Color.parseColor("#EF6C00"), Color.parseColor("#2E7D32"),
        )
    }
}
