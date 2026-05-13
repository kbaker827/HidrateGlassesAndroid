package com.hidrateglasses.rokid

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import com.hidrateglasses.data.models.HydrationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RokidPresentationMgr"

@Singleton
class RokidPresentationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var scope: CoroutineScope? = null

    fun start(dataFlow: StateFlow<HydrationData>) {
        stop()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            dataFlow.collectLatest { data ->
                pushToSecondaryDisplay(data)
            }
        }
        Log.d(TAG, "started")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        Log.d(TAG, "stopped")
    }

    private fun pushToSecondaryDisplay(data: HydrationData) {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isEmpty()) return
        Log.d(TAG, "push hydration to presentation display: ${data.todayOz}/${data.goalOz} oz")
    }
}
