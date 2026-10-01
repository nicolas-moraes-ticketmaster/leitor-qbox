package br.com.leitorqbox

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * PIN do administrador em teclado numérico (BottomSheet).
 * - Sem PIN cadastrado: pede para criar (digitar e confirmar).
 * - Com PIN: confere; 5 erros seguidos bloqueiam por 30 s.
 * - [alwaysAsk] = true ignora a tolerância de 2 min (usado nos setores).
 */
class PinPad(private val activity: AppCompatActivity, private val prefs: Prefs) {

    private var unlockedUntil = 0L
    private var failures = 0
    private var lockedUntil = 0L
    private val standard = PathInterpolatorCompat.create(0.3f, 0f, 0.2f, 1f)

    fun require(alwaysAsk: Boolean = false, onOk: () -> Unit) {
        if (!alwaysAsk && prefs.pin.isNotEmpty() && SystemClock.elapsedRealtime() < unlockedUntil) {
            onOk()
            return
        }
        if (prefs.pin.isEmpty()) show(Mode.CREATE, onOk) else show(Mode.VERIFY, onOk)
    }

    private enum class Mode { VERIFY, CREATE, CONFIRM }

    private fun show(mode: Mode, onOk: () -> Unit, firstEntry: String = "") {
        val dialog = BottomSheetDialog(activity)
        val v = activity.layoutInflater.inflate(R.layout.sheet_pin, null)
        dialog.setContentView(v)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val tvTitle = v.findViewById<TextView>(R.id.tvPinTitle)
        val tvSub = v.findViewById<TextView>(R.id.tvPinSub)
        val dots = v.findViewById<LinearLayout>(R.id.pinDots)
        val tvError = v.findViewById<TextView>(R.id.tvPinError)
        val keypad = v.findViewById<GridLayout>(R.id.keypad)
        val btnOk = v.findViewById<MaterialButton>(R.id.btnPinOk)
        v.findViewById<View>(R.id.btnPinClose).setOnClickListener { dialog.dismiss() }

        tvTitle.text = when (mode) {
            Mode.VERIFY -> "PIN do administrador"
            Mode.CREATE -> "Criar PIN do administrador"
            Mode.CONFIRM -> "Confirme o novo PIN"
        }
        tvSub.text = when (mode) {
            Mode.VERIFY -> "Necessário para alterar setores e configurações."
            Mode.CREATE -> "Escolha de 4 a 8 dígitos. Ele protege setores, eventos e configurações."
            Mode.CONFIRM -> "Digite o mesmo PIN de novo."
        }

        val entry = StringBuilder()
        val maxLen = if (mode == Mode.VERIFY) prefs.pin.length else 8
        val dp = activity.resources.displayMetrics.density

        fun renderDots() {
            dots.removeAllViews()
            val slots = if (mode == Mode.VERIFY) prefs.pin.length else maxOf(4, entry.length)
            repeat(slots) { i ->
                dots.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()).apply {
                        marginStart = (8 * dp).toInt(); marginEnd = (8 * dp).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (i < entry.length) setColor(ContextCompat.getColor(activity, R.color.text_primary))
                        else setStroke((2 * dp).toInt(), ContextCompat.getColor(activity, R.color.divider_strong))
                    }
                })
            }
            btnOk.isEnabled = entry.length >= 4
        }

        fun fail(message: String) {
            tvError.text = message
            tvError.visibility = View.VISIBLE
            entry.clear()
            renderDots()
            dots.animate().cancel()
            dots.translationX = 0f
            val shake = 8 * dp
            dots.animate().translationX(shake).setDuration(50).withEndAction {
                dots.animate().translationX(-shake).setDuration(80).withEndAction {
                    dots.animate().translationX(0f).setDuration(60).setInterpolator(standard).start()
                }.start()
            }.start()
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        fun submit() {
            val now = SystemClock.elapsedRealtime()
            when (mode) {
                Mode.VERIFY -> {
                    if (now < lockedUntil) {
                        fail("Bloqueado. Tente em ${(lockedUntil - now) / 1000 + 1} s.")
                        return
                    }
                    if (entry.toString() == prefs.pin) {
                        failures = 0
                        unlockedUntil = now + 2 * 60 * 1000
                        dialog.dismiss()
                        onOk()
                    } else {
                        failures++
                        if (failures >= 5) {
                            failures = 0
                            lockedUntil = now + 30_000
                            fail("5 tentativas erradas. Bloqueado por 30 s.")
                        } else {
                            fail("PIN incorreto · ${5 - failures} tentativa(s) restante(s)")
                        }
                    }
                }
                Mode.CREATE -> {
                    val first = entry.toString()
                    dialog.dismiss()
                    show(Mode.CONFIRM, onOk, first)
                }
                Mode.CONFIRM -> {
                    if (entry.toString() == firstEntry) {
                        prefs.pin = firstEntry
                        unlockedUntil = now + 2 * 60 * 1000
                        dialog.dismiss()
                        onOk()
                    } else {
                        fail("Os PINs não conferem. Digite de novo.")
                    }
                }
            }
        }

        // Teclado 3x4: 1-9, Apagar, 0, Limpar.
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Apagar", "0", "Limpar")
        keys.forEachIndexed { i, key ->
            val b = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = key
                textSize = if (key.length > 1) 14f else 22f
                insetTop = 0; insetBottom = 0
                minimumHeight = (56 * dp).toInt()
                cornerRadius = (4 * dp).toInt()
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                strokeColor = ContextCompat.getColorStateList(activity, R.color.divider_strong)
                contentDescription = key
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvError.visibility = View.INVISIBLE
                    when (key) {
                        "Apagar" -> if (entry.isNotEmpty()) entry.deleteCharAt(entry.length - 1)
                        "Limpar" -> entry.clear()
                        else -> if (entry.length < maxLen) entry.append(key)
                    }
                    renderDots()
                    // Conferência automática ao completar os dígitos do PIN.
                    if (mode == Mode.VERIFY && entry.length == prefs.pin.length) submit()
                    if (mode == Mode.CONFIRM && entry.length == firstEntry.length) submit()
                }
            }
            keypad.addView(b, GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)).apply {
                width = 0
                setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            })
        }

        btnOk.text = when (mode) {
            Mode.VERIFY -> "Entrar"
            Mode.CREATE -> "Continuar"
            Mode.CONFIRM -> "Salvar PIN"
        }
        btnOk.setOnClickListener { submit() }
        renderDots()
        dialog.show()
    }
}
