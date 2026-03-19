package com.doey.corex

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnActivar = findViewById<MaterialButton>(R.id.btnActivar)
        val btnAjustes = findViewById<MaterialButton>(R.id.btnAjustes)

        btnActivar.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Activa el permiso y vuelve", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Corex activado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        btnAjustes.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
