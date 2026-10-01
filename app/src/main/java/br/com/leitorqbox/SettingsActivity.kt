package br.com.leitorqbox

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Configurações do aparelho. Os dados do show ficam em cada evento (EventsActivity). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etGate: EditText
    private lateinit var etPin: EditText
    private lateinit var swCheckOnly: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.tvTitle).text = "Configurações · v$version"

        etGate = findViewById(R.id.etGate)
        etPin = findViewById(R.id.etPin)
        swCheckOnly = findViewById(R.id.swCheckOnly)

        etGate.setText(prefs.gate)
        etPin.setText(prefs.pin)
        swCheckOnly.isChecked = prefs.checkOnly

        findViewById<Button>(R.id.btnEvents).setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun save() {
        val gate = etGate.text.toString().trim()
        if (gate.isEmpty()) {
            Toast.makeText(this, "Informe o nome deste leitor / portão", Toast.LENGTH_LONG).show()
            return
        }
        prefs.gate = gate
        prefs.pin = etPin.text.toString().trim()
        prefs.checkOnly = swCheckOnly.isChecked
        Toast.makeText(this, "Configuração salva", Toast.LENGTH_SHORT).show()
        finish()
    }
}
