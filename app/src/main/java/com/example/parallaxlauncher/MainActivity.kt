package com.example.parallaxlauncher

import android.app.Activity
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_HOME_ROLE = 1001
        private const val REQUEST_PICK_WIDGET = 2001
        private const val REQUEST_CONFIGURE_WIDGET = 2002
        private const val APP_WIDGET_HOST_ID = 7301
        private const val WIDGET_PREFS = "launcher_widgets"
        private const val WIDGET_IDS_KEY = "widget_ids"
    }

    private lateinit var scene: LauncherScene
    private lateinit var orientationTracker: OrientationTracker
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var lastWindowBlurRadius = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        scene = LauncherScene(this)
        orientationTracker = OrientationTracker(
            context = this,
            onOrientation = scene::setTargetOrientation,
            onStatusChanged = scene::setSensorStatus
        )
        scene.onRecenterRequested = orientationTracker::recenter
        scene.onSetAsHomeRequested = ::requestHomeRole
        scene.onWallpaperRequested = ::openWallpaperPicker
        scene.onAddWidgetRequested = ::pickWidget
        scene.onRemoveWidgetRequested = ::removeWidget
        scene.onBlurStrengthChanged = ::updateWindowBlur
        setContentView(scene)
        restoreWidgets()
    }

    override fun onStart() {
        super.onStart()
        try {
            appWidgetHost.startListening()
        } catch (_: Exception) {
            Toast.makeText(this, "Widgets are unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        scene.setIsDefaultHome(isDefaultHome())
        scene.startRendering()
        orientationTracker.start()
    }

    override fun onPause() {
        orientationTracker.stop()
        scene.stopRendering()
        super.onPause()
    }

    override fun onStop() {
        appWidgetHost.stopListening()
        super.onStop()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A Home activity has nowhere useful to go back to.
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun requestHomeRole() {
        if (isDefaultHome()) {
            scene.setIsDefaultHome(true)
            Toast.makeText(this, "Parallax Home is already your Home app", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_HOME_ROLE
                    )
                    return
                }
            }
            openHomeSettings()
        } catch (_: Exception) {
            openHomeSettings()
        }
    }

    private fun openHomeSettings() {
        val settingsIntents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (settingsIntent in settingsIntents) {
            if (settingsIntent.resolveActivity(packageManager) == null) continue
            try {
                Toast.makeText(
                    this,
                    "In Default apps -> Home app, choose Parallax Home",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(settingsIntent)
                return
            } catch (_: Exception) {
                // Try the next, more general Settings destination.
            }
        }
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "No wallpaper picker is available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickWidget() {
        pendingWidgetId = appWidgetHost.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            pendingWidgetId
        )
        try {
            startActivityForResult(intent, REQUEST_PICK_WIDGET)
        } catch (_: Exception) {
            discardPendingWidget()
            Toast.makeText(this, "No widget picker is available", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWidget(widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        if (info == null) {
            discardWidget(widgetId)
            return
        }

        val configureComponent = info.configure
        if (configureComponent == null) {
            finishAddingWidget(widgetId)
            return
        }

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configureComponent
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        pendingWidgetId = widgetId
        try {
            startActivityForResult(intent, REQUEST_CONFIGURE_WIDGET)
        } catch (_: Exception) {
            finishAddingWidget(widgetId)
        }
    }

    private fun finishAddingWidget(widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: run {
            discardWidget(widgetId)
            return
        }
        val ids = storedWidgetIds().toMutableList()
        if (widgetId !in ids) {
            ids += widgetId
            saveWidgetIds(ids)
        }
        val hostView = appWidgetHost.createView(this, widgetId, info)
        hostView.setAppWidget(widgetId, info)
        scene.addWidget(widgetId, hostView, info.loadLabel(packageManager) ?: "Widget")
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun restoreWidgets() {
        val validIds = mutableListOf<Int>()
        for (widgetId in storedWidgetIds()) {
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            if (info == null) {
                appWidgetHost.deleteAppWidgetId(widgetId)
                continue
            }
            val hostView = appWidgetHost.createView(this, widgetId, info)
            hostView.setAppWidget(widgetId, info)
            scene.addWidget(widgetId, hostView, info.loadLabel(packageManager) ?: "Widget")
            validIds += widgetId
        }
        saveWidgetIds(validIds)
    }

    private fun removeWidget(widgetId: Int) {
        scene.removeWidget(widgetId)
        appWidgetHost.deleteAppWidgetId(widgetId)
        saveWidgetIds(storedWidgetIds().filterNot { it == widgetId })
        Toast.makeText(this, "Widget removed", Toast.LENGTH_SHORT).show()
    }

    private fun discardPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            discardWidget(pendingWidgetId)
        }
    }

    private fun discardWidget(widgetId: Int) {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(widgetId)
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun updateWindowBlur(strength: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = (resources.displayMetrics.density * 42f * strength.coerceIn(0f, 1f)).toInt()
            if (radius == lastWindowBlurRadius) return
            lastWindowBlurRadius = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun storedWidgetIds(): List<Int> {
        val encoded = getSharedPreferences(WIDGET_PREFS, MODE_PRIVATE)
            .getString(WIDGET_IDS_KEY, "")
            .orEmpty()
        return encoded.split(',').mapNotNull { it.toIntOrNull() }
    }

    private fun saveWidgetIds(ids: List<Int>) {
        getSharedPreferences(WIDGET_PREFS, MODE_PRIVATE)
            .edit()
            .putString(WIDGET_IDS_KEY, ids.distinct().joinToString(","))
            .apply()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_HOME_ROLE -> {
                val isHome = isDefaultHome()
                scene.setIsDefaultHome(isHome)
                Toast.makeText(
                    this,
                    if (isHome) "Parallax Home is now your Home screen"
                    else "Home app was not changed. Select Parallax Home in Default apps.",
                    Toast.LENGTH_LONG
                ).show()
                if (!isHome) openHomeSettings()
            }

            REQUEST_PICK_WIDGET -> {
                val widgetId = resultWidgetId(data)
                if (resultCode == RESULT_OK && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    configureWidget(widgetId)
                } else {
                    discardWidget(widgetId)
                }
            }

            REQUEST_CONFIGURE_WIDGET -> {
                val widgetId = resultWidgetId(data)
                if (resultCode == RESULT_OK && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    finishAddingWidget(widgetId)
                } else {
                    discardWidget(widgetId)
                }
            }
        }
    }

    private fun resultWidgetId(data: Intent?): Int {
        val returnedId = data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        return if (returnedId != AppWidgetManager.INVALID_APPWIDGET_ID) returnedId else pendingWidgetId
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(7, 17, 31)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
