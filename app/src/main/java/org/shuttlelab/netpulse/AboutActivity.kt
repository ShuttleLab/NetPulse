package org.shuttlelab.netpulse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.backBtn).setOnClickListener { finish() }

        val version = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "v1.0"
        }
        findViewById<TextView>(R.id.versionText).text = version

        findViewById<TextView>(R.id.siteLink).setOnClickListener {
            openUrl("https://shuttlelab.org/")
        }
        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            openUrl("https://github.com/ShuttleLab")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }
}
