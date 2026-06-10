package org.shuttlelab.netpulse

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var urlInput: AutoCompleteTextView
    private lateinit var intervalInput: EditText
    private lateinit var logKeepInput: EditText
    private lateinit var vibrationSwitch: Switch
    private lateinit var soundSwitch: Switch
    private lateinit var languageValue: TextView

    // 语言选项 code 与显示
    private val langCodes = arrayOf("system", "en", "zh")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("vpnchecker", MODE_PRIVATE)

        urlInput = findViewById(R.id.urlInput)
        intervalInput = findViewById(R.id.intervalInput)
        logKeepInput = findViewById(R.id.logKeepInput)
        vibrationSwitch = findViewById(R.id.vibrationSwitch)
        soundSwitch = findViewById(R.id.soundSwitch)
        languageValue = findViewById(R.id.languageValue)

        val presets = resources.getStringArray(R.array.url_presets)
        urlInput.setAdapter(NoFilterAdapter(this, presets))
        urlInput.threshold = 0
        urlInput.setOnClickListener { urlInput.showDropDown() }

        urlInput.setText(prefs.getString("url", CheckerService.DEFAULT_URL))
        intervalInput.setText(prefs.getInt("interval", 15).toString())
        logKeepInput.setText(prefs.getInt("log_keep", LogStore.DEFAULT_KEEP).toString())
        vibrationSwitch.isChecked = prefs.getBoolean("vibration", true)
        soundSwitch.isChecked = prefs.getBoolean("sound", false)

        languageValue.text = langLabel(currentLang())
        findViewById<LinearLayout>(R.id.languageRow).setOnClickListener { showLanguageDialog() }

        findViewById<ImageView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
    }

    private fun currentLang() = prefs.getString("lang", "system") ?: "system"

    private fun langLabel(code: String) = when (code) {
        "en" -> getString(R.string.lang_en)
        "zh" -> getString(R.string.lang_zh)
        else -> getString(R.string.lang_system)
    }

    private fun showLanguageDialog() {
        val labels = langCodes.map { langLabel(it) }.toTypedArray()
        val current = langCodes.indexOf(currentLang()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                applyLanguage(langCodes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun applyLanguage(code: String) {
        prefs.edit().putString("lang", code).apply()
        val locales = when (code) {
            "en" -> LocaleListCompat.forLanguageTags("en")
            "zh" -> LocaleListCompat.forLanguageTags("zh")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        // 触发界面以新语言重建
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun save() {
        val url = urlInput.text.toString().trim().ifEmpty { CheckerService.DEFAULT_URL }
        val interval = intervalInput.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: 15
        val logKeep = logKeepInput.text.toString().toIntOrNull()
            ?.coerceIn(LogStore.MIN_KEEP, LogStore.MAX_KEEP) ?: LogStore.DEFAULT_KEEP

        prefs.edit()
            .putString("url", url)
            .putInt("interval", interval)
            .putInt("log_keep", logKeep)
            .putBoolean("vibration", vibrationSwitch.isChecked)
            .putBoolean("sound", soundSwitch.isChecked)
            .apply()

        // 立即按新上限裁剪历史日志
        LogStore.trimToLimit(this)

        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 不做前缀过滤的下拉适配器：点击输入框即展示全部预设地址 */
    private class NoFilterAdapter(context: android.content.Context, items: Array<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
        private val all = items.toList()
        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = all; count = all.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                notifyDataSetChanged()
        }
        override fun getFilter(): Filter = noFilter
    }
}
