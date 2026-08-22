package com.perecvpn.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.telegramButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/perec?start=ref_2D7TMSVG")))
        }
        findViewById<Button>(R.id.vpnButton).setOnClickListener {
            startActivity(Intent(this, VpnActivity::class.java))
        }
    }
}
