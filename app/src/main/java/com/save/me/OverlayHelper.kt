package com.save.me

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

object OverlayHelper {
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun requestOverlayPermission(context: Context) {
        if (!hasOverlayPermission(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun isDarkTheme(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun getOutlineColor(context: Context): Int {
        return if (isDarkTheme(context)) Color.BLACK else Color.WHITE
    }

    private fun createOutlineDrawable(context: Context, strokeWidth: Int = 8): GradientDrawable {
        val outlineColor = getOutlineColor(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(strokeWidth, outlineColor)
            setColor(Color.TRANSPARENT)
            cornerRadius = 32f
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics
        ).toInt()
    }

    fun showSurfaceOverlay(
        context: Context,
        callback: (SurfaceHolder?, View?) -> Unit,
        // Optional: you can add parameters for size/visibility if you want to support both visible and hidden
        overlaySizeDp: Int = 64, // default to 64dp x 64dp preview
        offScreen: Boolean = true // default to place overlay offscreen
    ) {
        if (!hasOverlayPermission(context)) {
            Log.e("OverlayHelper", "Overlay permission not granted")
            callback(null, null)
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val sizePx = dpToPx(context, overlaySizeDp)
                val params = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START

                if (offScreen) {
                    // Place overlay completely off the top-left of the screen
                    params.x = -sizePx
                    params.y = -sizePx
                } else {
                    // Place overlay in the bottom-end corner (visible, not offscreen)
                    params.gravity = Gravity.BOTTOM or Gravity.END
                    params.x = dpToPx(context, 16) // margin from edge if wanted
                    params.y = dpToPx(context, 16)
                }

                val surfaceView = object : SurfaceView(context) {}
                surfaceView.setBackgroundColor(Color.TRANSPARENT)

                val frameLayout = FrameLayout(context)
                frameLayout.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                frameLayout.background = null // No outline if offscreen

                frameLayout.addView(surfaceView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // Only show text overlay if not offscreen and you want to debug
                if (!offScreen) {
                    val textView = TextView(context)
                    textView.text = "Overlay Active"
                    textView.setTextColor(getOutlineColor(context))
                    textView.setBackgroundColor(Color.TRANSPARENT)
                    textView.textSize = 14f
                    textView.gravity = Gravity.CENTER
                    val textParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    frameLayout.addView(textView, textParams)
                }

                wm.addView(frameLayout, params)
                Log.i("OverlayHelper", "Overlay added to window manager")
                callback(surfaceView.holder, frameLayout)
            } catch (e: Exception) {
                Log.e("OverlayHelper", "Failed to add overlay: ${e.message}", e)
                callback(null, null)
            }
        }
    }

    fun showViewOverlay(
        context: Context,
        callback: (View?) -> Unit,
        overlaySizeDp: Int = 64,
        offScreen: Boolean = true
    ) {
        if (!hasOverlayPermission(context)) {
            Log.e("OverlayHelper", "Overlay permission not granted")
            callback(null)
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val sizePx = dpToPx(context, overlaySizeDp)
                val params = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START

                if (offScreen) {
                    params.x = -sizePx
                    params.y = -sizePx
                } else {
                    params.gravity = Gravity.BOTTOM or Gravity.END
                    params.x = dpToPx(context, 16)
                    params.y = dpToPx(context, 16)
                }

                val frameLayout = FrameLayout(context)
                frameLayout.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                frameLayout.background = null

                if (!offScreen) {
                    val textView = TextView(context)
                    textView.text = "Audio Overlay Active"
                    textView.setTextColor(getOutlineColor(context))
                    textView.textSize = 14f
                    textView.gravity = Gravity.CENTER
                    textView.setBackgroundColor(Color.TRANSPARENT)
                    frameLayout.addView(textView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                }

                wm.addView(frameLayout, params)
                Log.i("OverlayHelper", "Audio overlay added to window manager")
                callback(frameLayout)
            } catch (e: Exception) {
                Log.e("OverlayHelper", "Failed to add audio overlay: ${e.message}", e)
                callback(null)
            }
        }
    }

    fun removeOverlay(context: Context, view: View?) {
        if (view == null) return
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
                Log.i("OverlayHelper", "Overlay removed")
            } catch (e: Exception) {
                Log.e("OverlayHelper", "Failed to remove overlay: ${e.message}", e)
            }
        }
    }
}