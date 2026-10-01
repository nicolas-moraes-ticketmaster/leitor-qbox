package br.com.leitorqbox

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import br.com.leitorqbox.QboxClient.ScanResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** Tipo de resultado: cores do painel (GDS), ícone, som e vibração. */
    private enum class Kind(val bg: Int, val sub: Int, val fg: Int, val icon: Int) {
        VALID(R.color.valid, R.color.valid_sub, android.R.color.white, R.drawable.ic_success),
        MASTER(R.color.master, R.color.master_sub, android.R.color.white, R.drawable.ic_star),
        DENIED(R.color.denied, R.color.denied_sub, android.R.color.white, R.drawable.ic_error),
        BLOCKED(R.color.sector, R.color.sector_sub, R.color.on_sector, R.drawable.ic_blocked),
        EXPIRED(R.color.expired, R.color.expired_sub, android.R.color.white, R.drawable.ic_warning),
        USED(R.color.used, R.color.used_sub, android.R.color.white, R.drawable.ic_error),
        VOIDED(R.color.voided, R.color.voided_sub, android.R.color.white, R.drawable.ic_blocked),
        ERROR(R.color.warning, R.color.warning_sub, R.color.on_warning, R.drawable.ic_warning),
    }

    /** Tudo o que o painel mostra. */
    private data class Panel(
        val bg: Int, val sub: Int, val fg: Int,
        val icon: Int?,            // ícone pequeno na linha de cima
        val bigIcon: Int? = null,  // só PRONTO
        val busy: Boolean = false, // só VALIDANDO
        val eyebrow: String,
        val time: String,
        val title: String,
        val holder: String,
        val rows: List<Pair<String, String>> = emptyList(),
        val note: String? = null,
        val code: String = "",
    )

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
    private lateinit var tvSync: TextView
    private lateinit var syncDot: View
    private lateinit var tvSectorSummary: TextView
    private lateinit var tvSectorCount: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var ivResultIcon: ImageView
    private lateinit var tvEyebrow: TextView
    private lateinit var tvResultTime: TextView
    private lateinit var ivBigIcon: ImageView
    private lateinit var pbBusy: ProgressBar
    private lateinit var accent: View
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultHolder: TextView
    private lateinit var gridRows: GridLayout
    private lateinit var tvResultNote: TextView
    private lateinit var tvResultCode: TextView
    private lateinit var barTrack: View
    private lateinit var barFill: View
    private lateinit var tvOk: TextView
    private lateinit var tvDenied: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvPct: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerItems: LinearLayout
    private lateinit var btnSync: ImageButton
    private lateinit var offlineBanner: View
    private lateinit var tvOffline: TextView
    private lateinit var updateBanner: View
    private lateinit var tvUpdate: TextView

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
    private lateinit var pinPad: PinPad
    private var panelColor: Int? = null
    private var panelColorAnim: ValueAnimator? = null
    private var syncSpin: ObjectAnimator? = null
    private var updateChecked = false

    private val handler = Handler(Looper.getMainLooper())
    private val resetToIdle = Runnable { showIdle() }
    private val refreshRate = object : Runnable {
        override fun run() {
            updateMetrics()
            handler.postDelayed(this, 10_000)
        }
    }
    // Verifica a cada 30 s se o Q-Box responde; mostra a faixa vermelha se cair.
    private val heartbeat = object : Runnable {
        override fun run() {
            checkServer()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }
    private var tone: ToneGenerator? = null
    private var flash: ValueAnimator? = null
    private val standard = PathInterpolatorCompat.create(0.3f, 0f, 0.2f, 1f)

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
        pinPad = PinPad(this, prefs)

        root = findViewById(R.id.root)
        tvEvent = findViewById(R.id.tvEvent)
        tvSync = findViewById(R.id.tvSync)
        syncDot = findViewById(R.id.syncDot)
        tvSectorSummary = findViewById(R.id.tvSectorSummary)
        tvSectorCount = findViewById(R.id.tvSectorCount)
        resultPanel = findViewById(R.id.resultPanel)
        ivResultIcon = findViewById(R.id.ivResultIcon)
        tvEyebrow = findViewById(R.id.tvEyebrow)
        tvResultTime = findViewById(R.id.tvResultTime)
        ivBigIcon = findViewById(R.id.ivBigIcon)
        pbBusy = findViewById(R.id.pbBusy)
        accent = findViewById(R.id.accent)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvResultHolder = findViewById(R.id.tvResultHolder)
        gridRows = findViewById(R.id.gridRows)
        tvResultNote = findViewById(R.id.tvResultNote)
        tvResultCode = findViewById(R.id.tvResultCode)
        barTrack = findViewById(R.id.barTrack)
        barFill = findViewById(R.id.barFill)
        tvOk = findViewById(R.id.tvOk)
        tvDenied = findViewById(R.id.tvDenied)
        tvRate = findViewById(R.id.tvRate)
        tvPct = findViewById(R.id.tvPct)
        drawer = findViewById(R.id.drawer)
        drawerItems = findViewById(R.id.drawerItems)
        btnSync = findViewById(R.id.btnSync)
        offlineBanner = findViewById(R.id.offlineBanner)
        tvOffline = findViewById(R.id.tvOffline)
        updateBanner = findViewById(R.id.updateBanner)
        tvUpdate = findViewById(R.id.tvUpdate)

        findViewById<View>(R.id.eventBox).setOnClickListener {
            withPin { startActivity(Intent(this, EventsActivity::class.java)) }
        }
        findViewById<View>(R.id.sectorBar).setOnClickListener { pinPad.require(alwaysAsk = true) { openSectorSheet() } }
        btnSync.setOnClickListener { haptic(it); syncWithServer(manual = true) }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            haptic(it)
            renderDrawer()
            drawer.openDrawer(GravityCompat.END)
        }
        findViewById<View>(R.id.btnRetry).setOnClickListener { haptic(it); checkServer() }
        findViewById<View>(R.id.btnUpdate).setOnClickListener { haptic(it); Updater.openDownload(this) }
        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener { haptic(it); DataWedge.softTrigger(this) }
        findViewById<MaterialButton>(R.id.btnKeyboard).setOnClickListener { haptic(it); askManualCode() }

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
        if (prefs.maxVolume) maximizeVolume()
        renderEvent()
        showIdle()
        handler.post(refreshRate)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        if (!updateChecked) checkForUpdate(manual = false)
        // Sincroniza ao abrir o app e sempre que o evento ativo mudar.
        val active = prefs.activeEvent
        if (active != null && syncKey(active) != syncedKey) syncWithServer(manual = false)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
        handler.removeCallbacks(resetToIdle)
        handler.removeCallbacks(refreshRate)
        handler.removeCallbacks(heartbeat)
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
        spinSync(true)
        val cfg = prefs.config(event)
        setSyncStatus(null, "Sincronizando…")
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.fetchSectors(cfg) } }
            val ms = SystemClock.elapsedRealtime() - start
            syncing = false
            spinSync(false)
            result.onSuccess { list ->
                setOffline(null)
                syncedKey = syncKey(event)
                prefs.events.firstOrNull { it.id == event.id }?.let { prefs.upsert(it.withSectors(list)) }
                renderEvent()
                setSyncStatus(true, "Conectado · $ms ms")
                if (manual) {
                    showResult(
                        Kind.VALID,
                        panel(Kind.VALID, "Sincronização", "Conectado", "",
                            rows = listOf("Setores" to list.size.toString(), "Resposta" to "$ms ms"),
                            code = cfg.baseUrl),
                        holdMs = 2500,
                    )
                }
            }.onFailure {
                val msg = it.message ?: it.javaClass.simpleName
                setSyncStatus(false, "Sem conexão com o Q-Box")
                setOffline("Q-Box fora do ar · ${cfg.baseUrl}")
                if (manual) {
                    showResult(
                        Kind.ERROR,
                        panel(Kind.ERROR, "Falha de rede", "Sem sincronizar", "",
                            rows = listOf("Endereço" to cfg.baseUrl),
                            note = msg),
                        holdMs = 8000,
                    )
                }
            }
        }
    }

    private fun syncKey(e: Event) = "${e.id}|${e.host}|${e.showId}|${e.token}"

    private fun setSyncStatus(ok: Boolean?, message: String) {
        syncDot.setBackgroundColor(ContextCompat.getColor(this, when (ok) {
            true -> R.color.valid
            false -> R.color.denied
            null -> R.color.text_muted
        }))
        tvSync.text = message
    }

    // ---------- Evento e setores ----------

    private fun renderEvent() {
        val event = prefs.activeEvent
        tvEvent.text = when {
            event == null -> "Cadastrar evento"
            event.isVirtual -> "${event.name} · Virtual"
            else -> event.name
        }
        renderSectorBar()
        renderDrawer()
    }

    private fun renderSectorBar() {
        val event = prefs.activeEvent
        val sectors = event?.sectors.orEmpty()
        val sel = event?.selectedSectorIds.orEmpty()
        when {
            event == null -> {
                tvSectorSummary.text = "Nenhum evento"
                tvSectorCount.isVisible = false
            }
            sectors.isEmpty() -> {
                tvSectorSummary.text = "Setores não carregados"
                tvSectorCount.isVisible = false
            }
            sel.isEmpty() -> {
                tvSectorSummary.text = "Todos os setores"
                tvSectorCount.text = "Todos"
                tvSectorCount.isVisible = true
            }
            else -> {
                tvSectorSummary.text = sectors.filter { it.id in sel }.joinToString(" · ") { it.name }
                tvSectorCount.text = "${sel.size}/${sectors.size}"
                tvSectorCount.isVisible = true
            }
        }
    }

    private fun openSectorSheet() {
        val event = prefs.activeEvent ?: return
        if (event.sectors.isEmpty()) {
            syncWithServer(manual = true)
            return
        }

        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_sectors, null)
        dialog.setContentView(v)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val draft = event.selectedSectorIds.toMutableSet()
        val swAll = v.findViewById<MaterialSwitch>(R.id.swAll)
        val list = v.findViewById<LinearLayout>(R.id.sectorList)
        val btnApply = v.findViewById<MaterialButton>(R.id.btnApply)
        v.findViewById<TextView>(R.id.tvSheetSub).text = "${prefs.gate} · ${event.name}"
        v.findViewById<TextView>(R.id.tvPinNote).apply {
            isVisible = true
            val lock = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()?.apply {
                setTint(ContextCompat.getColor(context, R.color.text_muted))
                val s = (16 * resources.displayMetrics.density).toInt()
                setBounds(0, 0, s, s)
            }
            setCompoundDrawablesRelative(lock, null, null, null)
        }
        v.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        val boxes = mutableListOf<MaterialCheckBox>()
        fun refresh() {
            swAll.setOnCheckedChangeListener(null)
            swAll.isChecked = draft.isEmpty()
            swAll.setOnCheckedChangeListener { _, on ->
                if (on) draft.clear() else event.sectors.firstOrNull()?.let { draft.add(it.id) }
                refresh()
            }
            // draft vazio = "todos os setores": todas as caixas aparecem marcadas.
            val allIds = event.sectors.map { it.id }
            boxes.forEachIndexed { i, cb ->
                val id = event.sectors[i].id
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = draft.isEmpty() || id in draft
                cb.setOnCheckedChangeListener { _, on ->
                    if (draft.isEmpty()) draft.addAll(allIds) // saindo do modo "todos"
                    if (on) draft.add(id) else draft.remove(id)
                    when {
                        draft.isEmpty() -> {
                            draft.add(id)
                            Toast.makeText(this, "Selecione ao menos um setor ou ative Aceitar todos", Toast.LENGTH_SHORT).show()
                        }
                        draft.size == allIds.size -> draft.clear() // marcou todos = "todos"
                    }
                    refresh()
                }
            }
            btnApply.text = if (draft.isEmpty()) {
                "Aplicar · todos os setores"
            } else {
                "Aplicar · ${draft.size} ${if (draft.size == 1) "setor" else "setores"}"
            }
        }

        event.sectors.forEachIndexed { i, s ->
            val row = layoutInflater.inflate(R.layout.item_sector, list, false)
            row.findViewById<View>(R.id.marker).setBackgroundColor(SECTOR_COLORS[i % SECTOR_COLORS.size])
            val cb = row.findViewById<MaterialCheckBox>(R.id.cb).apply { text = s.name }
            row.findViewById<TextView>(R.id.tvId).text = "#${s.id}"
            row.setOnClickListener { cb.toggle() }
            boxes += cb
            list.addView(row)
            list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (1 * resources.displayMetrics.density).toInt())
            })
        }
        refresh()

        btnApply.setOnClickListener {
            prefs.upsert(event.copy(selectedSectorIds = event.sectors.map { it.id }.filter { it in draft }))
            renderEvent()
            showIdle()
            dialog.dismiss()
        }
        dialog.show()
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
        barFill.animate().cancel()
        barFill.scaleX = 0f
        renderPanel(
            Panel(R.color.processing, R.color.processing_sub, android.R.color.white, null,
                busy = true, eyebrow = "Consultando o Q-Box", time = now("HH:mm:ss"),
                title = "Validando", holder = "", code = code.take(48))
        )

        val cfg = prefs.config()
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { QboxClient.scan(cfg, code) }
            val ms = SystemClock.elapsedRealtime() - start
            busy = false
            render(result, cfg, code, ms)
        }
    }

    private fun render(result: ScanResult, cfg: QboxClient.Config, code: String, ms: Long) {
        when (result) {
            is ScanResult.Valid -> {
                val info = result.info
                if (result.master) {
                    val p = panel(Kind.MASTER, "Código mestre", "Código mestre", info.holder.orEmpty(),
                        rows = listOf("Acesso" to (info.sector ?: "Todos os setores")), code = code)
                    showResult(Kind.MASTER, p)
                    record(Kind.MASTER, "Código mestre", info.holder, listOfNotNull(info.sector), code)
                } else {
                    val title = if (cfg.checkOnly) "Válido (consulta)" else "Liberado"
                    val rows = listOfNotNull(
                        info.sector?.let { "Setor" to it },
                        info.document?.let { "Documento" to it },
                        info.ticketId?.let { "Ingresso" to it.toString() },
                        "Resposta" to "$ms ms",
                    )
                    showResult(Kind.VALID, panel(Kind.VALID, "Entrada liberada", title, info.holder.orEmpty(), rows, code = code))
                    record(Kind.VALID, title, info.holder, listOfNotNull(info.sector, info.document), code)
                }
                okCount++
            }
            is ScanResult.Rejected -> {
                val info = result.info
                val (kind, p) = rejectedPanel(result, code)
                showResult(kind, p)
                deniedCount++
                record(kind, p.title, info.holder,
                    listOfNotNull(p.note, info.sector, info.document, "Motivo: ${result.reason}"), code)
            }
            ScanResult.AuthError -> {
                val p = panel(Kind.ERROR, "Falha de configuração", "Erro de configuração",
                    "Show ID ou Token recusados pelo Q-Box",
                    rows = listOf(
                        "Endereço" to cfg.baseUrl,
                        "Show ID" to cfg.showId,
                        "Token" to "${cfg.token.length} caract. …${cfg.token.takeLast(4)}",
                    ),
                    code = code)
                showResult(Kind.ERROR, p)
                record(Kind.ERROR, "Autenticação", null, p.rows.map { "${it.first}: ${it.second}" }, code)
            }
            is ScanResult.ServerError -> {
                val p = if (result.httpCode == 404) {
                    panel(Kind.ERROR, "Erro no Q-Box", "Não suportado", "Este Q-Box não tem essa função",
                        note = if (cfg.checkOnly) "Desligue o modo consulta nas configurações." else "Verifique a versão do Q-Box.",
                        code = code)
                } else {
                    panel(Kind.ERROR, "Erro no Q-Box", "Erro no Q-Box", "HTTP ${result.httpCode}",
                        note = "Tente ler novamente em alguns segundos.", code = code)
                }
                showResult(Kind.ERROR, p)
                record(Kind.ERROR, "Erro ${result.httpCode}", null, listOf("HTTP ${result.httpCode}"), code)
            }
            is ScanResult.NetworkError -> {
                val p = panel(Kind.ERROR, "Falha de rede", "Sem conexão", "Não foi possível falar com o Q-Box",
                    rows = listOf("Endereço" to cfg.baseUrl, "Erro" to result.detail),
                    note = "Leia de novo. O mesmo código pode ser relido logo em seguida.",
                    code = code)
                showResult(Kind.ERROR, p)
                record(Kind.ERROR, "Sem conexão", null, listOf(cfg.baseUrl, result.detail), code)
                setSyncStatus(false, "Sem conexão com o Q-Box")
                setOffline("Q-Box fora do ar · ${cfg.baseUrl}")
                // Libera reler o mesmo código logo depois de uma falha de rede.
                lastCode = null
            }
        }
        updateMetrics()
    }

    /** Painel de cada motivo de rejeição do Q-Box. */
    private fun rejectedPanel(r: ScanResult.Rejected, code: String): Pair<Kind, Panel> {
        val info = r.info
        val holder = info.holder.orEmpty()
        val basicRows = listOfNotNull(info.sector?.let { "Setor" to it }, info.document?.let { "Documento" to it })
        return when (r.reason) {
            "USED" -> Kind.USED to panel(Kind.USED, "Entrada negada", "Já utilizado", holder,
                rows = listOfNotNull(
                    r.usedDate?.let { "Usado às" to it },
                    r.usedGate?.let { "Portão" to it },
                ) + basicRows,
                note = if (r.sameGate) "Lido neste mesmo portão." else null,
                code = code)
            "INVALID_SECTOR" -> {
                val event = prefs.activeEvent
                val here = event?.sectors?.filter { it.id in event.selectedSectorIds }
                    ?.joinToString(", ") { it.name }.orEmpty()
                Kind.BLOCKED to panel(Kind.BLOCKED, "Entrada negada", "Setor não permitido", holder,
                    rows = listOf(
                        "Setor do ingresso" to (info.sector ?: "—"),
                        "Este ponto aceita" to here.ifEmpty { "—" },
                    ),
                    code = code)
            }
            "VOID" -> Kind.VOIDED to panel(Kind.VOIDED, "Entrada negada", "Ingresso cancelado", holder,
                rows = basicRows, note = "Ingresso cancelado ou estornado.", code = code)
            "INVALID_QUENTRO_CODE" -> Kind.EXPIRED to panel(Kind.EXPIRED, "Entrada negada", "Código expirado", holder,
                rows = basicRows,
                note = "QR vencido (print ou PDF de QR dinâmico) ou inválido. Peça o QR atualizado no app.",
                code = code)
            "SHOW_NOT_OPEN" -> Kind.ERROR to panel(Kind.ERROR, "Validação fechada", "Show não aberto", holder,
                rows = basicRows, note = "Validação pausada ou fora do horário.", code = code)
            else -> {
                val (title, note) = when (r.reason) {
                    "INVALID_ACL" -> "Código bloqueado" to "Está em lista negra do show."
                    "DENIED" -> "Acesso negado" to "Negado pelas regras do show."
                    "ACCESS_NOT_FOUND" -> "Não encontrado" to "Ingresso não é deste evento, ainda não sincronizou ou o show dele está pausado."
                    else -> "Negado" to r.reason
                }
                Kind.DENIED to panel(Kind.DENIED, "Entrada negada", title, holder, rows = basicRows, note = note, code = code)
            }
        }
    }

    private fun panel(
        kind: Kind, eyebrow: String, title: String, holder: String,
        rows: List<Pair<String, String>> = emptyList(), note: String? = null, code: String = "",
    ) = Panel(kind.bg, kind.sub, kind.fg, kind.icon, eyebrow = eyebrow, time = now("HH:mm:ss"),
        title = title, holder = holder, rows = rows, note = note, code = code)

    private fun now(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    // ---------- Métricas e histórico ----------

    private fun record(kind: Kind, title: String, holder: String?, details: List<String>, code: String) {
        history.addFirst(ReadEntry(now("HH:mm:ss"), kind, title, holder, details, code))
        while (history.size > 30) history.removeLast()
        if (kind != Kind.ERROR) readTimes.addLast(SystemClock.elapsedRealtime())
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

    // ---------- Painel ----------

    private fun showIdle() {
        handler.removeCallbacks(resetToIdle)
        barFill.animate().cancel()
        barFill.scaleX = 0f
        if (!prefs.isConfigured()) {
            renderPanel(Panel(R.color.idle, R.color.idle_sub, R.color.text_primary, null,
                bigIcon = R.drawable.ic_qr, eyebrow = "Nenhum evento", time = "",
                title = "Cadastrar evento", holder = "Toque no nome do evento, no topo"))
            return
        }
        val mode = if (prefs.checkOnly) "Consulta" else "Validação"
        renderPanel(Panel(R.color.idle, R.color.idle_sub, R.color.text_primary, null,
            bigIcon = R.drawable.ic_qr, eyebrow = "$mode · ${prefs.gate}",
            time = "", title = "Pronto", holder = "Aponte e aperte o gatilho",
            code = history.firstOrNull()?.let { "Última leitura ${it.time}" } ?: ""))
    }

    private fun renderPanel(p: Panel) {
        val dp = resources.displayMetrics.density
        val c = { id: Int -> ContextCompat.getColor(this, id) }
        val fg = c(p.fg)

        animatePanelColor(c(p.bg))
        listOf(tvEyebrow, tvResultTime, tvResultTitle, tvResultHolder, tvResultNote, tvResultCode)
            .forEach { it.setTextColor(fg) }

        ivResultIcon.isVisible = p.icon != null
        p.icon?.let { ivResultIcon.setImageResource(it); ivResultIcon.setColorFilter(fg) }
        ivBigIcon.isVisible = p.bigIcon != null
        p.bigIcon?.let { ivBigIcon.setImageResource(it); ivBigIcon.setColorFilter(fg) }
        pbBusy.isVisible = p.busy

        tvEyebrow.text = p.eyebrow
        tvResultTime.text = p.time
        accent.setBackgroundColor(fg)
        tvResultTitle.text = p.title
        tvResultHolder.text = p.holder
        tvResultHolder.isVisible = p.holder.isNotEmpty()
        tvResultNote.text = p.note
        tvResultNote.isVisible = !p.note.isNullOrEmpty()
        tvResultCode.text = p.code

        // Grade 2 colunas: células na cor "sub", 1dp de espaço entre elas.
        gridRows.removeAllViews()
        gridRows.isVisible = p.rows.isNotEmpty()
        p.rows.forEachIndexed { i, (k, v) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c(p.sub))
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                addView(TextView(context).apply { text = k; textSize = 12f; setTextColor(fg) })
                addView(TextView(context).apply {
                    text = v
                    textSize = 14f
                    setTextColor(fg)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    fontFeatureSettings = "tnum"
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            gridRows.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f),
            ).apply {
                width = 0
                setMargins(if (i % 2 == 1) (1 * dp).toInt() else 0, if (i >= 2) (1 * dp).toInt() else 0, 0, 0)
            })
        }

        barTrack.setBackgroundColor(c(p.sub))
        barFill.setBackgroundColor(fg)
    }

    /** Resultado: painel + entrada curta (só alpha) + flash na tela + barra de tempo + som. */
    private fun showResult(kind: Kind, p: Panel, holdMs: Long = 4000) {
        renderPanel(p)
        // Entrada em sequência: ícone, faixa, status, titular, grade e nota (GDS: curto, sem overshoot).
        ivResultIcon.alpha = 0f
        ivResultIcon.scaleX = 0.85f; ivResultIcon.scaleY = 0.85f
        ivResultIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(standard).start()
        val rise = 10 * resources.displayMetrics.density
        listOf(accent, tvResultTitle, tvResultHolder, gridRows, tvResultNote).forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = rise
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(200)
                .setInterpolator(standard).start()
        }
        // Flash da cor na tela inteira, visível de longe.
        flash?.cancel()
        flash = ValueAnimator.ofObject(
            ArgbEvaluator(),
            ContextCompat.getColor(this, kind.bg), ContextCompat.getColor(this, R.color.background),
        ).apply {
            duration = 700
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
        // Barra de tempo: 100% -> 0% enquanto o resultado fica na tela.
        barFill.animate().cancel()
        barFill.pivotX = 0f
        barFill.scaleX = 1f
        barFill.animate().scaleX(0f).setDuration(holdMs).setInterpolator(LinearInterpolator()).start()
        feedback(kind)
        handler.removeCallbacks(resetToIdle)
        handler.postDelayed(resetToIdle, holdMs)
    }

    /** Som e vibração diferentes por resultado, para reconhecer sem olhar a tela. */
    private fun feedback(kind: Kind) {
        val (toneType, toneMs, pattern) = when (kind) {
            Kind.VALID -> Triple(ToneGenerator.TONE_PROP_ACK, 200, longArrayOf(0, 70))
            Kind.MASTER -> Triple(ToneGenerator.TONE_PROP_BEEP2, 300, longArrayOf(0, 60, 80, 60))
            Kind.DENIED, Kind.BLOCKED, Kind.USED, Kind.VOIDED, Kind.EXPIRED -> Triple(ToneGenerator.TONE_SUP_ERROR, 700, longArrayOf(0, 250, 120, 250, 120, 250))
            Kind.ERROR -> Triple(ToneGenerator.TONE_SUP_CONGESTION, 900, longArrayOf(0, 600))
        }
        if (kind == Kind.VALID) Beeper.success() else tone?.startTone(toneType, toneMs)
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** Transição de cor do painel entre um estado e outro (200 ms). */
    private fun animatePanelColor(to: Int) {
        val bg = (resultPanel.background as? GradientDrawable) ?: GradientDrawable().also {
            it.cornerRadius = 4 * resources.displayMetrics.density
            resultPanel.background = it
        }
        val from = panelColor ?: to
        panelColor = to
        panelColorAnim?.cancel()
        if (from == to) { bg.setColor(to); return }
        panelColorAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 200
            interpolator = standard
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    /** Ícone de sincronizar gira enquanto conversa com o Q-Box. */
    private fun spinSync(on: Boolean) {
        syncSpin?.cancel()
        if (on) {
            syncSpin = ObjectAnimator.ofFloat(btnSync, View.ROTATION, btnSync.rotation, btnSync.rotation + 360f).apply {
                duration = 800
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            btnSync.animate().rotation(0f).setDuration(150).setInterpolator(standard).start()
        }
    }

    // ---------- Q-Box fora do ar (verificação a cada 30 s) ----------

    private fun checkServer() {
        val event = prefs.activeEvent ?: return
        if (!event.isComplete() || busy || syncing) return
        val url = event.baseUrl()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { QboxClient.ping(url) } }
            result.onSuccess { ms ->
                setOffline(null)
                setSyncStatus(true, "Conectado · $ms ms")
            }.onFailure {
                setOffline("Q-Box fora do ar · $url")
                setSyncStatus(false, "Sem conexão com o Q-Box")
            }
        }
    }

    /** Mostra (mensagem) ou esconde (null) a faixa vermelha, deslizando. */
    private fun setOffline(message: String?) {
        if (message != null) {
            tvOffline.text = message
            slideBanner(offlineBanner, show = true)
        } else {
            slideBanner(offlineBanner, show = false)
        }
    }

    private fun slideBanner(banner: View, show: Boolean) {
        if (show == banner.isVisible) return
        banner.animate().cancel()
        if (show) {
            banner.isVisible = true
            banner.alpha = 0f
            banner.translationY = -12 * resources.displayMetrics.density
            banner.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(standard).start()
        } else {
            banner.animate().alpha(0f).setDuration(150).setInterpolator(standard)
                .withEndAction { banner.isVisible = false }.start()
        }
    }

    // ---------- Atualização do app (GitHub) ----------

    private fun checkForUpdate(manual: Boolean) {
        updateChecked = true
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { runCatching { Updater.newerRelease(this@MainActivity) } }
            val newer = release.getOrNull()
            when {
                newer != null -> {
                    tvUpdate.text = "Nova versão ${newer.name} disponível"
                    slideBanner(updateBanner, show = true)
                }
                manual && release.isFailure ->
                    Toast.makeText(this@MainActivity, "Não foi possível verificar (sem internet?)", Toast.LENGTH_LONG).show()
                manual -> Toast.makeText(this@MainActivity, "O app já está na versão mais nova", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Menu lateral ----------

    private fun renderDrawer() {
        val event = prefs.activeEvent
        findViewById<TextView>(R.id.tvDrawerEvent).text = event?.name ?: "Nenhum evento"
        findViewById<TextView>(R.id.tvDrawerSub).text =
            listOfNotNull(prefs.gate, event?.showId, event?.baseUrl()?.ifEmpty { null }).joinToString(" · ")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.tvDrawerVersion).text = "Versão $version"

        drawerItems.removeAllViews()
        drawerItem(R.drawable.ic_list, "Eventos", "Trocar o evento em uso") {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        drawerItem(R.drawable.ic_camera, "Cadastrar evento", "Ler o QR: preenche Show ID e Token") {
            startActivity(Intent(this, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_SCAN_NOW, true))
        }
        drawerItem(R.drawable.ic_server, "Conexão com o servidor", prefs.hostForNewEvent().ifEmpty { "IP do Q-Box" }) {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        drawerItem(R.drawable.ic_settings, "Configurações do leitor", "Portão, PIN, volume e modo") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        drawerItem(R.drawable.ic_download, "Verificar atualização", null, needsPin = false) {
            checkForUpdate(manual = true)
        }
    }

    private fun drawerItem(icon: Int, title: String, subtitle: String?, needsPin: Boolean = true, action: () -> Unit) {
        val dp = resources.displayMetrics.density
        drawerItems.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            setOnClickListener {
                haptic(it)
                drawer.closeDrawer(GravityCompat.END)
                if (needsPin) withPin(action) else action()
            }
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                    marginEnd = (16 * dp).toInt()
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                subtitle?.let {
                    addView(TextView(context).apply {
                        text = it
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
            })
        })
    }

    // ---------- Som / toque ----------

    private fun maximizeVolume() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.END)) drawer.closeDrawer(GravityCompat.END)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Executa [action] após o PIN do administrador (tolerância de 2 min; setores pedem sempre). */
    private fun withPin(action: () -> Unit) = pinPad.require(onOk = action)

    companion object {
        private const val HEARTBEAT_MS = 30_000L
        private val SECTOR_COLORS = listOf(
            Color.parseColor("#026CDF"), Color.parseColor("#7B3FE4"), Color.parseColor("#00838F"),
            Color.parseColor("#AD1457"), Color.parseColor("#EF6C00"), Color.parseColor("#2E7D32"),
        )
    }
}
