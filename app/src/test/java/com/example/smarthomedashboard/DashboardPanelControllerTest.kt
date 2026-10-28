package com.example.smarthomedashboard

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardPanelControllerTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private val owner = MainViewModel()
    private val panel = FrameLayout(activity)
    private val tiles = TileManager(activity)
    private var settings = 0
    private var additions = 0
    private val controller = DashboardPanelController(activity, owner, object : DashboardPanelController.Host {
        override val panel get() = this@DashboardPanelControllerTest.panel
        override val dim = View(activity)
        override val hasPanel = true
        override val hasDim = true
        override val tiles get() = this@DashboardPanelControllerTest.tiles
        override val weather = YandexWeatherManager(activity)
        override val temperatureUnit = "C"
        override val motionEntity: String? = null
        override fun readPreferences() = Unit
        override fun updateHint() = Unit
        override fun wake() = Unit
        override fun editTile(id: String) = Unit
        override fun requestEdit() = Unit
        override fun exitEdit() = Unit
        override fun settings() { settings++ }
        override fun addTile() { additions++ }
    })

    @Test fun navigationIsDelegatedAndKeepsOriginalActivityContext() {
        val button = controller.createSettingsButton()
        assertSame(activity, button.context)
        button.performClick()
        controller.createAddButton().performClick()
        assertEquals(1, settings)
        assertEquals(1, additions)
        controller.clearPanelInteractions()
    }

    @Test fun rebuildingDetachesOldListenersAndReadsCurrentOwnerState() {
        tiles.saveTiles(listOf(TileEntity("button", "button", "bottom_panel", "Test", 10, 20, 1, 1,
            config = """{"entity_id":"light.test"}""")))
        owner.onEntityStateChanged("light.test", "off")
        controller.refreshBottomPanel()
        val old = panel.findViewWithTag<Button>("button")
        assertTrue(old.hasOnClickListeners())
        owner.onEntityStateChanged("light.test", "on")
        controller.refreshBottomPanel()
        val current = panel.findViewWithTag<Button>("button")
        assertNotSame(old, current)
        assertFalse(old.hasOnClickListeners())
        assertEquals("on", owner.devices.singleStates["light.test"])
        assertEquals(1, panel.childCount)
        controller.clearPanelInteractions()
        assertFalse(current.hasOnClickListeners())
    }
}
