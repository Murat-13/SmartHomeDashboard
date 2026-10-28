package com.example.smarthomedashboard

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ChildButtonsEditorTest {
    private val host by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    // Те же id, что в item_child_button_editor.xml.
    private fun row(parent: ViewGroup): View = LinearLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
        addView(TextView(context).apply { id = R.id.tvChildOrder })
        addView(TextView(context).apply { id = R.id.tvChildEntityId })
        addView(EditText(context).apply { id = R.id.etChildName })
        addView(ImageView(context).apply { id = R.id.ivDragHandle })
    }

    private fun list(items: MutableList<ChildButtonData>): Pair<RecyclerView, ChildButtonAdapter> {
        val adapter = ChildButtonAdapter(items, { position, name -> items[position].name = name }, ::row)
        val recycler = RecyclerView(host).apply {
            layoutManager = LinearLayoutManager(host)
            this.adapter = adapter
        }
        host.setContentView(recycler)
        layout(recycler)
        return recycler to adapter
    }

    private fun layout(recycler: RecyclerView) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 600, 1000)
    }

    private fun field(recycler: RecyclerView, position: Int): EditText =
        recycler.findViewHolderForAdapterPosition(position)!!.itemView.findViewById(R.id.etChildName)

    @Test fun typedNameIsKeptWithoutLosingFocus() {
        val items = mutableListOf(ChildButtonData("light.a", "1"), ChildButtonData("light.b", "2"))
        val (recycler, _) = list(items)
        field(recycler, 1).apply { requestFocus(); setText("Кухня") }
        // Кнопка «Сохранить» не забирает фокус у поля: имя должно уже быть в данных.
        assertEquals("Кухня", items[1].name)
        assertEquals("1", items[0].name)
    }

    @Test fun nameTypedAfterDragGoesToMovedItem() {
        val items = mutableListOf(ChildButtonData("light.a", "A"), ChildButtonData("light.b", "B"),
            ChildButtonData("light.c", "C"))
        val (recycler, adapter) = list(items)
        // Так перестановку выполняет ItemTouchHelper.onMove: по соседним строкам, без перепривязки.
        for (from in 0..1) {
            Collections.swap(items, from, from + 1)
            adapter.notifyItemMoved(from, from + 1)
        }
        layout(recycler)
        assertEquals(listOf("light.b", "light.c", "light.a"), items.map { it.entityId })
        val moved = recycler.findViewHolderForAdapterPosition(2)!!
        assertEquals("light.a", moved.itemView.findViewById<TextView>(R.id.tvChildEntityId).text.toString())
        // Раньше holder помнил позицию 0 и записал бы имя в light.b.
        moved.itemView.findViewById<EditText>(R.id.etChildName).setText("Новое")
        assertEquals("Новое", items.single { it.entityId == "light.a" }.name)
        assertEquals("B", items.single { it.entityId == "light.b" }.name)
        assertEquals("C", items.single { it.entityId == "light.c" }.name)
    }

    @Test fun rebindingRecycledRowDoesNotOverwriteOtherItem() {
        val items = mutableListOf(ChildButtonData("light.a", "A"), ChildButtonData("light.b", "B"))
        val (recycler, adapter) = list(items)
        adapter.notifyItemRangeChanged(0, items.size)
        layout(recycler)
        assertEquals(listOf("A", "B"), items.map { it.name })
        field(recycler, 0).setText("A2")
        assertEquals(listOf("A2", "B"), items.map { it.name })
    }

    @Test fun saveUsesCurrentOrderAndNumbersEmptyNames() {
        val context = host
        val manager = TileManager(context)
        manager.saveTiles(listOf(TileEntity("g", "group", "bottom_panel", "Свет", 0, 0, 1, 1,
            config = """{"entity_ids":["light.a","light.b"],"child_names":["A","B"],"button_size":200}""")))
        val editor = Robolectric.buildActivity(ChildButtonsEditorActivity::class.java).get()
        fun set(name: String, value: Any?) = ChildButtonsEditorActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(editor, value)
        set("tileManager", manager)
        set("tileId", "g")
        @Suppress("UNCHECKED_CAST")
        val items = ChildButtonsEditorActivity::class.java.getDeclaredField("childItems")
            .apply { isAccessible = true }.get(editor) as MutableList<ChildButtonData>
        items += listOf(ChildButtonData("light.b", "Кухня"), ChildButtonData("light.a", "  "))
        ChildButtonsEditorActivity::class.java.getDeclaredMethod("saveChildButtons")
            .apply { isAccessible = true }.invoke(editor)
        val config = JSONObject(manager.loadTiles().single().config)
        assertEquals("""["light.b","light.a"]""", config.getJSONArray("entity_ids").toString())
        assertEquals("""["Кухня","2"]""", config.getJSONArray("child_names").toString())
        assertEquals(200, config.getInt("button_size"))
    }
}
