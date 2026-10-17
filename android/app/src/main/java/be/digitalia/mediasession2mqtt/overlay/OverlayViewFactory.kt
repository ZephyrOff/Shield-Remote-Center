package be.digitalia.mediasession2mqtt.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

internal class BuiltOverlay(
    val root: View,
    val image: ImageView?,
    val firstFocus: View?,
    val buttons: List<View>,
    /** Surface of the live video, when the request has a stream. The [image] is displayed over it until it plays. */
    val video: TextureView? = null
)

/** Root view which turns the Back key into a dismissal. */
private class OverlayRoot(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                onBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Builds the views of the 4 overlay kinds. Dimensions are expressed in a 1280 px wide reference screen
 * and scaled to the real screen width.
 */
internal class OverlayViewFactory(private val context: Context) {
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val scale = screenWidth / REFERENCE_WIDTH

    private fun u(value: Int): Int = (value * scale).roundToInt()

    fun build(request: OverlayRequest, onButton: (OverlayButton) -> Unit, onBack: () -> Unit): BuiltOverlay {
        return when (request.kind) {
            OverlayKind.TOAST -> buildToast(request, onButton, onBack)
            OverlayKind.BANNER -> buildBanner(request, onButton, onBack)
            OverlayKind.POPUP -> buildPopup(request, onButton, onBack)
            OverlayKind.CAMERA -> buildCamera(request, onButton, onBack)
        }
    }

    private fun buildToast(request: OverlayRequest, onButton: (OverlayButton) -> Unit, onBack: () -> Unit): BuiltOverlay {
        val accent = request.color ?: DEFAULT_ACCENT
        val root = OverlayRoot(context, onBack)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(14), u(12), u(14), u(12))
            background = roundedRect(0xF01C1C1E.toInt(), PILL_RADIUS)
            minimumWidth = min(u(520), screenWidth - u(48))
        }
        row.addView(iconCircle(request.icon, "bell", u(56), accent, Color.WHITE), LinearLayout.LayoutParams(u(56), u(56)))
        row.addView(
            textColumn(request, 24, 19, Color.WHITE, 0xFFA9A9B0.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = u(16)
                marginEnd = u(16)
            }
        )
        val buttons = buttonRow(request, onButton, 19, u(12), u(20), 0xFF3A3A3C.toInt(), Color.WHITE, accent, Color.WHITE, Color.WHITE, weighted = false)
        buttons.row?.let { row.addView(it) }
        root.addView(row)
        return BuiltOverlay(root, null, buttons.first, buttons.views)
    }

    private fun buildBanner(request: OverlayRequest, onButton: (OverlayButton) -> Unit, onBack: () -> Unit): BuiltOverlay {
        val background = request.color ?: DEFAULT_BANNER
        val foreground = if (isLight(background)) 0xFF1C1500.toInt() else Color.WHITE
        val root = OverlayRoot(context, onBack)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(18), u(14), u(16), u(14))
            this.background = roundedRect(background, PILL_RADIUS)
            minimumWidth = min(u(1000), screenWidth - u(48))
        }
        row.addView(iconCircle(request.icon, "alert", u(64), foreground, background), LinearLayout.LayoutParams(u(64), u(64)))
        row.addView(
            textColumn(request, 28, 20, foreground, withAlpha(foreground, 0xC0)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = u(18)
                marginEnd = u(18)
            }
        )
        val buttons = buttonRow(
            request, onButton, 20, u(14), u(24),
            withAlpha(foreground, 0x24), foreground, foreground, background, foreground, weighted = false
        )
        buttons.row?.let { row.addView(it) }
        root.addView(row)
        return BuiltOverlay(root, null, buttons.first, buttons.views)
    }

    private fun buildPopup(request: OverlayRequest, onButton: (OverlayButton) -> Unit, onBack: () -> Unit): BuiltOverlay {
        val accent = request.color ?: DEFAULT_ACCENT
        val root = OverlayRoot(context, onBack).apply { setBackgroundColor(0x73000000) }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(44), u(36), u(44), u(36))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                val r = u(44).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var media: MediaViews? = null
        if (request.hasMedia) {
            media = mediaViews(request, u(24))
            header.addView(media.container, LinearLayout.LayoutParams(u(240), u(150)))
        } else {
            header.addView(iconCircle(request.icon, "bell-ring", u(96), accent, Color.WHITE), LinearLayout.LayoutParams(u(96), u(96)))
        }
        header.addView(
            textColumn(request, 38, 23, Color.WHITE, 0xFFA9A9B0.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = u(28) }
        )
        sheet.addView(header)
        val buttons = buttonRow(request, onButton, 23, u(20), u(14), 0xFF3A3A3C.toInt(), Color.WHITE, accent, Color.WHITE, Color.WHITE, weighted = true)
        buttons.row?.let {
            sheet.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = u(26) })
        }
        root.addView(
            sheet,
            FrameLayout.LayoutParams(min(u(1040), screenWidth), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        return BuiltOverlay(root, media?.image, buttons.first, buttons.views, media?.video)
    }

    private fun buildCamera(request: OverlayRequest, onButton: (OverlayButton) -> Unit, onBack: () -> Unit): BuiltOverlay {
        val accent = request.color ?: DEFAULT_ACCENT
        val root = OverlayRoot(context, onBack)
        val card = FrameLayout(context).apply {
            background = roundedRect(0xFF10171F.toInt(), u(32))
            clipRounded(this, u(32))
        }
        val media = mediaViews(request, u(32))
        card.addView(media.container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(10), u(5), u(14), u(5))
            background = roundedRect(0xD91C1C1E.toInt(), PILL_RADIUS)
        }
        val chipIcon = TextView(context).apply {
            typeface = MdiIcons.typeface(context)
            text = MdiIcons.glyph(context, request.icon, "cctv")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(22).toFloat())
            setTextColor(accent)
        }
        chip.addView(chipIcon)
        chip.addView(
            text(request.primary, 17, true, Color.WHITE),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = u(8) }
        )
        top.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        request.secondary?.let {
            val subtitle = text(it, 16, false, Color.WHITE).apply { setShadowLayer(u(4).toFloat(), 0f, 0f, Color.BLACK) }
            top.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = u(6)
                marginStart = u(8)
            })
        }
        card.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = u(16)
            topMargin = u(16)
            rightMargin = u(16)
        })

        val buttons = buttonRow(request, onButton, 17, u(11), u(6), 0xD91C1C1E.toInt(), Color.WHITE, accent, Color.WHITE, Color.WHITE, weighted = true)
        buttons.row?.let {
            card.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = u(12)
                rightMargin = u(12)
                bottomMargin = u(12)
            })
        }
        root.addView(card, FrameLayout.LayoutParams(u(440), u(248)))
        return BuiltOverlay(root, media.image, buttons.first, buttons.views, media.video)
    }

    private class MediaViews(val container: FrameLayout, val image: ImageView, val video: TextureView?)

    /** The media of a popup or a camera: pictures, and a video surface below them if there is a stream. */
    private fun mediaViews(request: OverlayRequest, cornerRadius: Int): MediaViews {
        val container = FrameLayout(context).apply {
            background = roundedRect(0xFF10171F.toInt(), cornerRadius)
            clipRounded(this, cornerRadius)
        }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val video = if (request.streamUrl != null) TextureView(context) else null
        video?.let { container.addView(it, match) }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        container.addView(image, FrameLayout.LayoutParams(match))
        return MediaViews(container, image, video)
    }

    private class ButtonRow(val row: LinearLayout?, val first: View?, val views: List<View>)

    private fun buttonRow(
        request: OverlayRequest,
        onButton: (OverlayButton) -> Unit,
        textSize: Int,
        padV: Int,
        padH: Int,
        normalBg: Int,
        normalFg: Int,
        focusBg: Int,
        focusFg: Int,
        ring: Int,
        weighted: Boolean
    ): ButtonRow {
        if (request.buttons.isEmpty()) {
            return ButtonRow(null, null, emptyList())
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        request.buttons.forEachIndexed { index, button ->
            val view = TextView(context).apply {
                text = button.label
                gravity = Gravity.CENTER
                isSingleLine = true
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(textSize).toFloat())
                setTextColor(
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_focused, android.R.attr.state_window_focused),
                            intArrayOf(android.R.attr.state_pressed),
                            intArrayOf(android.R.attr.state_selected),
                            intArrayOf()
                        ),
                        intArrayOf(focusFg, focusFg, focusFg, normalFg)
                    )
                )
                setPadding(padH, padV, padH, padV)
                background = StateListDrawable().apply {
                    val focused = GradientDrawable().apply {
                        setColor(focusBg)
                        cornerRadius = PILL_RADIUS
                        setStroke(u(3), ring)
                    }
                    // A view keeps its focus while its window has not: only a focused window draws it
                    addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_window_focused), focused)
                    addState(intArrayOf(android.R.attr.state_pressed), focused)
                    // Selection driven by the key interception service, independent from the window focus
                    addState(intArrayOf(android.R.attr.state_selected), focused)
                    addState(intArrayOf(), roundedRect(normalBg, PILL_RADIUS))
                }
                isFocusable = true
                isClickable = true
                setOnClickListener { onButton(button) }
            }
            val params = if (weighted) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            if (index > 0) {
                params.marginStart = u(8)
            }
            row.addView(view, params)
        }
        return ButtonRow(row, row.getChildAt(0), List(row.childCount) { row.getChildAt(it) })
    }

    private fun textColumn(request: OverlayRequest, primarySize: Int, secondarySize: Int, primaryColor: Int, secondaryColor: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(request.primary, primarySize, true, primaryColor))
            request.secondary?.let { addView(text(it, secondarySize, false, secondaryColor)) }
        }
    }

    private fun text(value: String, size: Int, bold: Boolean, color: Int): TextView {
        return TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 3
        }
    }

    private fun iconCircle(icon: String?, fallback: String, size: Int, circle: Int, glyphColor: Int): View {
        return TextView(context).apply {
            typeface = MdiIcons.typeface(context)
            text = MdiIcons.glyph(context, icon, fallback)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(glyphColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.55f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circle)
            }
        }
    }

    private fun roundedRect(color: Int, radius: Int): GradientDrawable = roundedRect(color, radius.toFloat())

    private fun roundedRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun clipRounded(view: View, radius: Int) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius.toFloat())
            }
        }
        view.clipToOutline = true
    }

    private fun isLight(color: Int): Boolean {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 150
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        private const val REFERENCE_WIDTH = 1280f
        private const val PILL_RADIUS = 1000f
        private val DEFAULT_ACCENT = 0xFF0A84FF.toInt()
        private val DEFAULT_BANNER = 0xFFFFD60A.toInt()
    }
}
