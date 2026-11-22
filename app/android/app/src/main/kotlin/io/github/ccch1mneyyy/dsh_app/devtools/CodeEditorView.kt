package io.github.ccch1mneyyy.dsh_app.devtools

import android.content.Context
import android.view.View
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * 开发者工具 › 编辑器: a Sora code editor as a Flutter platform view.
 *
 * The view loads the file itself and saves it back through the same path guard the rest of the
 * devtools bridge uses, so Dart only ever names a path. One editor is active at a time (the page
 * shows one), and the shared channel talks to that one.
 */
internal class CodeEditorView(
    context: Context,
    messenger: BinaryMessenger,
    creation: Map<String, Any?>?,
) : PlatformView {
    private val editor = CodeEditor(context)
    private val channel = MethodChannel(messenger, CHANNEL)
    private var file: File? = null

    init {
        editor.setEditable(true)
        editor.setWordwrap(false)
        editor.setTextSize(36f)
        val path = creation?.get("path") as? String
        if (path != null) open(path)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "save" -> {
                    val f = file
                    if (f == null) result.error("devtools", "no file", null)
                    else try {
                        f.writeText(editor.text.toString())
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("devtools", e.message ?: "save failed", null)
                    }
                }
                "reload" -> {
                    val f = file
                    if (f == null) result.error("devtools", "no file", null)
                    else open(f.path).also { result.success(true) }
                }
                "text" -> result.success(editor.text.toString())
                else -> result.notImplemented()
            }
        }
        active = this
    }

    private fun open(path: String) {
        try {
            val f = File(path)
            if (!f.isFile) return
            file = f
            editor.setText(f.readText())
        } catch (_: Exception) {
        }
    }

    override fun getView(): View = editor

    override fun dispose() {
        channel.setMethodCallHandler(null)
        if (active === this) active = null
    }

    class Factory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, id: Int, args: Any?): PlatformView =
            CodeEditorView(context, messenger, args as? Map<String, Any?>)
    }

    companion object {
        const val CHANNEL = "dsh/devtools/editor"
        private var active: CodeEditorView? = null
    }
}
