package com.example.parallaxlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.MediaStore
import android.net.Uri
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class LauncherScene(context: Context) : FrameLayout(context), Choreographer.FrameCallback {
    companion object {
        // Start here when tuning on a real phone.
        private const val MAX_PHYSICAL_ROLL_DEGREES = 85f
        private const val MAX_Y_DEGREES = 7f
        private const val SMOOTHING_TIME_SECONDS = 0.15f
    }

    var onRecenterRequested: () -> Unit = {}
    var onSetAsHomeRequested: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val atmosphere = AtmosphereView(context)
    private val floatingLayer = FrameLayout(context)
    private val edgeBlur = TiltEdgeBlurView(context)
    private val fixedControls = LinearLayout(context)
    private lateinit var setHomeButton: TextView
    private val clockText = textView(76f, Color.WHITE, Typeface.DEFAULT_BOLD)
    private val dateText = textView(16f, Color.argb(190, 255, 255, 255), Typeface.DEFAULT)
    private val sensorText = textView(12f, Color.rgb(125, 231, 255), Typeface.DEFAULT_BOLD)
    private val anglesText = textView(12f, Color.argb(160, 255, 255, 255), Typeface.MONOSPACE)

    private var targetY = 0f
    private var currentY = 0f
    private var targetEdgeTilt = 0f
    private var currentEdgeTilt = 0f
    private var rendering = false
    private var previousFrameNanos = 0L
    private var lastClockSecond = -1L
    private var lastDebugUpdateMillis = 0L

    init {
        setBackgroundColor(Color.rgb(7, 17, 31))
        atmosphere.scaleX = 1.04f
        atmosphere.scaleY = 1.04f
        addView(atmosphere, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        floatingLayer.cameraDistance = 8_000f * density
        floatingLayer.scaleX = 1.035f
        floatingLayer.scaleY = 1.035f
        addView(floatingLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildContent()
        addView(edgeBlur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildFixedControls()

        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            floatingLayer.setPadding(dp(28), top + dp(24), dp(28), bottom + dp(22))
            (fixedControls.layoutParams as LayoutParams).bottomMargin = bottom + dp(20)
            fixedControls.requestLayout()
            insets
        }
    }

    fun setTargetOrientation(
        @Suppress("UNUSED_PARAMETER") pitch: Float,
        roll: Float,
        @Suppress("UNUSED_PARAMETER") yaw: Float
    ) {
        // Only left/right roll drives the effect. The physical range extends to
        // 85 degrees, but it is curved into a subtle seven-degree UI rotation.
        val normalizedRoll = (roll / MAX_PHYSICAL_ROLL_DEGREES).coerceIn(-1f, 1f)
        val curvedRoll = if (normalizedRoll < 0f) {
            -sqrt(-normalizedRoll)
        } else {
            sqrt(normalizedRoll)
        }
        targetY = -curvedRoll * MAX_Y_DEGREES
        targetEdgeTilt = normalizedRoll
    }

    fun setSensorStatus(message: String, full3d: Boolean) {
        sensorText.text = if (full3d) "●  $message" else "◐  $message"
    }

    fun setIsDefaultHome(isDefault: Boolean) {
        if (::setHomeButton.isInitialized) {
            setHomeButton.visibility = if (isDefault) View.GONE else View.VISIBLE
        }
    }

    fun startRendering() {
        if (rendering) return
        rendering = true
        previousFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopRendering() {
        rendering = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        val dt = if (previousFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        previousFrameNanos = frameTimeNanos

        // Time-based exponential smoothing feels the same on 60/90/120 Hz displays.
        val blend = 1f - exp(-dt / SMOOTHING_TIME_SECONDS)
        currentY += (targetY - currentY) * blend
        currentEdgeTilt += (targetEdgeTilt - currentEdgeTilt) * blend

        floatingLayer.rotationX = 0f
        floatingLayer.rotationY = currentY
        floatingLayer.rotation = 0f
        floatingLayer.translationX = currentY / MAX_Y_DEGREES * dp(10)
        floatingLayer.translationY = 0f
        atmosphere.translationX = -currentY / MAX_Y_DEGREES * dp(4)
        atmosphere.translationY = 0f
        edgeBlur.setTilt(currentEdgeTilt)

        updateTextIfNeeded()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun buildContent() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        floatingLayer.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = textView(13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            text = "PARALLAX HOME"
            letterSpacing = 0.18f
        }
        header.addView(brand, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(sensorText)
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column.addView(space(36))
        clockText.includeFontPadding = false
        column.addView(clockText)
        column.addView(dateText)
        column.addView(space(16))
        column.addView(anglesText)
        column.addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = roundedBackground(Color.argb(42, 255, 255, 255), 28f, Color.argb(70, 255, 255, 255))
            elevation = dp(14).toFloat()
        }
        card.addView(textView(24f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { text = "Floating home layer" })
        card.addView(space(8))
        card.addView(textView(14f, Color.argb(190, 255, 255, 255), Typeface.DEFAULT).apply {
            text = "Tilt the phone gently. The foreground applies the inverse rotation while the atmosphere stays behind it."
            gravity = Gravity.CENTER
        })
        column.addView(card, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column.addView(space(28))
        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            Triple("B", "Browser", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
            Triple("C", "Camera", Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            Triple("M", "Maps", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=nearby"))),
            Triple("♪", "Music", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
        ).forEachIndexed { index, item ->
            if (index > 0) iconRow.addView(space(12))
            iconRow.addView(
                fakeAppIcon(item.first, item.second) { safeLaunch(item.third) },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        column.addView(iconRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(Space(context), LinearLayout.LayoutParams(1, 0, 0.72f))
        column.addView(textView(12f, Color.argb(145, 255, 255, 255), Typeface.DEFAULT).apply {
            text = "Controls stay fixed below"
            gravity = Gravity.CENTER
        })
        column.addView(space(52))
    }

    private fun buildFixedControls() {
        fixedControls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val recenter = textView(14f, Color.rgb(7, 17, 31), Typeface.DEFAULT_BOLD).apply {
            text = "RECENTER"
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(dp(24), dp(13), dp(24), dp(13))
            background = roundedBackground(Color.rgb(125, 231, 255), 100f)
            isClickable = true
            isFocusable = true
            contentDescription = "Recenter parallax effect"
            setOnClickListener {
                onRecenterRequested()
                Toast.makeText(context, "Current angle is now neutral", Toast.LENGTH_SHORT).show()
            }
        }
        fixedControls.addView(recenter, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        fixedControls.addView(space(10))
        setHomeButton = textView(14f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            text = "SET AS HOME"
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = roundedBackground(Color.rgb(48, 80, 120), 100f, Color.argb(80, 255, 255, 255))
            isClickable = true
            isFocusable = true
            contentDescription = "Set Parallax Home as the default launcher"
            setOnClickListener { onSetAsHomeRequested() }
        }
        fixedControls.addView(setHomeButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.25f))

        addView(fixedControls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(28)
            rightMargin = dp(28)
            bottomMargin = dp(20)
        })
    }

    private fun fakeAppIcon(letter: String, label: String, launch: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Open $label"
            setOnClickListener { launch() }
            addView(textView(23f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                text = letter
                gravity = Gravity.CENTER
                background = roundedBackground(Color.argb(52, 255, 255, 255), 18f, Color.argb(55, 255, 255, 255))
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(space(7))
            addView(textView(11f, Color.argb(205, 255, 255, 255), Typeface.DEFAULT).apply {
                text = label
                gravity = Gravity.CENTER
            })
        }
    }

    private fun safeLaunch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No compatible app is installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTextIfNeeded() {
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second != lastClockSecond) {
            lastClockSecond = second
            val date = Date(now)
            clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            dateText.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(date)
        }

        val uptime = SystemClock.uptimeMillis()
        if (uptime - lastDebugUpdateMillis >= 120L) {
            lastDebugUpdateMillis = uptime
            anglesText.text = String.format(Locale.US, "SIDE %+05.1f degrees", currentY)
        }
    }

    private fun textView(sizeSp: Float, color: Int, face: Typeface): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = face
    }

    private fun space(dp: Int): Space = Space(context).apply {
        layoutParams = LinearLayout.LayoutParams(this@LauncherScene.dp(dp), this@LauncherScene.dp(dp))
    }

    private fun roundedBackground(fill: Int, radiusDp: Float, stroke: Int? = null) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}

/** Rear depth layer. It translates slightly but does not receive the 3D rotation. */
private class AtmosphereView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.rgb(6, 16, 30), Color.rgb(13, 42, 65), Color.rgb(9, 20, 37)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.shader = RadialGradient(
            width * 0.83f, height * 0.2f, width * 0.55f,
            Color.argb(100, 46, 195, 215), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(width * 0.83f, height * 0.2f, width * 0.55f, paint)

        paint.shader = RadialGradient(
            width * 0.05f, height * 0.82f, width * 0.62f,
            Color.argb(70, 82, 77, 196), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(width * 0.05f, height * 0.82f, width * 0.62f, paint)
        paint.shader = null
    }
}

/**
 * A cheap frosted-edge illusion. Only the edge toward the physical tilt is
 * drawn, and its feathered gradient becomes wider and brighter as tilt grows.
 * This avoids taking and blurring a full-screen bitmap on every sensor frame.
 */
private class TiltEdgeBlurView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var horizontalTilt = 0f

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setTilt(horizontal: Float) {
        if (abs(horizontal - horizontalTilt) < 0.004f) return
        horizontalTilt = horizontal
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawHorizontalEdge(canvas)
        paint.shader = null
    }

    private fun drawHorizontalEdge(canvas: Canvas) {
        val strength = abs(horizontalTilt)
        if (strength < 0.025f) return
        val edgeWidth = width * (0.10f + 0.12f * strength)
        val alpha = (105f * strength).toInt().coerceIn(0, 105)
        val haze = Color.argb(alpha, 174, 232, 255)

        if (horizontalTilt > 0f) {
            paint.shader = LinearGradient(
                width - edgeWidth, 0f, width.toFloat(), 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(alpha / 4, 142, 217, 255), haze),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(width - edgeWidth, 0f, width.toFloat(), height.toFloat(), paint)
        } else {
            paint.shader = LinearGradient(
                0f, 0f, edgeWidth, 0f,
                intArrayOf(haze, Color.argb(alpha / 4, 142, 217, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, edgeWidth, height.toFloat(), paint)
        }
    }

}
