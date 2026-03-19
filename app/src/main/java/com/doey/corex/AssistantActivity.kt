package com.doey.corex

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

class AssistantActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ventana transparente que no bloquea
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        // Iniciar el overlay service si no está corriendo
        startForegroundService(Intent(this, OverlayService::class.java))

        // Terminar esta activity — el overlay se muestra solo
        finish()
    }
}
