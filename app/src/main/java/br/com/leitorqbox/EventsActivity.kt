package br.com.leitorqbox

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/** Lista de eventos cadastrados: escolher o ativo, editar, compartilhar por QR, excluir. */
class EventsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_events)
        prefs = Prefs(this)
        list = findViewById(R.id.eventsList)

        findViewById<Button>(R.id.btnScanEvent).setOnClickListener {
            startActivity(Intent(this, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_SCAN_NOW, true))
        }
        findViewById<Button>(R.id.btnNewEvent).setOnClickListener {
            startActivity(Intent(this, EventEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val events = prefs.events
        val activeId = prefs.activeEvent?.id
        if (events.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nenhum evento cadastrado ainda."
                setTextColor(color(R.color.text_secondary))
            })
            return
        }
        events.forEach { e -> list.addView(card(e, e.id == activeId)) }
    }

    private fun card(e: Event, active: Boolean): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = (10 * dp).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 4 * dp
                setColor(color(R.color.card))
                if (active) setStroke((2 * dp).toInt(), color(R.color.valid))
            }
            isClickable = true
            setOnClickListener {
                prefs.activeEventId = e.id
                finish()
            }

            addView(TextView(context).apply {
                text = if (active) "${e.name} · Em uso" else e.name
                setTextColor(color(R.color.text_primary))
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                val kind = if (e.isVirtual) "VIRTUAL" else "show"
                val sectors = if (e.sectors.isEmpty()) "setores não carregados" else "${e.sectors.size} setor(es)"
                text = "${e.showId} · $kind · ${e.host.ifBlank { "sem IP" }} · $sectors"
                setTextColor(color(R.color.text_secondary))
                textSize = 13f
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                if (!active) addView(smallButton("Usar") { prefs.activeEventId = e.id; finish() })
                addView(smallButton("QR") { showShareQr(e) })
                addView(smallButton("Editar") {
                    startActivity(Intent(this@EventsActivity, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_EVENT_ID, e.id))
                })
                addView(smallButton("Excluir") { confirmDelete(e) })
            })
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { onClick() }
        }

    /** QR com o evento completo (nome, IP, ID, token) para cadastrar em outro leitor. */
    private fun showShareQr(e: Event) {
        val size = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val bitmap = BarcodeEncoder().encodeBitmap(EventQr.encode(e), BarcodeFormat.QR_CODE, size, size)
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(android.graphics.Color.WHITE)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage("Em outro TC22: Eventos → Ler QR do evento.")
            .setView(image)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun confirmDelete(e: Event) {
        AlertDialog.Builder(this)
            .setTitle("Excluir evento?")
            .setMessage("${e.name} (${e.showId}) será removido deste leitor. Nada muda no Q-Box.")
            .setPositiveButton("Excluir") { _, _ ->
                prefs.delete(e.id)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
}
