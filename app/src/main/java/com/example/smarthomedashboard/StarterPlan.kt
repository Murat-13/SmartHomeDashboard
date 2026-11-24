package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Первые плитки по комнатам (docs/setup-tz.md, часть 2): что предложить из дома Home Assistant и как разложить по
 * страницам. Чистая логика: ничего не читает, не пишет и не переключает. Раскладка — горизонтальная (вертикальная
 * строится из неё, как у всей панели), без наложений, под размер экрана.
 */
internal object StarterPlan {
    enum class Kind { LIGHT, SWITCH, FAN, GATE, CAMERA }

    /** [recommended] — отмечено сразу; иначе показано без галочки. */
    class Item(val entity: String, val name: String, val kind: Kind, val recommended: Boolean)

    /** Комната; [id] "" — «Без комнаты». [temperature]/[humidity] — датчики для «Климата» ("" — нет). */
    class Room(val id: String, val name: String, val items: List<Item>, val temperature: String = "", val humidity: String = "")

    /** [skipped] — сколько устройств сейчас недоступны и поэтому не предложены. */
    class Proposal(val rooms: List<Room>, val weather: String, val skipped: Int) {
        val items get() = rooms.flatMap { it.items }
        val isEmpty get() = items.isEmpty() && weather.isEmpty() && rooms.none { it.temperature.isNotEmpty() }
    }

    /**
     * Камера или домофон, настроенные в мастере напрямую ([CameraSetupActivity]): [id] — номер будущей плитки (под ним уже
     * сохранён пароль камеры), [haDevice] — то же устройство в Home Assistant (его камера второй раз не предлагается).
     */
    class Direct(val id: String, val doorbell: Boolean, val title: String, val host: String, val config: DoorbellConfig, val haDevice: String = "") {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("doorbell", doorbell).put("title", title).put("host", host)
            .put("config", config.toJson()).put("device", haDevice)
        companion object {
            fun fromJson(j: JSONObject): Direct? = j.optString("id").ifEmpty { null }?.let {
                Direct(it, j.optBoolean("doorbell"), j.optString("title"), j.optString("host"),
                    DoorbellConfig.fromJson(j.optJSONObject("config") ?: JSONObject()), j.optString("device")) }
        }
    }

    /** Плитка «Часы · Погода · Климат» в выборе: у неё нет своей сущности. */
    const val CLIMATE = "climate"
    const val NO_ROOM = "Без комнаты"
    /** Выключателей и розеток в комнате отмечено сразу не больше. */
    private const val SWITCHES_PER_ROOM = 4
    private const val CAMERAS_ON_MAIN = 2
    /** «По умолчанию отмечено не больше, чем входит на главную и две страницы». */
    private const val DEFAULT_PAGES = 3
    private val GATE_COVERS = setOf("gate", "garage", "door")
    private val INNER_TEMPERATURE = listOf("cpu", "processor", "chip", "device", "board", "процессор", "плат", "устройств", "water", "вод", "boiler", "котел", "котёл")

    /** [known] — устройства Home Assistant, чьи камеры уже настроены напрямую: их камеры не предлагаются. */
    fun propose(home: HaHome, known: Set<String> = emptySet()): Proposal {
        val usable = home.entities.filter { !it.hidden }
        var skipped = 0
        fun available(e: HaHome.Entity): Boolean = e.available.also { if (!it) skipped++ }
        val byDevice = usable.filter { it.device.isNotEmpty() }.groupBy { it.device }
        fun deviceHas(e: HaHome.Entity, vararg domains: String) = byDevice[e.device].orEmpty().any { it.domain in domains }

        val items = LinkedHashMap<String, MutableList<Item>>()
        fun add(e: HaHome.Entity, name: String, kind: Kind, recommended: Boolean) {
            items.getOrPut(home.areaOf(e)) { mutableListOf() }.add(Item(e.id, name, kind, recommended))
        }
        val order = compareBy<HaHome.Entity>({ it.name.lowercase(Locale("ru")) }, { it.id })
        val sorted = usable.sortedWith(order)
        for (e in sorted) when (e.domain) {
            // Группы света Home Assistant и Hue: их участники предлагаются сами, группу панель делает свою.
            "light" -> if (!e.attributes.has("entity_id") && !e.attributes.optBoolean("is_hue_group") && available(e))
                add(e, e.name, Kind.LIGHT, home.areaOf(e).isNotEmpty())
            // Выключатели камер, колонок и служб — их настройки, а не устройства комнаты.
            "switch" -> if (home.devices[e.device]?.service != true && !deviceHas(e, "camera", "media_player") && available(e)) {
                val room = home.areaOf(e)
                val marked = items[room].orEmpty().count { it.kind == Kind.SWITCH && it.recommended }
                add(e, e.name, Kind.SWITCH, room.isNotEmpty() && marked < SWITCHES_PER_ROOM)
            }
            "fan" -> if (available(e)) add(e, e.name, Kind.FAN, home.areaOf(e).isNotEmpty())
            "lock" -> if (available(e)) add(e, e.name, Kind.GATE, true)
            "cover" -> if (e.deviceClass in GATE_COVERS && available(e)) add(e, e.name, Kind.GATE, true)
        }
        // Камера отдаёт несколько потоков — несколько сущностей: предлагается одна на устройство.
        var cameras = 0
        sorted.filter { it.domain == "camera" && it.device !in known }.groupBy { it.device.ifEmpty { it.id } }.values.forEach { same ->
            val e = same.firstOrNull { it.available } ?: run { skipped++; return@forEach }
            add(e, home.devices[e.device]?.name?.takeIf { it.isNotBlank() } ?: e.name, Kind.CAMERA, cameras++ < CAMERAS_ON_MAIN)
        }

        val rooms = home.areas.sortedWith(compareBy({ home.floors[it.floor]?.level ?: Int.MAX_VALUE }, { home.floors[it.floor]?.name.orEmpty() },
            { it.name.lowercase(Locale("ru")) })).mapNotNull { area ->
            val (temperature, humidity) = climate(home, area, usable)
            val list = items[area.id].orEmpty().sortedBy { it.kind.ordinal }
            if (list.isEmpty() && temperature.isEmpty()) null else Room(area.id, area.name, list, temperature, humidity)
        }
        val areaIds = home.areas.mapTo(HashSet()) { it.id }
        val outside = items.filterKeys { it !in areaIds }.values.flatten().sortedBy { it.kind.ordinal }
        val weather = usable.filter { it.domain == "weather" && it.available }.sortedWith(compareBy({ it.id != "weather.home" }, { it.id }))
            .firstOrNull()?.id.orEmpty()
        return Proposal(rooms + if (outside.isEmpty()) emptyList() else listOf(Room("", NO_ROOM, outside)), weather, skipped)
    }

    /**
     * То, что Home Assistant знает об этой камере: её сущность (видео вне дома) и датчики событий того же устройства —
     * чтобы звонок и значки работали сразу. Заполняется только пустое.
     */
    fun withHome(config: DoorbellConfig, home: HaHome, device: String, doorbell: Boolean): DoorbellConfig {
        if (device.isEmpty()) return config
        val mine = home.entities.filter { it.device == device && !it.hidden }.sortedBy { it.id }
        fun sensor(vararg ends: String) = mine.firstOrNull { e -> e.domain == "binary_sensor" && ends.any { e.id.endsWith("_$it") } }?.id.orEmpty()
        return config.copy(
            camera = config.camera.ifEmpty { mine.firstOrNull { it.domain == "camera" && it.available }?.id.orEmpty() },
            visitor = config.visitor.ifEmpty { if (doorbell) sensor("visitor", "doorbell", "ring") else "" },
            person = config.person.ifEmpty { sensor("person", "people", "human") }, vehicle = config.vehicle.ifEmpty { sensor("vehicle", "car") },
            animal = config.animal.ifEmpty { sensor("animal", "pet") }, motion = config.motion.ifEmpty { sensor("motion") })
    }

    /** Датчики «Климата» комнаты: выбранные в Home Assistant или самые подходящие. */
    private fun climate(home: HaHome, area: HaHome.Area, usable: List<HaHome.Entity>): Pair<String, String> {
        val sensors = usable.filter { it.domain == "sensor" && it.available && home.areaOf(it) == area.id }
        fun value(e: HaHome.Entity) = e.state.toDoubleOrNull()?.takeIf { it.isFinite() }
        val temperatures = sensors.filter { it.deviceClass == "temperature" && HomeClimateData.isTemperatureUnit(it.unit) && value(it) != null }
        val humidities = sensors.filter { it.deviceClass == "humidity" && it.unit == "%" && value(it) != null }
        val all = usable.associateBy { it.id }
        val chosen = all[area.temperature]?.takeIf { it.domain == "sensor" && HomeClimateData.isTemperatureUnit(it.unit) }
        val temperature = chosen ?: temperatures.maxWithOrNull(compareBy<HaHome.Entity> { e ->
            val text = (e.id + " " + e.name).lowercase(Locale("ru"))
            (if (humidities.any { it.device.isNotEmpty() && it.device == e.device }) 2 else 0) - (if (INNER_TEMPERATURE.any { it in text }) 3 else 0)
        }.thenByDescending { it.id })?.takeIf { e -> INNER_TEMPERATURE.none { it in (e.id + " " + e.name).lowercase(Locale("ru")) } }
        val humidity = all[area.humidity]?.takeIf { it.domain == "sensor" && it.unit == "%" }
            ?: humidities.firstOrNull { it.device.isNotEmpty() && it.device == temperature?.device } ?: humidities.minByOrNull { it.id }
        val ok = temperature?.id?.let { HomeClimateConfig.validEntity(it, "sensor") } == true
        return if (!ok) "" to "" else temperature!!.id to humidity?.id?.takeIf { HomeClimateConfig.validEntity(it, "sensor") }.orEmpty()
    }

    // ---- Раскладка ----

    class Plan(val tiles: List<TileEntity>, val gates: List<Gate>, val pages: Int)

    /** Отмеченное сразу: рекомендованное, но не больше главной и двух страниц. */
    fun defaults(proposal: Proposal, width: Int, height: Int, density: Float, direct: List<Direct> = emptyList()): Set<String> {
        val chosen = LinkedHashSet<String>()
        chosen += CLIMATE
        // Настроенные напрямую занимают места камер на главной: через Home Assistant тогда отмечаются лишь оставшиеся.
        var spare = (CAMERAS_ON_MAIN - direct.size).coerceAtLeast(0)
        proposal.items.filter { it.recommended && (it.kind != Kind.CAMERA || spare-- > 0) }.mapTo(chosen) { it.entity }
        val removable = proposal.rooms.asReversed().flatMap { room ->
            room.items.filter { it.recommended }.sortedByDescending { it.kind == Kind.SWITCH }.filter { it.kind != Kind.GATE && it.kind != Kind.CAMERA } }
        for (item in removable) {
            if (layout(proposal, chosen, width, height, density, direct = direct).pages <= DEFAULT_PAGES) break
            chosen -= item.entity
        }
        return chosen
    }

    /** Плитка с видео: настроенная напрямую (номер и пароль уже есть) или камера Home Assistant. */
    private class Video(val id: String, val type: String, val title: String, val config: DoorbellConfig)

    private class Cell(val tile: (x: Int, y: Int, page: Int) -> TileEntity, val columns: Int = 1)

    private class Grid(width: Int, height: Int, private val density: Float) {
        private fun dp(v: Int) = (v * density).toInt()
        val margin = dp(16).coerceAtLeast(20)
        val gap = dp(10).coerceAtLeast(8)
        val top = 40
        val bottom = (height - 100).coerceAtLeast(top + 100)
        val inner = width - 2 * margin
        private val wanted = dp(107).coerceIn(80, ((bottom - top) / 2).coerceAtLeast(80))
        val columns = ((inner + gap) / (wanted + gap)).coerceAtLeast(2)
        val side = ((inner - (columns - 1) * gap) / columns).coerceAtMost(bottom - top)
        fun rows(from: Int) = ((bottom - from + gap) / (side + gap)).coerceAtLeast(0)
        fun x(column: Int) = margin + column * (side + gap)
        fun y(from: Int, row: Int) = from + row * (side + gap)
        fun span(columns: Int) = columns * side + (columns - 1) * gap
    }

    fun layout(proposal: Proposal, selected: Set<String>, width: Int, height: Int, density: Float, existing: List<Gate> = emptyList(),
               direct: List<Direct> = emptyList()): Plan {
        val w = maxOf(width, height)
        val h = minOf(width, height)
        val grid = Grid(w, h, density)
        val tiles = mutableListOf<TileEntity>()
        val gates = mutableListOf<Gate>()
        val chosen = proposal.items.filter { it.entity in selected }

        // Главная, верх: «Часы · Погода · Климат» 2:1 и до двух камер 4:3 одной высоты.
        val cameras = direct.sortedByDescending { it.doorbell }.map { Video(it.id, if (it.doorbell) DoorbellConfig.TYPE else DoorbellConfig.CAMERA_TYPE, it.title, it.config) } +
            chosen.filter { it.kind == Kind.CAMERA }.map { Video(UUID.randomUUID().toString(), DoorbellConfig.CAMERA_TYPE, it.name, DoorbellConfig(camera = it.entity, talk = false)) }
        val onMain = cameras.take(CAMERAS_ON_MAIN)
        val ratios = (if (CLIMATE in selected) listOf(HomeClimateConfig.ASPECT) else emptyList()) + onMain.map { DoorbellConfig.DEFAULT_ASPECT }
        var flowTop = grid.top
        if (ratios.isNotEmpty()) {
            val byWidth = (grid.inner - grid.gap * (ratios.size - 1)) / ratios.sum()
            val rowHeight = minOf(byWidth, (grid.bottom - grid.top) * TOP_SHARE).toInt().coerceAtLeast(HomeClimateConfig.MIN_HEIGHT)
            var x = grid.margin
            if (CLIMATE in selected) {
                val cw = (rowHeight * HomeClimateConfig.ASPECT).toInt()
                tiles += tile(HomeClimateConfig.TYPE, "grid", "Часы · Погода · Климат", climateConfig(proposal, cw, rowHeight), x, grid.top, 0)
                x += cw + grid.gap
            }
            onMain.forEach { item ->
                val cw = (rowHeight * DoorbellConfig.DEFAULT_ASPECT).toInt()
                tiles += videoTile(item, cw, rowHeight, x, grid.top, 0)
                x += cw + grid.gap
            }
            flowTop = grid.top + rowHeight + grid.gap
        }

        // Ниже — проходы, затем общий свет комнат, пока помещается.
        val pending = ArrayDeque<Cell>()
        chosen.filter { it.kind == Kind.GATE }.forEach { item ->
            val known = (existing + gates).firstOrNull { it.open == item.entity }
            if (known == null && existing.size + gates.size >= Gate.MAX) return@forEach
            val gate = known ?: gate(item, existing + gates).also { gates += it }
            pending += Cell({ x, y, page -> tile(GateTileConfig.TYPE, "grid", gate.name, GateTileConfig.withGate(
                TilePlacement.withSize("{}", grid.span(2), grid.side), gate.id), x, y, page) }, columns = 2)
        }
        val lights = proposal.rooms.filter { it.id.isNotEmpty() }.map { room -> room to room.items.filter { it.kind == Kind.LIGHT && it.entity in selected } }
            .filter { it.second.isNotEmpty() }
        val lightCells = lights.associate { (room, list) -> room.id to lightCell(room, list, grid) }
        val rest = place(pending.toList(), 0, flowTop, grid, tiles)
        val placedLights = HashSet<String>()
        // Свет — только целиком помещающиеся ячейки, в порядке комнат.
        var free = rest.next
        if (rest.left.isEmpty()) for ((room, _) in lights) {
            val spot = free ?: break
            val put = place(listOf(lightCells.getValue(room.id)), 0, flowTop, grid, tiles, spot)
            if (put.left.isNotEmpty()) break
            placedLights += room.id
            free = put.next
        }

        val blocks = mutableListOf<List<Cell>>()
        if (rest.left.isNotEmpty()) blocks += rest.left
        for (room in proposal.rooms) {
            val mine = room.items.filter { it.entity in selected }
            val own = mine.filter { it.kind == Kind.LIGHT }
            val cells = mutableListOf<Cell>()
            if (room.id.isEmpty()) own.forEach { cells += button(it, room, grid) }
            else {
                if (own.isNotEmpty() && room.id !in placedLights) cells += lightCells.getValue(room.id)
                // Двух-трёх светильников — ещё и по отдельности; больше — в группе (удержание открывает каждый).
                if (own.size in 2..FEW_LIGHTS) own.forEach { cells += button(it, room, grid) }
            }
            mine.filter { it.kind == Kind.SWITCH || it.kind == Kind.FAN }.forEach { cells += button(it, room, grid) }
            if (cells.isNotEmpty()) blocks += cells
        }

        // Небольшой дом целиком помещается на главной: комнаты ниже общего света, каждая с новой строки.
        val mainRows = grid.rows(flowTop)
        val from = free?.let { if (it.second == 0) it.first else it.first + 1 } ?: mainRows
        if (rest.left.isEmpty() && placedLights.size == lights.size && from + blocks.sumOf { rowsFor(it, grid.columns) } <= mainRows) {
            var line = from
            blocks.forEach { place(it, 0, flowTop, grid, tiles, line to 0); line += rowsFor(it, grid.columns) }
            blocks.clear()
        }

        // Иначе страницы комнат: каждая комната — с новой строки, комнаты не рвутся, пока помещаются целиком.
        var page = 1
        var row = 0
        var used = false
        val perPage = grid.rows(grid.top).coerceAtLeast(1)
        for (cells in blocks) {
            if (row > 0 && row + rowsFor(cells, grid.columns) > perPage) { page++; row = 0 }
            var left = cells
            while (left.isNotEmpty()) {
                val put = place(left, page, grid.top, grid, tiles, row to 0)
                left = put.left
                used = true
                if (left.isNotEmpty()) { page++; row = 0 } else row = (put.next?.let { if (it.second == 0) it.first else it.first + 1 }) ?: perPage
            }
            if (row >= perPage) { page++; row = 0 }
        }
        var pages = if (used) (if (row == 0 && page > 1) page else page + 1) else 1

        // Остальные камеры — на своих страницах, по две в ряд.
        cameras.drop(CAMERAS_ON_MAIN).chunked(CAMERAS_PER_PAGE).forEach { group ->
            val cw = minOf((grid.inner - grid.gap) / 2, ((grid.bottom - grid.top - grid.gap) / 2 * DoorbellConfig.DEFAULT_ASPECT).toInt())
            val ch = (cw / DoorbellConfig.DEFAULT_ASPECT).toInt()
            group.forEachIndexed { i, item ->
                tiles += videoTile(item, cw, ch, grid.margin + i % 2 * (cw + grid.gap), grid.top + i / 2 * (ch + grid.gap), pages)
            }
            pages++
        }
        return Plan(tiles, gates, pages)
    }

    private class Placed(val left: List<Cell>, /** Следующее свободное место (строка, столбец); null — страница заполнена. */ val next: Pair<Int, Int>?)

    private fun place(cells: List<Cell>, page: Int, from: Int, grid: Grid, out: MutableList<TileEntity>, start: Pair<Int, Int> = 0 to 0): Placed {
        var (row, column) = start
        val rows = grid.rows(from)
        cells.forEachIndexed { i, cell ->
            if (column + cell.columns > grid.columns) { row++; column = 0 }
            if (row >= rows) return Placed(cells.drop(i), null)
            out += cell.tile(grid.x(column), grid.y(from, row), page)
            column += cell.columns
        }
        if (column >= grid.columns) { row++; column = 0 }
        return Placed(emptyList(), (row to column).takeIf { row < rows })
    }

    private fun rowsFor(cells: List<Cell>, columns: Int): Int {
        var rows = 1
        var column = 0
        cells.forEach { if (column + it.columns > columns) { rows++; column = 0 }; column += it.columns }
        return rows
    }

    private fun tile(type: String, container: String, title: String, config: String, x: Int, y: Int, page: Int) =
        TileEntity(UUID.randomUUID().toString(), type, container, title, x, y, 1, 1, config = config, page = page)

    private fun lightCell(room: Room, lights: List<Item>, grid: Grid): Cell = if (lights.size == 1) button(lights.single(), room, grid) else {
        val config = JSONObject().put("button_size", grid.side).put(ModernIcon.CONFIG_KEY, ModernIcon.BULB.key)
            .put("entity_ids", JSONArray(lights.map { it.entity })).put("child_names", JSONArray(lights.map { short(it.name, room.name) }))
        TileButtonStyle.write(config, TileButtonStyle.NEW_TILE)
        Cell({ x, y, page -> tile("group", "bottom_panel", "Свет · ${room.name}", config.toString(), x, y, page) })
    }

    private fun button(item: Item, room: Room, grid: Grid): Cell {
        val config = JSONObject().put("button_size", grid.side).put("entity_id", item.entity)
        TileButtonStyle.write(config, TileButtonStyle.NEW_TILE)
        return Cell({ x, y, page -> tile("button", "bottom_panel", withRoom(item.name, room), config.toString(), x, y, page) })
    }

    private fun climateConfig(proposal: Proposal, width: Int, height: Int): String {
        val rooms = proposal.rooms.filter { it.temperature.isNotEmpty() }
            .map { HomeClimateConfig.Room("room_${it.id}", it.name, icon(it.name)?.key, it.temperature, it.humidity) }
        return HomeClimateConfig(weather = HomeClimateConfig.Weather(entityId = proposal.weather), rooms = rooms,
            favorites = rooms.take(HomeClimateConfig.MAX_FAVORITES).map { it.id }).writeTo(TilePlacement.withSize("{}", width, height))
    }

    /** Значок комнаты по названию; незнакомое — без значка. */
    private fun icon(room: String): ModernIcon? {
        val name = room.lowercase(Locale("ru"))
        return ROOM_ICONS.firstOrNull { (words, _) -> words.any { it in name } }?.second
    }
    private val ROOM_ICONS = listOf(
        listOf("кухн", "kitchen") to ModernIcon.KITCHEN, listOf("спальн", "bedroom") to ModernIcon.BED,
        listOf("гостин", "зал", "living") to ModernIcon.SOFA, listOf("ванн", "душ", "bath") to ModernIcon.BATH,
        listOf("детск", "kid", "child", "nursery") to ModernIcon.BABY, listOf("кабинет", "office") to ModernIcon.OFFICE,
        listOf("кладов", "storage") to ModernIcon.BOX, listOf("серверн", "server") to ModernIcon.SERVER,
        listOf("двор", "сад", "улиц", "garden", "yard") to ModernIcon.TREE, listOf("гараж", "garage") to ModernIcon.GARAGE)

    private fun videoTile(v: Video, width: Int, height: Int, x: Int, y: Int, page: Int) = TileEntity(v.id, v.type, "grid", v.title, x, y, 1, 1,
        config = v.config.writeTo(TilePlacement.withSize("{}", width, height)), page = page)

    private fun gate(item: Item, all: List<Gate>): Gate = if (item.entity.startsWith("lock."))
        Gate(GateStore.newId(all), item.name, Gate.Kind.LOCK, open = item.entity, icon = Gate.Icon.DOOR)
        else Gate(GateStore.newId(all), item.name, Gate.Kind.COVER, open = item.entity, icon = Gate.Icon.GATE, confirmSeconds = GATE_CONFIRM_S)

    /** Имя с комнатой, если в самом имени её нет («Люстра» → «Кухня · Люстра»). */
    internal fun withRoom(name: String, room: Room): String =
        if (room.id.isEmpty() || mentions(name, room.name)) name else "${room.name} · $name"

    /** Имя без комнаты в начале («Кухня Люстра» → «Люстра») — для кнопок внутри группы комнаты. */
    internal fun short(name: String, room: String): String =
        if (name.length > room.length && name.startsWith(room, true)) name.drop(room.length).trim(' ', '·', '-', ':').ifEmpty { name } else name

    /** Комната названа в имени с точностью до окончания: «Свет в кухне» — это «Кухня». */
    private fun mentions(name: String, room: String): Boolean {
        val text = name.lowercase(Locale("ru"))
        return room.lowercase(Locale("ru")).split(' ', '-').filter { it.length >= 3 }.all { word ->
            val stem = if (word.length <= 4) word.dropLast(1).ifEmpty { word } else word.dropLast(2)
            stem in text
        } && room.isNotBlank()
    }

    private const val TOP_SHARE = 0.5f
    private const val FEW_LIGHTS = 3
    private const val CAMERAS_PER_PAGE = 4
    /** Ворота едут долго: ожидание подтверждения — как у нового прохода с приводом в редакторе. */
    private const val GATE_CONFIRM_S = 45
}
