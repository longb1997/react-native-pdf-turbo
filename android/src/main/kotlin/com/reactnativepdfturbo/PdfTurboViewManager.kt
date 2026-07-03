package com.reactnativepdfturbo

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * ViewManager que expõe o PdfTurboView para o React Native
 * 
 * Props suportadas:
 * - source: String (caminho do arquivo PDF)
 * - page: Int (página atual, 0-indexed)
 * - maximumZoom: Float (zoom máximo permitido)
 * - enableAntialiasing: Boolean (habilitar antialiasing)
 */
class PdfTurboViewManager : SimpleViewManager<PdfTurboView>() {

    companion object {
        const val REACT_CLASS = "PdfTurboView"
    }

    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): PdfTurboView {
        return PdfTurboView(reactContext)
    }

    @ReactProp(name = "source")
    fun setSource(view: PdfTurboView, source: String?) {
        source?.let { view.setSource(it) }
    }

    @ReactProp(name = "page", defaultInt = 0)
    fun setPage(view: PdfTurboView, page: Int) {
        view.setPage(page)
    }

    @ReactProp(name = "maximumZoom", defaultFloat = 5.0f)
    fun setMaximumZoom(view: PdfTurboView, maximumZoom: Float) {
        view.setMaximumZoom(maximumZoom)
    }

    @ReactProp(name = "enableAntialiasing", defaultBoolean = true)
    fun setEnableAntialiasing(view: PdfTurboView, enableAntialiasing: Boolean) {
        view.setEnableAntialiasing(enableAntialiasing)
    }

    @ReactProp(name = "password")
    fun setPassword(view: PdfTurboView, password: String?) {
        view.setPassword(password ?: "")
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any>? {
        return MapBuilder.builder<String, Any>()
            .put("onLoadComplete", MapBuilder.of("registrationName", "onLoadComplete"))
            .put("onError", MapBuilder.of("registrationName", "onError"))
            .put("onPageCount", MapBuilder.of("registrationName", "onPageCount"))
            .put("onPasswordRequired", MapBuilder.of("registrationName", "onPasswordRequired"))
            .build()
    }

    override fun onDropViewInstance(view: PdfTurboView) {
        super.onDropViewInstance(view)
        view.cleanup()
    }
}
