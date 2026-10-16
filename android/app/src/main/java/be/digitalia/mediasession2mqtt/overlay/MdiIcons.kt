package be.digitalia.mediasession2mqtt.overlay

import android.content.Context
import android.graphics.Typeface

/**
 * Material Design Icons, the icon set used by Home Assistant ("mdi:door-open").
 * The font and the name -> codepoint table are bundled in the assets and loaded lazily.
 */
object MdiIcons {
    @Volatile
    private var typeface: Typeface? = null

    @Volatile
    private var codepoints: Map<String, Int>? = null

    fun typeface(context: Context): Typeface {
        return typeface ?: Typeface.createFromAsset(context.assets, "mdi.ttf").also { typeface = it }
    }

    /**
     * The glyph for an icon name with or without the "mdi:" prefix, or [fallback] then null if unknown.
     */
    fun glyph(context: Context, name: String?, fallback: String): String {
        val table = codepoints ?: load(context)
        val key = name?.removePrefix("mdi:")?.trim()?.lowercase()
        val codepoint = (key?.let { table[it] }) ?: table[fallback] ?: return ""
        return String(Character.toChars(codepoint))
    }

    private fun load(context: Context): Map<String, Int> {
        val map = HashMap<String, Int>(8192)
        context.assets.open("mdi_codepoints.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    map[line.substring(0, separator)] = line.substring(separator + 1).toInt(16)
                }
            }
        }
        codepoints = map
        return map
    }
}
