package com.doey.corex

import android.graphics.Rect

data class ScreenElement(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean
)
