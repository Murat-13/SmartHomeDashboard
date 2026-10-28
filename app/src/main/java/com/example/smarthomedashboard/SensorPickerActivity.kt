package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SensorPickerActivity : PinProtectedActivity() {

    private lateinit var etSearch: EditText
    private lateinit var rvSensors: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var adapter: SensorListAdapter

    private val selectedSensors = mutableSetOf<String>()
    private var singleChoice = false
    private var allEntities = listOf<HaEntity>()

    private var webSocket: HomeAssistantWebSocket? = null
    private var haTokens: HaTokenAccess? = null
    private var tokenRequest = 0
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor_picker)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearch)
        rvSensors = findViewById(R.id.rvSensors)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        val preSelected = intent.getStringArrayListExtra("selected_sensors") ?: arrayListOf()
        selectedSensors.addAll(preSelected)
        singleChoice = intent.getBooleanExtra(EXTRA_SINGLE_CHOICE, false)
        if (singleChoice) {
            // Замена одного устройства: нажатие сразу возвращает выбор, «Сохранить» не нужна.
            // Заголовок задан в разметке Toolbar и перебивает заголовок Activity: меняем сам Toolbar.
            toolbar.title = "Выберите устройство"
            btnSave.visibility = View.GONE
        }

        setupRecyclerView()
        setupSearch()
        loadCachedOrFetch()

        btnSave.setOnClickListener { finishWith(ArrayList(selectedSensors)) }
    }

    private fun finishWith(selected: ArrayList<String>) {
        setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra("selected_sensors", selected))
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.sensor_picker_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            tvStatus.text = "Обновление..."
            tvStatus.visibility = View.VISIBLE
            fetchEntitiesFromHA()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterEntities(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterEntities(query: String) {
        val filtered = if (query.isEmpty()) {
            allEntities
        } else {
            allEntities.filter {
                it.name.contains(query, ignoreCase = true) || it.entityId.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filtered.map { it.copy(selected = selectedSensors.contains(it.entityId)) })
    }

    private fun setupRecyclerView() {
        rvSensors.layoutManager = LinearLayoutManager(this)
        adapter = SensorListAdapter { entity, isChecked ->
            if (singleChoice) {
                // Любое нажатие выбирает это устройство, в том числе повторное нажатие на текущее.
                finishWith(arrayListOf(entity.entityId))
                return@SensorListAdapter
            }
            if (isChecked) selectedSensors.add(entity.entityId) else selectedSensors.remove(entity.entityId)
        }
        rvSensors.adapter = adapter
    }

    private fun loadCachedOrFetch() {
        val cached = loadCachedEntities()
        if (cached.isNotEmpty()) {
            allEntities = cached
            filterEntities(etSearch.text.toString())
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = "Загрузка устройств..."
            tvStatus.visibility = View.VISIBLE
        }
        fetchEntitiesFromHA()
    }

    private fun loadCachedEntities(): List<HaEntity> {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val json = prefs.getString("cached_entities", "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<HaEntity>>() {}.type
            Gson().fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchEntitiesFromHA() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        fun hosts() = listOf(prefs.getString("ha_local_host", "").orEmpty(),
            prefs.getString("ha_remote_host", "").orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val endpoints = hosts()
        val request = ++tokenRequest
        webSocket?.disconnect()
        webSocket = null
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Чтение токена HA..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            fun active() = request == tokenRequest && !isFinishing && !isDestroyed &&
                HaTokenAccess.isCurrent(result) && hosts() == endpoints
            if (!active()) return@launch
            if (result.status != HaTokenAccess.Status.READY || result.value.isNullOrBlank() || endpoints.isEmpty()) {
                tvStatus.text = if (result.status != HaTokenAccess.Status.READY) result.message else "Укажите адрес Home Assistant"
                return@launch
            }
            webSocket = HomeAssistantWebSocket(
                host = endpoints.first(), alternateHosts = endpoints.drop(1), token = result.value,
                autoReconnect = false, onStateChanged = { _, _, _ -> },
                onConnected = { if (active()) { tvStatus.text = "Загрузка..."; webSocket?.getEntities() } },
                onDisconnected = {},
                onError = { if (active()) tvStatus.text = it },
                onStatus = { if (active()) tvStatus.text = it },
                onEntitiesList = { entities ->
                    if (active()) {
                        allEntities = entities
                        filterEntities(etSearch.text.toString())
                        tvStatus.visibility = View.GONE
                        cacheEntities(entities)
                        webSocket?.disconnect()
                        webSocket = null
                    }
                }
            )
            webSocket?.connect()
        }
    }

    private fun cacheEntities(entities: List<HaEntity>) {
        val json = Gson().toJson(entities)
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", json)
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    override fun onDestroy() {
        tokenRequest++
        super.onDestroy()
        webSocket?.disconnect()
    }

    companion object {
        /** Выбор ровно одного устройства: результат — список из одного entity_id. */
        const val EXTRA_SINGLE_CHOICE = "single_choice"
    }
}

class SensorListAdapter(
    private val onItemChecked: (HaEntity, Boolean) -> Unit
) : ListAdapter<HaEntity, SensorListAdapter.ViewHolder>(SensorDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entity = getItem(position)
        holder.tvName.text = entity.name
        holder.tvId.text = entity.entityId
        holder.cbSelected.setOnCheckedChangeListener(null)
        holder.cbSelected.isChecked = entity.selected
        holder.cbSelected.setOnCheckedChangeListener { _, isChecked -> onItemChecked(entity, isChecked) }
        holder.itemView.setOnClickListener { holder.cbSelected.isChecked = !holder.cbSelected.isChecked }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvSensorName)
        val tvId: TextView = view.findViewById(R.id.tvSensorId)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }
}

class SensorDiffCallback : DiffUtil.ItemCallback<HaEntity>() {
    override fun areItemsTheSame(oldItem: HaEntity, newItem: HaEntity): Boolean = oldItem.entityId == newItem.entityId
    override fun areContentsTheSame(oldItem: HaEntity, newItem: HaEntity): Boolean =
        oldItem.entityId == newItem.entityId && oldItem.selected == newItem.selected
}