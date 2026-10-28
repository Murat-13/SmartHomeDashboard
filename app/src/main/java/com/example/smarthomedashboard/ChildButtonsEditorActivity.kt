package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

/** Устройства группы: имя кнопки, порядок, замена и удаление устройства, добавление новых. */
class ChildButtonsEditorActivity : PinProtectedActivity() {

    private lateinit var tileManager: TileManager
    private lateinit var rvChildButtons: RecyclerView
    private lateinit var adapter: ChildButtonAdapter
    private lateinit var btnSave: Button

    private var tileId: String? = null
    private val childItems = mutableListOf<ChildButtonData>()
    private val group = GroupChildren(childItems)
    private var changed = false
    private var replacePosition = RecyclerView.NO_POSITION
    private val entityNames by lazy { loadEntityNames() }

    private val replaceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val position = replacePosition
        replacePosition = RecyclerView.NO_POSITION
        val chosen = result.data?.getStringArrayListExtra("selected_sensors")?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && chosen != null) replaceEntity(position, chosen)
    }

    private val addLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val chosen = result.data?.getStringArrayListExtra("selected_sensors")
        if (result.resultCode == Activity.RESULT_OK && chosen != null) addEntities(chosen)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_buttons_editor)

        tileManager = TileManager(this)
        tileId = intent.getStringExtra("tile_id")

        rvChildButtons = findViewById(R.id.rvChildButtons)
        btnSave = findViewById(R.id.btnSave)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            closeAskingAboutChanges()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeAskingAboutChanges()
        })

        loadChildButtons()

        adapter = ChildButtonAdapter(
            childItems,
            onNameChanged = { position, newName ->
                if (childItems[position].name != newName) changed = true
                childItems[position].name = newName
            },
            describe = ::describeEntity,
            onReplace = ::pickReplacement,
            onRemove = ::removeChild
        )
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
                group.move(fromPos, toPos)
                changed = true
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

        findViewById<Button>(R.id.btnAddChild).setOnClickListener {
            addLauncher.launch(Intent(this, SensorPickerActivity::class.java))
        }

        btnSave.setOnClickListener {
            saveChildButtons()
            setResult(Activity.RESULT_OK)
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
        updateEmptyState()
    }

    private fun closeAskingAboutChanges() {
        if (!changed) {
            finish()
            return
        }
        // Изменения применяются только по «Сохранить»: случайный «назад» не должен их терять молча.
        AlertDialog.Builder(this)
            .setTitle("Сохранить изменения?")
            .setMessage("Устройства или имена кнопок группы изменены.")
            .setPositiveButton("Сохранить") { _, _ -> btnSave.performClick() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun pickReplacement(position: Int) {
        replacePosition = position
        replaceLauncher.launch(Intent(this, SensorPickerActivity::class.java)
            .putExtra(SensorPickerActivity.EXTRA_SINGLE_CHOICE, true)
            .putStringArrayListExtra("selected_sensors", arrayListOf(childItems[position].entityId)))
    }

    private fun replaceEntity(position: Int, entityId: String) {
        when (group.replace(position, entityId)) {
            GroupChildren.Change.DONE -> {
                changed = true
                adapter.notifyItemChanged(position)
            }
            GroupChildren.Change.DUPLICATE ->
                Toast.makeText(this, "Это устройство уже есть в группе", Toast.LENGTH_SHORT).show()
            GroupChildren.Change.IGNORED -> Unit
        }
    }

    private fun addEntities(entityIds: List<String>) {
        val start = childItems.size
        // Имя новой кнопки — понятное имя устройства из HA, если оно известно.
        val added = group.add(entityIds) { entityNames[it].orEmpty() }
        if (added > 0) {
            changed = true
            adapter.notifyItemRangeInserted(start, added)
            rvChildButtons.scrollToPosition(childItems.size - 1)
        }
        if (added < entityIds.size) {
            Toast.makeText(this, "Устройства, которые уже есть в группе, пропущены", Toast.LENGTH_SHORT).show()
        }
        updateEmptyState()
    }

    private fun removeChild(position: Int) {
        val removed = childItems.getOrNull(position) ?: return
        group.remove(position)
        changed = true
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, childItems.size - position)
        updateEmptyState()
        val label = removed.name.ifBlank { removed.entityId }
        UserHints.undoNotice(rvChildButtons, "Кнопка «$label» удалена", btnSave) {
            // За время показа Snackbar список мог укоротиться; тот же объект не дублируем.
            if (childItems.any { it.entityId == removed.entityId }) return@undoNotice
            val insertAt = position.coerceAtMost(childItems.size)
            childItems.add(insertAt, removed)
            adapter.notifyItemInserted(insertAt)
            adapter.notifyItemRangeChanged(0, childItems.size)
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        findViewById<View>(R.id.tvGroupEmpty)?.visibility = if (childItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun describeEntity(entityId: String): String {
        val name = entityNames[entityId]
        return if (name.isNullOrBlank() || name == entityId) entityId else "$name\n$entityId"
    }

    /** Понятные имена из последнего списка устройств HA; без него показываем только entity_id. */
    private fun loadEntityNames(): Map<String, String> = try {
        val json = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getString("cached_entities", null)
        if (json.isNullOrEmpty()) emptyMap() else {
            val type = object : TypeToken<List<HaEntity>>() {}.type
            Gson().fromJson<List<HaEntity>>(json, type)
                .filter { it.name.isNotBlank() && it.name != "null" }
                .associate { it.entityId to it.name }
        }
    } catch (_: Exception) { emptyMap() }

    private fun loadChildButtons() {
        tileId?.let { id ->
            val tile = tileManager.loadTiles().find { it.id == id } ?: return
            try {
                childItems.addAll(GroupChildren.fromConfig(JSONObject(tile.config)).items)
            } catch (_: Exception) {}
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
                    group.writeTo(config)
                    tiles[index] = oldTile.copy(config = config.toString())
                    tileManager.saveTiles(tiles)
                    changed = false
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
    },
    private val describe: (String) -> String = { it },
    private val onReplace: (Int) -> Unit = {},
    private val onRemove: (Int) -> Unit = {}
) : RecyclerView.Adapter<ChildButtonAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(createItemView(parent))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvOrder.text = "${position + 1}"
        holder.tvEntityId.text = describe(item.entityId)
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

        // Как и имя, действие берёт текущую позицию строки, а не позицию на момент привязки.
        holder.replace.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onReplace)
        }
        holder.remove.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onRemove)
        }

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
        val replace: View = view.findViewById(R.id.btnReplaceChild)
        val remove: View = view.findViewById(R.id.btnRemoveChild)
        var nameWatcher: android.text.TextWatcher? = null
    }
}
