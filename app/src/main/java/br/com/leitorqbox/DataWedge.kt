package br.com.leitorqbox

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Integração com o DataWedge (scanner da Zebra TC22).
 * Cria automaticamente um profile associado a este app que entrega cada leitura
 * como broadcast com a ação [SCAN_ACTION] (sem digitar no teclado).
 */
object DataWedge {

    const val SCAN_ACTION = "br.com.leitorqbox.SCAN"
    const val EXTRA_DATA = "com.symbol.datawedge.data_string"

    private const val PROFILE_NAME = "LeitorQBOX"
    private const val DW_PACKAGE = "com.symbol.datawedge"
    private const val DW_ACTION = "com.symbol.datawedge.api.ACTION"

    fun configureProfile(context: Context) {
        val appConfig = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        val barcode = plugin("BARCODE") {
            putString("scanner_selection", "auto")
            putString("scanner_input_enabled", "true")
            // Ingressos chegam em QR (app), PDF417/Code128 (PDF/impresso) e outros formatos.
            listOf(
                "decoder_qrcode", "decoder_pdf417", "decoder_code128", "decoder_datamatrix",
                "decoder_aztec", "decoder_code39", "decoder_ean13", "decoder_i2of5",
            ).forEach { putString(it, "true") }
        }
        val intentOutput = plugin("INTENT") {
            putString("intent_output_enabled", "true")
            putString("intent_action", SCAN_ACTION)
            putString("intent_category", Intent.CATEGORY_DEFAULT)
            putString("intent_delivery", "2") // 2 = broadcast
        }
        val keystroke = plugin("KEYSTROKE") {
            putString("keystroke_output_enabled", "false")
        }

        // CREATE_IF_NOT_EXIST cria o profile na primeira vez; UPDATE aplica a config
        // nova num profile que já existia (de versões anteriores do app).
        listOf("CREATE_IF_NOT_EXIST", "UPDATE").forEach { mode ->
            val profile = Bundle().apply {
                putString("PROFILE_NAME", PROFILE_NAME)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", mode)
                putParcelableArray("APP_LIST", arrayOf(appConfig))
                putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(barcode, intentOutput, keystroke))
            }
            send(context, "com.symbol.datawedge.api.SET_CONFIG", profile)
        }
    }

    /** Aciona o scanner pelo botão da tela (equivalente ao gatilho físico). */
    fun softTrigger(context: Context) {
        send(context, "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "TOGGLE_SCANNING")
    }

    private fun plugin(name: String, params: Bundle.() -> Unit) = Bundle().apply {
        putString("PLUGIN_NAME", name)
        putString("RESET_CONFIG", "true")
        putBundle("PARAM_LIST", Bundle().apply(params))
    }

    private fun send(context: Context, extraKey: String, value: Any) {
        val intent = Intent(DW_ACTION).setPackage(DW_PACKAGE)
        when (value) {
            is Bundle -> intent.putExtra(extraKey, value)
            is String -> intent.putExtra(extraKey, value)
        }
        context.sendBroadcast(intent)
    }
}
