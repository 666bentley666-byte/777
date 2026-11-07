package com.retro.currency

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val blue = Color.rgb(0, 51, 153)
    private val border = Color.rgb(145, 145, 145)
    private val bg = Color.rgb(229, 229, 229)
    private val white = Color.WHITE
    private val codes = arrayOf("USD", "UZS", "RUB")
    private val flags = arrayOf("🇺🇸", "🇺🇿", "🇷🇺")
    private val inputs = arrayOfNulls<EditText>(3)
    private var usdToUzs = 0.0
    private var usdToRub = 0.0
    private var active = 0
    private var internalChange = false
    private lateinit var rateStatus: TextView
    private var calcText = "1"
    private var pendingOp: String? = null
    private var storedValue: Double? = null
    private var resetOnDigit = false
    private lateinit var calcDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        val prefs = getSharedPreferences("rates", MODE_PRIVATE)
        usdToUzs = prefs.getFloat("uzs", 0f).toDouble()
        usdToRub = prefs.getFloat("rub", 0f).toDouble()
        val cachedAt = prefs.getString("updated", null)
        if (usdToUzs > 0 && usdToRub > 0) {
            rateStatus.text = "Сохранённый курс · ${cachedAt ?: "дата неизвестна"}"
            recalculateFrom(0, "1")
        } else {
            rateStatus.text = "Загрузка курса…"
        }
        fetchRates()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(white)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))

        for (i in 0..2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(white)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(white); setStroke(dp(1), border)
                }
            }
            val label = TextView(this).apply {
                text = "${flags[i]}  ${codes[i]}"
                setTextColor(blue)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(label, LinearLayout.LayoutParams(dp(112), dp(58)))
            val edit = EditText(this).apply {
                textSize = 25f
                setTextColor(Color.BLACK)
                setSingleLine(true)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setPadding(dp(4), 0, dp(4), 0)
                background = null
                setSelectAllOnFocus(false)
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) active = i }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (!internalChange && hasFocus()) {
                            active = i
                            recalculateFrom(i, s?.toString() ?: "")
                            calcText = s?.toString()?.ifBlank { "0" } ?: "0"
                            calcDisplay.text = calcText
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            inputs[i] = edit
            row.addView(edit, LinearLayout.LayoutParams(0, dp(58), 1f))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply {
                bottomMargin = dp(7)
            })
        }

        rateStatus = TextView(this).apply {
            text = "Курс загружается при запуске"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        panel.addView(rateStatus)

        val calcBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(white)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        calcDisplay = TextView(this).apply {
            text = "1"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(white); setStroke(dp(1), border)
            }
        }
        calcBox.addView(calcDisplay, LinearLayout.LayoutParams(-1, dp(58)).apply {
            bottomMargin = dp(6)
        })
        val keys = arrayOf(
            arrayOf("C", "⌫", "%", "÷"),
            arrayOf("7", "8", "9", "×"),
            arrayOf("4", "5", "6", "−"),
            arrayOf("1", "2", "3", "+"),
            arrayOf("+/−", "0", ".", "=")
        )
        for (line in keys) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in line) {
                val button = TextView(this).apply {
                    text = key
                    textSize = if (key.length > 2) 19f else 24f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (key in listOf("C","⌫","%","÷","×","−","+","=","+/−")) blue else Color.BLACK)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (key in listOf("C","⌫","%","÷","×","−","+","=","+/−")) Color.rgb(229,234,242) else Color.rgb(245,245,245))
                        setStroke(dp(1), border)
                    }
                    setOnClickListener { onCalc(key) }
                }
                val lp = LinearLayout.LayoutParams(0, dp(55), 1f)
                lp.setMargins(dp(2), dp(2), dp(2), dp(2))
                row.addView(button, lp)
            }
            calcBox.addView(row)
        }
        panel.addView(calcBox)
        setContentView(root)
        recalculateFrom(0, "1")
    }

    private fun recalculateFrom(index: Int, raw: String) {
        if (usdToUzs <= 0 || usdToRub <= 0) {
            if (index == 0) setValues(raw, "", "")
            return
        }
        val value = raw.replace(',', '.').toDoubleOrNull()
        if (value == null) {
            val vals = arrayOf("", "", "")
            vals[index] = raw
            setValues(vals[0], vals[1], vals[2])
            return
        }
        val usd = when (index) {
            0 -> value
            1 -> value / usdToUzs
            else -> value / usdToRub
        }
        val vals = arrayOf(usd, usd * usdToUzs, usd * usdToRub)
        val strings = vals.mapIndexed { i, v -> if (i == index) raw else format(v) }
        setValues(strings[0], strings[1], strings[2])
    }

    private fun setValues(a: String, b: String, c: String) {
        val vals = arrayOf(a, b, c)
        internalChange = true
        for (i in 0..2) {
            val e = inputs[i] ?: continue
            if (e.text.toString() != vals[i]) {
                val pos = e.selectionStart.coerceAtLeast(0)
                e.setText(vals[i])
                if (e.hasFocus()) e.setSelection(pos.coerceAtMost(e.text.length))
            }
        }
        internalChange = false
    }

    private fun format(v: Double): String {
        if (!v.isFinite()) return "0"
        val symbols = DecimalFormatSymbols(Locale.US)
        val df = DecimalFormat("#,##0.####", symbols)
        return df.format(v)
    }

    private fun fetchRates() {
        thread {
            try {
                val conn = (URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val rates = json.getJSONObject("rates")
                val uzs = rates.getDouble("UZS")
                val rub = rates.getDouble("RUB")
                if (uzs > 0 && rub > 0) {
                    usdToUzs = uzs; usdToRub = rub
                    val date = json.optString("time_last_update_utc", "обновлено")
                    getSharedPreferences("rates", MODE_PRIVATE).edit()
                        .putFloat("uzs", uzs.toFloat()).putFloat("rub", rub.toFloat())
                        .putString("updated", date).apply()
                    runOnUiThread {
                        rateStatus.text = "Курс обновлён: $date"
                        recalculateFrom(active, inputs[active]?.text?.toString() ?: "1")
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    rateStatus.text = if (usdToUzs > 0) "Нет интернета · используется сохранённый курс" else "Не удалось загрузить курс"
                }
            }
        }
    }

    private fun onCalc(key: String) {
        when (key) {
            "C" -> { calcText = "0"; pendingOp = null; storedValue = null; resetOnDigit = false }
            "⌫" -> { calcText = if (calcText.length > 1) calcText.dropLast(1) else "0" }
            "+/−" -> { calcText = if (calcText.startsWith("-")) calcText.drop(1) else "-$calcText" }
            "." -> if (!calcText.contains(".")) calcText += "."
            "%" -> calcText = ((calcText.toDoubleOrNull() ?: 0.0) / 100.0).toString()
            "+", "−", "×", "÷" -> {
                storedValue = calcText.toDoubleOrNull() ?: 0.0
                pendingOp = key
                resetOnDigit = true
            }
            "=" -> {
                val a = storedValue
                val b = calcText.toDoubleOrNull() ?: 0.0
                if (a != null) {
                    val result = when (pendingOp) {
                        "+" -> a + b
                        "−" -> a - b
                        "×" -> a * b
                        "÷" -> if (b == 0.0) 0.0 else a / b
                        else -> b
                    }
                    calcText = if (result.isFinite()) result.toString().trimZeros() else "0"
                    pendingOp = null; storedValue = null
                }
            }
            else -> {
                calcText = if (resetOnDigit || calcText == "0") key else calcText + key
                resetOnDigit = false
            }
        }
        calcDisplay.text = calcText
        recalculateFrom(active, calcText)
    }

    private fun String.trimZeros(): String =
        if (contains(".")) trimEnd('0').trimEnd('.') else this

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
