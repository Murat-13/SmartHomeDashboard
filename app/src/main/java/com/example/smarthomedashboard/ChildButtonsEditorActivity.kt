package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

class ChildButtonsEditorActivity : PinProtectedActivity() {

    private lateinit var tileManager: TileManager
    private lateinit var rvChildButtons: RecyclerView
    private lateinit var adapter: ChildButtonAdapter
    private lateinit var btnSave: Button

    private var tileId: String? = null
    private val childItems = mutableListOf<ChildButtonData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_buttons_editor)

        tileManager = TileManager(this)
        tileId = intent.getStringExtra("tile_id")

        rvChildButtons = findViewById(R.id.rvChildButtons)
        btnSave = findViewById(R.id.btnSave)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        loadChildButtons()

        adapter = ChildButtonAdapter(childItems, onNameChanged = { position, newName ->
            childItems[position].name = newName
        })
        rvChildButtons.layoutManager = LinearLayoutManager(this)
        rvChildButtons.adapter = adapter

        // Drag-and-drop для изменения порядка
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                Collections.swap(childItems, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // После перетаскивания номера строк должны соответствовать новому порядку.
                recyclerView.post { adapter.notifyItemRangeChanged(0, childItems.size) }
            }
        })
        itemTouchHelper.attachToRecyclerView(rvChildButtons)

        btnSave.setOnClickListener {
            saveChildButtons()
            setResult(Activity.RESULT_OK)
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadChildButtons() {
        tileId?.let { id ->
            val tile = tileManager.loadTiles().find { it.id == id }
            if (tile != null) {
                try {
                    val config = JSONObject(tile.config)
                    val entityIds = config.optJSONArray("entity_ids")
                    val names = config.optJSONArray("child_names")

                    if (entityIds != null) {
                        for (i in 0 until entityIds.length()) {
                            val entityId = entityIds.getString(i)
                            val name = if (names != null && i < names.length()) {
                                names.getString(i)
                            } else {
                                (i + 1).toString()
                            }
                            childItems.add(ChildButtonData(entityId, name))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveChildButtons() {
        tileId?.let { id ->
            val tiles = tileManager.loadTiles().toMutableList()
            val index = tiles.indexOfFirst { it.id == id }
            if (index >= 0) {
                val oldTile = tiles[index]
                try {
                    val config = JSONObject(oldTile.config)
                    val entityIds = JSONArray()
                    val names = JSONArray()

                    childItems.forEachIndexed { index, item ->
                        entityIds.put(item.entityId)
                        // Пустое имя заменяем номером итоговой позиции, как и при создании группы.
                        names.put(item.name.trim().ifEmpty { (index + 1).toString() })
                    }

                    config.put("entity_ids", entityIds)
                    config.put("child_names", names)

                    tiles[index] = oldTile.copy(config = config.toString())
                    tileManager.saveTiles(tiles)
                } catch (_: Exception) {}
            }
        }
    }
}

data class ChildButtonData(
    val entityId: String,
    var name: String
)

class ChildButtonAdapter(
    private val items: MutableList<ChildButtonData>,
    private val onNameChanged: (Int, String) -> Unit,
    // В JVM-тестах нет ресурсов приложения: строку можно собрать программно с теми же id.
    private val createItemView: (ViewGroup) -> View = { parent ->
        LayoutInflater.from(parent.context).inflate(R.layout.item_child_button_editor, parent, false)
    }
) : RecyclerView.Adapter<ChildButtonAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(createItemView(parent))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvOrder.text = "${position + 1}"
        holder.tvEntityId.text = item.entityId
        // Программная запись текста не должна писать имя в другую строку переиспользованного holder.
        holder.nameWatcher?.let { holder.etName.removeTextChangedListener(it) }
        holder.etName.setText(item.name)
        // Имя сохраняем при каждом изменении: «Сохранить» не забирает фокус у поля.
        // Позицию берём в момент ввода: после перетаскивания строка не перерисовывается.
        holder.nameWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val current = holder.bindingAdapterPosition
                if (current != RecyclerView.NO_POSITION && current < items.size) {
                    onNameChanged(current, s?.toString().orEmpty())
                }
            }
        }.also { holder.etName.addTextChangedListener(it) }

        holder.ivDrag.setOnTouchListener { _, _ ->
            // Drag handle — ItemTouchHelper обрабатывает
            false
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvOrder: TextView = view.findViewById(R.id.tvChildOrder)
        val tvEntityId: TextView = view.findViewById(R.id.tvChildEntityId)
        val etName: EditText = view.findViewById(R.id.etChildName)
        val ivDrag: ImageView = view.findViewById(R.id.ivDragHandle)
        var nameWatcher: android.text.TextWatcher? = null
    }
}