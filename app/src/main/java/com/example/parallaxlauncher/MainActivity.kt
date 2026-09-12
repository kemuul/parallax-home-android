package com.example.parallaxlauncher

import android.app.Activity
import android.app.role.RoleManager
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
    }

    private lateinit var scene: LauncherScene
    private lateinit var orientationTracker: OrientationTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        scene = LauncherScene(this)
        orientationTracker = OrientationTracker(
            context = this,
            onOrientation = scene::setTargetOrientation,
            onStatusChanged = scene::setSensorStatus
        )
        scene.onRecenterRequested = orientationTracker::recenter
        scene.onSetAsHomeRequested = ::requestHomeRole
        setContentView(scene)
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
                    Toast.makeText(
                        this,
                        "Choose Parallax Home in the Android dialog",
                        Toast.LENGTH_SHORT
                    ).show()
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

        Toast.makeText(
            this,
            "Open Settings -> Apps -> Default apps -> Home app",
            Toast.LENGTH_LONG
        ).show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HOME_ROLE) return

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
