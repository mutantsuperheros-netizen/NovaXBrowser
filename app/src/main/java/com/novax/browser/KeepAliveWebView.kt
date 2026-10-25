package com.novax.browser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

class KeepAliveWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {
    var allowBackgroundPlay: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (allowBackgroundPlay && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
