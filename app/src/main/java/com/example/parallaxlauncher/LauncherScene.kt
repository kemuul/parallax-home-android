package com.example.parallaxlauncher

import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class LauncherScene(context: Context) : FrameLayout(context), Choreographer.FrameCallback {
    companion object {
        private const val RECENTER_THRESHOLD_DEGREES = 15f
        private const val BLUR_START_DEGREES = 10f
        private const val COMPENSATION_STRENGTH = 0.90f
        private const val MAX_UI_ROTATION_DEGREES = 42f
        private const val SMOOTHING_TIME_SECONDS = 0.15f
        private const val APP_PREFS = "launcher_apps"
        private const val APP_COMPONENTS_KEY = "selected_components"
        private const val PARALLAX_ENABLED_KEY = "parallax_enabled"
    }

    var onRecenterRequested: () -> Unit = {}
    var onSetAsHomeRequested: () -> Unit = {}
    var onWallpaperRequested: () -> Unit = {}
    var onAddWidgetRequested: () -> Unit = {}
    var onRemoveWidgetRequested: (Int) -> Unit = {}
    var onBlurStrengthChanged: (Float) -> Unit = {}
    var onParallaxEnabledChanged: (Boolean) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val atmosphere = AtmosphereView(context)
    private val floatingLayer = FrameLayout(context)
    private val widgetStrip = LinearLayout(context)
    private val widgetScroller = HorizontalScrollView(context)
    private val appStrip = LinearLayout(context)
    private val appScroller = HorizontalScrollView(context)
    private val fixedControls = LinearLayout(context)
    private val blurFallback = WholeScreenBlurFallback(context)
    private val widgetViews = mutableMapOf<Int, View>()
    private var blurEffect: WholeScreenBlurEffectController? = null
    private lateinit var setHomeButton: TextView
    private lateinit var effectButton: TextView
    private var parallaxEnabled = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PARALLAX_ENABLED_KEY, true)

    private val clockText = textView(70f, Color.WHITE, Typeface.DEFAULT_BOLD)
    private val dateText = textView(16f, Color.argb(220, 255, 255, 255), Typeface.DEFAULT)
    private val sensorText = textView(12f, Color.rgb(125, 231, 255), Typeface.DEFAULT_BOLD)
    private val anglesText = textView(12f, Color.argb(190, 255, 255, 255), Typeface.MONOSPACE)

    private var targetY = 0f
    private var currentY = 0f
    private var targetBlurStrength = 0f
    private var currentBlurStrength = 0f
    private var rendering = false
    private var previousFrameNanos = 0L
    private var lastClockSecond = -1L
    private var lastDebugUpdateMillis = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
        atmosphere.scaleX = 1.04f
        atmosphere.scaleY = 1.04f
        addView(atmosphere, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        floatingLayer.cameraDistance = 8_000f * density
        floatingLayer.scaleX = 1.035f
        floatingLayer.scaleY = 1.035f
        addView(floatingLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildContent()
        buildFixedControls()

        addView(blurFallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurFallback.visibility = View.GONE
            blurEffect = WholeScreenBlurEffectController(this, density)
        }

        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            floatingLayer.setPadding(dp(24), top + dp(20), dp(24), bottom + dp(18))
            (fixedControls.layoutParams as LayoutParams).bottomMargin = bottom + dp(12)
            fixedControls.requestLayout()
            insets
        }
    }

    fun setTargetOrientation(
        @Suppress("UNUSED_PARAMETER") pitch: Float,
        roll: Float,
        @Suppress("UNUSED_PARAMETER") yaw: Float
    ) {
        if (!parallaxEnabled) {
            targetY = 0f
            targetBlurStrength = 0f
            return
        }
        targetY = (-roll * COMPENSATION_STRENGTH).coerceIn(
            -MAX_UI_ROTATION_DEGREES,
            MAX_UI_ROTATION_DEGREES
        )
        targetBlurStrength = (
            (abs(roll) - BLUR_START_DEGREES) /
                (RECENTER_THRESHOLD_DEGREES - BLUR_START_DEGREES)
            ).coerceIn(0f, 1f)
    }

    fun setSensorStatus(message: String, full3d: Boolean) {
        if (!parallaxEnabled) {
            sensorText.text = "Parallax disabled"
            return
        }
        sensorText.text = if (full3d) "●  $message" else "◐  $message"
    }

    fun setIsDefaultHome(isDefault: Boolean) {
        if (::setHomeButton.isInitialized) {
            setHomeButton.visibility = View.VISIBLE
            setHomeButton.text = if (isDefault) "CHANGE HOME APP" else "SET AS HOME"
        }
    }

    fun addWidget(widgetId: Int, hostView: AppWidgetHostView, label: String) {
        if (widgetViews.containsKey(widgetId)) return
        hostView.contentDescription = "$label widget. Long press to remove."
        hostView.setOnLongClickListener {
            confirmRemoveWidget(widgetId, label)
            true
        }

        val card = FrameLayout(context).apply {
            tag = widgetId
            background = roundedBackground(Color.argb(40, 0, 0, 0), 24f, Color.argb(65, 255, 255, 255))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnLongClickListener {
                confirmRemoveWidget(widgetId, label)
                true
            }
        }
        widgetViews[widgetId] = card
        widgetStrip.addView(card, LinearLayout.LayoutParams(dp(290), dp(150)).apply {
            rightMargin = dp(12)
        })
        widgetScroller.visibility = View.VISIBLE
    }

    fun removeWidget(widgetId: Int) {
        val view = widgetViews.remove(widgetId) ?: return
        widgetStrip.removeView(view)
        if (widgetViews.isEmpty()) widgetScroller.visibility = View.GONE
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
        onBlurStrengthChanged(0f)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        val dt = if (previousFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        previousFrameNanos = frameTimeNanos

        val blend = 1f - exp(-dt / SMOOTHING_TIME_SECONDS)
        currentY += (targetY - currentY) * blend
        currentBlurStrength += (targetBlurStrength - currentBlurStrength) * blend

        floatingLayer.rotationX = 0f
        floatingLayer.rotationY = currentY
        floatingLayer.rotation = 0f
        floatingLayer.translationX = currentY / MAX_UI_ROTATION_DEGREES * dp(18)
        floatingLayer.translationY = 0f
        atmosphere.translationX = -currentY / MAX_UI_ROTATION_DEGREES * dp(7)
        atmosphere.translationY = 0f

        blurEffect?.update(currentBlurStrength) ?: blurFallback.setStrength(currentBlurStrength)
        onBlurStrengthChanged(currentBlurStrength)
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
        header.addView(textView(13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            text = "PARALLAX HOME"
            letterSpacing = 0.18f
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(sensorText)
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column.addView(space(24))
        clockText.includeFontPadding = false
        column.addView(clockText)
        column.addView(dateText)
        column.addView(space(10))
        column.addView(anglesText)

        widgetStrip.orientation = LinearLayout.HORIZONTAL
        widgetStrip.gravity = Gravity.CENTER_VERTICAL
        widgetScroller.apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(widgetStrip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        column.addView(widgetScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(160)).apply {
            topMargin = dp(14)
        })

        column.addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(textView(12f, Color.argb(200, 255, 255, 255), Typeface.DEFAULT_BOLD).apply {
            text = "MY APPS  •  long-press an icon to remove it"
            letterSpacing = 0.08f
        })
        column.addView(space(10))

        appStrip.orientation = LinearLayout.HORIZONTAL
        appStrip.gravity = Gravity.CENTER_VERTICAL
        appScroller.apply {
            isHorizontalScrollBarEnabled = false
            addView(appStrip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        column.addView(appScroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(90)))
        column.addView(space(174))
        rebuildSelectedApps()
    }

    private fun buildFixedControls() {
        fixedControls.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(Color.argb(185, 7, 17, 31), 24f, Color.argb(60, 255, 255, 255))
        }

        val primaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        primaryRow.addView(controlButton("RECENTER", Color.rgb(125, 231, 255), Color.rgb(7, 17, 31)) {
            onRecenterRequested()
            Toast.makeText(context, "Current angle is now neutral", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        primaryRow.addView(space(8))
        setHomeButton = controlButton("SET AS HOME", Color.rgb(48, 80, 120), Color.WHITE) {
            onSetAsHomeRequested()
        }
        primaryRow.addView(setHomeButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f))
        fixedControls.addView(primaryRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        fixedControls.addView(space(8))
        val customizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        customizeRow.addView(controlButton("WALLPAPER", Color.rgb(44, 62, 86), Color.WHITE) {
            onWallpaperRequested()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        customizeRow.addView(space(6))
        customizeRow.addView(controlButton("ADD APP", Color.rgb(44, 62, 86), Color.WHITE) {
            showAppPicker()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        customizeRow.addView(space(6))
        customizeRow.addView(controlButton("ADD WIDGET", Color.rgb(44, 62, 86), Color.WHITE) {
            onAddWidgetRequested()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        fixedControls.addView(customizeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        fixedControls.addView(space(8))
        effectButton = controlButton("", Color.rgb(23, 94, 103), Color.WHITE) {
            toggleParallax()
        }
        updateEffectButton()
        fixedControls.addView(effectButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(fixedControls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(18)
            rightMargin = dp(18)
            bottomMargin = dp(12)
        })
    }

    private fun controlButton(label: String, fill: Int, textColor: Int, action: () -> Unit): TextView =
        textView(12f, textColor, Typeface.DEFAULT_BOLD).apply {
            text = label
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setPadding(dp(9), dp(11), dp(9), dp(11))
            background = roundedBackground(fill, 100f, Color.argb(65, 255, 255, 255))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun toggleParallax() {
        parallaxEnabled = !parallaxEnabled
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PARALLAX_ENABLED_KEY, parallaxEnabled)
            .apply()
        updateEffectButton()

        if (!parallaxEnabled) {
            clearParallaxImmediately()
            sensorText.text = "Parallax disabled"
            Toast.makeText(context, "Parallax effect disabled", Toast.LENGTH_SHORT).show()
        } else {
            sensorText.text = "Recentered"
            Toast.makeText(context, "Parallax effect enabled", Toast.LENGTH_SHORT).show()
        }
        onParallaxEnabledChanged(parallaxEnabled)
    }

    private fun updateEffectButton() {
        if (!::effectButton.isInitialized) return
        effectButton.text = if (parallaxEnabled) "PARALLAX EFFECT: ON" else "PARALLAX EFFECT: OFF"
        effectButton.background = roundedBackground(
            if (parallaxEnabled) Color.rgb(23, 94, 103) else Color.rgb(65, 72, 84),
            100f,
            Color.argb(65, 255, 255, 255)
        )
    }

    private fun clearParallaxImmediately() {
        targetY = 0f
        currentY = 0f
        targetBlurStrength = 0f
        currentBlurStrength = 0f
        floatingLayer.rotationX = 0f
        floatingLayer.rotationY = 0f
        floatingLayer.rotation = 0f
        floatingLayer.translationX = 0f
        floatingLayer.translationY = 0f
        atmosphere.translationX = 0f
        atmosphere.translationY = 0f
        blurEffect?.update(0f) ?: blurFallback.setStrength(0f)
        onBlurStrengthChanged(0f)
    }

    private fun showAppPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase(Locale.getDefault()) }

        if (apps.isEmpty()) {
            Toast.makeText(context, "No launchable apps were found", Toast.LENGTH_SHORT).show()
            return
        }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(context).apply { addView(list) }
        lateinit var dialog: AlertDialog
        for (app in apps) {
            val component = ComponentName(app.activityInfo.packageName, app.activityInfo.name)
            val row = textView(16f, Color.rgb(20, 30, 42), Typeface.DEFAULT).apply {
                text = app.loadLabel(context.packageManager)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val icon = app.loadIcon(context.packageManager)
                icon.setBounds(0, 0, dp(38), dp(38))
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = dp(12)
                setOnClickListener {
                    addSelectedApp(component)
                    dialog.dismiss()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(context)
            .setTitle("Add an app")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun addSelectedApp(component: ComponentName) {
        val components = selectedComponents().toMutableList()
        val flattened = component.flattenToString()
        if (flattened !in components) {
            components += flattened
            saveSelectedComponents(components)
            rebuildSelectedApps()
        }
    }

    private fun removeSelectedApp(flattened: String) {
        saveSelectedComponents(selectedComponents().filterNot { it == flattened })
        rebuildSelectedApps()
        Toast.makeText(context, "App shortcut removed", Toast.LENGTH_SHORT).show()
    }

    private fun rebuildSelectedApps() {
        appStrip.removeAllViews()
        val validComponents = mutableListOf<String>()
        for (flattened in selectedComponents()) {
            val component = ComponentName.unflattenFromString(flattened) ?: continue
            val info = try {
                context.packageManager.getActivityInfo(component, 0)
            } catch (_: Exception) {
                continue
            }
            validComponents += flattened
            val label = info.loadLabel(context.packageManager).toString()
            val icon = info.loadIcon(context.packageManager)
            appStrip.addView(realAppIcon(flattened, component, label, icon), LinearLayout.LayoutParams(dp(78), LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        if (validComponents != selectedComponents()) saveSelectedComponents(validComponents)

        if (validComponents.isEmpty()) {
            appStrip.addView(textView(14f, Color.argb(210, 255, 255, 255), Typeface.DEFAULT).apply {
                text = "Tap ADD APP to place shortcuts here"
                setPadding(dp(12), dp(20), dp(12), dp(20))
            })
        }
    }

    private fun realAppIcon(
        flattened: String,
        component: ComponentName,
        label: String,
        icon: android.graphics.drawable.Drawable
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Open $label. Long press to remove."
        setOnClickListener {
            safeLaunch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component))
        }
        setOnLongClickListener {
            AlertDialog.Builder(context)
                .setTitle("Remove $label?")
                .setMessage("This removes only the Home shortcut, not the installed app.")
                .setPositiveButton("Remove") { _, _ -> removeSelectedApp(flattened) }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        addView(ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(textView(11f, Color.WHITE, Typeface.DEFAULT).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun selectedComponents(): List<String> {
        val encoded = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getString(APP_COMPONENTS_KEY, "")
            .orEmpty()
        return encoded.split('\n').filter { it.isNotBlank() }
    }

    private fun saveSelectedComponents(components: List<String>) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(APP_COMPONENTS_KEY, components.distinct().joinToString("\n"))
            .apply()
    }

    private fun confirmRemoveWidget(widgetId: Int, label: String) {
        AlertDialog.Builder(context)
            .setTitle("Remove $label?")
            .setMessage("The widget will be removed from this Home screen.")
            .setPositiveButton("Remove") { _, _ -> onRemoveWidgetRequested(widgetId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun safeLaunch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "The selected app could not be opened", Toast.LENGTH_SHORT).show()
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
            anglesText.text = String.format(Locale.US, "SIDE %+05.1f degrees  •  auto-center at ±15", currentY)
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

/** Transparent depth shading drawn over Android's selected system wallpaper. */
private class AtmosphereView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.argb(145, 6, 16, 30), Color.argb(105, 13, 42, 65), Color.argb(145, 9, 20, 37)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.shader = RadialGradient(
            width * 0.83f, height * 0.2f, width * 0.55f,
            Color.argb(85, 46, 195, 215), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(width * 0.83f, height * 0.2f, width * 0.55f, paint)
        paint.shader = null
    }
}

/** True full-content blur on Android 12 and newer. */
private class WholeScreenBlurEffectController(
    private val target: View,
    private val density: Float
) {
    private var lastBucket = -1

    fun update(strength: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val bucket = (strength.coerceIn(0f, 1f) * 20f).roundToInt()
        if (bucket == lastBucket) return
        lastBucket = bucket
        if (bucket == 0) {
            target.setRenderEffect(null)
            return
        }
        val steppedStrength = bucket / 20f
        // A restrained maximum keeps launcher labels and widgets readable.
        val radius = density * (1.5f + 5.5f * steppedStrength)
        target.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }
}

/** Whole-screen frosted fallback for Android 8-11, where RenderEffect is unavailable. */
private class WholeScreenBlurFallback(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var strength = 0f

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setStrength(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (abs(next - strength) < 0.01f) return
        strength = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (strength < 0.02f) return
        paint.color = Color.argb((32f * strength).toInt(), 205, 225, 238)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
