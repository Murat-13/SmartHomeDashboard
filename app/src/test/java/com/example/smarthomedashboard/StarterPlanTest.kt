package com.example.smarthomedashboard

import android.graphics.Rect
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Первые плитки по комнатам (docs/setup-tz.md, часть 2): подбор, раскладка без наложений, создание и отмена. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarterPlanTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)

    @Before fun clean() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        GateHub.invalidate()
    }

    /** Выдуманный дом: реестры и состояния в виде ответов Home Assistant. */
    private class Home {
        val areas = JSONArray(); val floors = JSONArray(); val devices = JSONArray(); val registry = JSONArray(); val states = JSONArray()
        fun floor(id: String, level: Int) = apply { floors.put(JSONObject().put("floor_id", id).put("name", id).put("level", level)) }
        fun area(id: String, name: String, floor: String? = null, temperature: String? = null) = apply {
            areas.put(JSONObject().put("area_id", id).put("name", name).put("floor_id", floor ?: JSONObject.NULL)
                .put("temperature_entity_id", temperature ?: JSONObject.NULL))
        }
        fun device(id: String, area: String? = null, name: String = id, service: Boolean = false, url: String? = null) = apply {
            devices.put(JSONObject().put("id", id).put("area_id", area ?: JSONObject.NULL).put("name", name).put("name_by_user", JSONObject.NULL)
                .put("entry_type", if (service) "service" else JSONObject.NULL).put("configuration_url", url ?: JSONObject.NULL))
        }
        fun entity(id: String, name: String, area: String? = null, device: String? = null, state: String = "off", deviceClass: String? = null,
                   unit: String? = null, category: String? = null, hidden: String? = null, registered: Boolean = true,
                   attributes: JSONObject = JSONObject()) = apply {
            if (registered) registry.put(JSONObject().put("entity_id", id).put("area_id", area ?: JSONObject.NULL).put("device_id", device ?: JSONObject.NULL)
                .put("entity_category", category ?: JSONObject.NULL).put("hidden_by", hidden ?: JSONObject.NULL).put("disabled_by", JSONObject.NULL)
                .put("name", JSONObject.NULL).put("original_name", name).put("platform", "demo"))
            states.put(JSONObject().put("entity_id", id).put("state", state).put("attributes", attributes.put("friendly_name", name).apply {
                deviceClass?.let { put("device_class", it) }; unit?.let { put("unit_of_measurement", it) }
            }))
        }
        fun read() = HaHome.parse(areas, floors, devices, registry, states)
    }

    private fun house() = Home().floor("up", 1).floor("down", 0)
        .area("bed", "Спальня", "up").area("kit", "Кухня", "down").area("hall", "Гостиная", "down", "sensor.hall_t").area("yard", "Двор").area("empty", "Чердак", "up")
        .device("lamp_dev", "kit").device("cam_dev", "yard", "Калитка", url = "http://192.0.2.60:80").device("tv_dev", "hall").device("svc", service = true)
        .device("climate_dev", "bed").device("boiler_dev", "bed")
        // Кухня: комната у устройства, а не у сущности.
        .entity("light.kitchen_main", "Кухня Люстра", device = "lamp_dev").entity("light.kitchen_led", "Подсветка", "kit")
        .entity("switch.kettle", "Чайник", "kit", deviceClass = "outlet")
        // Гостиная: пять светильников, группа Home Assistant и выключатель телевизора.
        .entity("light.hall_1", "Свет в гостиной 1", "hall").entity("light.hall_2", "Свет в гостиной 2", "hall").entity("light.hall_3", "Торшер", "hall")
        .entity("light.hall_4", "Бра левое", "hall").entity("light.hall_5", "Бра правое", "hall")
        .entity("light.hall_all", "Весь свет гостиной", "hall", attributes = JSONObject().put("entity_id", JSONArray(listOf("light.hall_1"))))
        .entity("media_player.tv", "Телевизор", device = "tv_dev").entity("switch.tv_mute", "Без звука", device = "tv_dev")
        // Спальня: одна лампа, вентилятор, датчики.
        .entity("light.bed", "Ночник", "bed", state = "on").entity("fan.bed", "Вентилятор", "bed")
        .entity("sensor.bed_t", "Температура", device = "climate_dev", state = "22.5", deviceClass = "temperature", unit = "°C")
        .entity("sensor.bed_h", "Влажность", device = "climate_dev", state = "45", deviceClass = "humidity", unit = "%")
        .entity("sensor.boiler_water_t", "Температура воды", device = "boiler_dev", state = "60", deviceClass = "temperature", unit = "°C")
        .entity("sensor.hall_t", "Датчик", "hall", state = "21", deviceClass = "temperature", unit = "°C")
        // Двор: ворота, замок, две сущности одной камеры, её выключатель.
        .entity("cover.gate", "Ворота", "yard", state = "closed", deviceClass = "gate").entity("cover.blind", "Жалюзи", "bed", state = "open", deviceClass = "blind")
        .entity("lock.wicket", "Замок калитки", "yard", state = "locked")
        .entity("camera.door_main", "Калитка Чёткий", device = "cam_dev", state = "idle").entity("camera.door_sub", "Калитка Плавный", device = "cam_dev", state = "unavailable")
        .entity("switch.door_ir", "ИК-подсветка", device = "cam_dev")
        // Не предлагается.
        .entity("switch.child_lock", "Блокировка", "kit", category = "config").entity("light.hidden", "Скрытый", "kit", hidden = "user")
        .entity("light.dead", "Погасший", "kit", state = "unavailable").entity("automation.x", "Автоматизация").entity("script.y", "Сценарий")
        .entity("switch.adblock", "Фильтр", device = "svc").entity("update.z", "Обновление")
        // Без комнаты и без записи в реестре.
        .entity("light.garage", "Гараж", registered = false).entity("weather.home", "Дом", state = "sunny").entity("weather.other", "Другая", state = "rainy")

    private fun proposal() = StarterPlan.propose(house().read())
    private fun StarterPlan.Proposal.room(name: String) = rooms.single { it.name == name }
    private fun StarterPlan.Room.entities(kind: StarterPlan.Kind? = null) = items.filter { kind == null || it.kind == kind }.map { it.entity }

    @Test fun proposalTakesDevicesOfRoomsAndLeavesServiceOnesOut() {
        val p = proposal()
        assertEquals("этажи снизу вверх, затем по имени", listOf("Гостиная", "Кухня", "Спальня", "Двор", StarterPlan.NO_ROOM), p.rooms.map { it.name })
        assertEquals(setOf("light.kitchen_main", "light.kitchen_led", "switch.kettle"), p.room("Кухня").entities().toSet())
        assertEquals(5, p.room("Гостиная").entities(StarterPlan.Kind.LIGHT).size)
        assertTrue("выключатель телевизора — не устройство комнаты", p.items.none { it.entity == "switch.tv_mute" })
        assertTrue("группа HA, служебное, скрытое, службы, автоматизации", p.items.none { it.entity in setOf("light.hall_all", "switch.child_lock",
            "light.hidden", "switch.adblock", "switch.door_ir", "automation.x", "script.y", "update.z", "cover.blind") })
        assertTrue("недоступное не предлагается, но посчитано", p.items.none { it.entity == "light.dead" } && p.skipped >= 1)
        assertEquals(listOf("light.garage"), p.room(StarterPlan.NO_ROOM).entities())
        assertFalse("без комнаты — без галочки", p.room(StarterPlan.NO_ROOM).items.single().recommended)
        assertEquals(setOf("cover.gate", "lock.wicket"), p.room("Двор").entities(StarterPlan.Kind.GATE).toSet())
        assertEquals("одна камера на устройство", listOf("camera.door_main"), p.room("Двор").entities(StarterPlan.Kind.CAMERA))
        assertEquals("Калитка", p.room("Двор").items.single { it.kind == StarterPlan.Kind.CAMERA }.name)
        assertEquals("weather.home", p.weather)
    }

    @Test fun roomClimateUsesChosenSensorAndIgnoresWaterAndChips() {
        val p = proposal()
        assertEquals("датчик, выбранный в Home Assistant", "sensor.hall_t", p.room("Гостиная").temperature)
        assertEquals("", p.room("Гостиная").humidity)
        assertEquals("sensor.bed_t", p.room("Спальня").temperature)
        assertEquals("sensor.bed_h", p.room("Спальня").humidity)
        assertEquals("", p.room("Кухня").temperature)
        val only = StarterPlan.propose(Home().area("b", "Котельная").entity("sensor.water_t", "Вода", "b", state = "55", deviceClass = "temperature", unit = "°C")
            .entity("sensor.nan", "Комната", "b", state = "unknown", deviceClass = "temperature", unit = "°C").read())
        assertTrue("вода и неизвестное — не климат комнаты", only.rooms.isEmpty() && only.isEmpty)
    }

    private fun plan(p: StarterPlan.Proposal, w: Int = 1920, h: Int = 1200, d: Float = 1.5f, chosen: Set<String> = StarterPlan.defaults(p, w, h, d)) =
        StarterPlan.layout(p, chosen, w, h, d)

    private fun rect(t: TileEntity, w: Int, h: Int): Rect {
        val size = TilePlacement.displaySize(t.container, t.config, w, h).let { s ->
            if (t.type == HomeClimateConfig.TYPE) HomeClimateConfig.fit(s.width, s.height).let { TilePlacement.Size(it.first, it.second) } else s }
        val (x, y) = TilePlacement.position(t.x.toFloat(), t.y.toFloat(), size.width, size.height, w, h)
        assertEquals("панель не сдвинет плитку ${t.title}", t.x.toFloat() to t.y.toFloat(), x to y)
        return Rect(t.x, t.y, t.x + size.width, t.y + size.height)
    }

    private fun assertTidy(plan: StarterPlan.Plan, w: Int, h: Int) {
        plan.tiles.groupBy { it.page }.forEach { (page, list) ->
            assertTrue("страница $page из ${plan.pages}", page in 0 until plan.pages)
            val rects = list.map { it to rect(it, w, h) }
            rects.forEach { (t, r) -> assertTrue("${t.title}: $r в $w×$h", r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h - 60) }
            for (i in rects.indices) for (j in 0 until i) assertFalse("наложение ${rects[i].first.title} и ${rects[j].first.title}",
                Rect.intersects(rects[i].second, rects[j].second))
        }
        assertEquals("пустых страниц нет", plan.pages, plan.tiles.maxOfOrNull { it.page }?.plus(1) ?: 1)
        assertEquals(plan.tiles.size, plan.tiles.map { it.id }.distinct().size)
    }

    @Test fun houseIsLaidOutOnMainAndRoomPages() {
        val p = proposal()
        val plan = plan(p)
        assertTidy(plan, 1920, 1200)
        val main = plan.tiles.filter { it.page == 0 }
        assertEquals(1, main.count { it.type == HomeClimateConfig.TYPE })
        assertEquals(1, main.count { it.type == DoorbellConfig.CAMERA_TYPE })
        assertEquals(2, main.count { it.type == GateTileConfig.TYPE })
        // Общий свет комнат — на главной.
        assertEquals(setOf("Свет · Гостиная", "Свет · Кухня", "Спальня · Ночник"), main.filter { it.container == "bottom_panel" }.map { it.title }.toSet())
        val rooms = plan.tiles.filter { it.page > 0 }
        assertEquals("больше трёх светильников — только группой", 0, rooms.count { it.title.contains("Торшер") || it.title.contains("Бра") })
        assertEquals(setOf("Кухня Люстра", "Кухня · Подсветка", "Кухня · Чайник", "Спальня · Вентилятор"), rooms.map { it.title }.toSet())
        assertEquals(2, plan.pages)
        val group = TileButtonConfig.group(main.single { it.title == "Свет · Кухня" }.config) as ButtonConfigResult.Valid
        assertEquals(listOf("light.kitchen_main", "light.kitchen_led"), group.value.entityIds)
        assertEquals("в группе комнаты её имя не повторяется", listOf("Люстра", "Подсветка"), group.value.childNames)
        assertTrue(plan.tiles.filter { it.container == "bottom_panel" }.all { TileButtonStyle.isModern(it.type, it.config) })
        val climate = HomeClimateConfig.from(main.single { it.type == HomeClimateConfig.TYPE }.config)
        assertEquals("weather.home", climate.weather.entityId)
        assertEquals(listOf("Гостиная", "Спальня"), climate.rooms.map { it.name })
        assertEquals(climate.rooms.map { it.id }, climate.favorites)
        assertNull(climate.validationError())
        assertEquals("bed", climate.rooms[1].icon)
        // Проходы: замок и привод, открываются удержанием, как всегда.
        assertEquals(setOf(Gate.Kind.LOCK, Gate.Kind.COVER), plan.gates.map { it.kind }.toSet())
        assertTrue(plan.gates.all { it.ready && !it.autoClose && !it.phone })
        assertEquals(plan.gates.map { it.id }.toSet(), main.filter { it.type == GateTileConfig.TYPE }.map { GateTileConfig.gateId(it.config) }.toSet())
    }

    @Test fun onlyChosenIsCreatedAndEmptyChoiceGivesNothing() {
        val p = proposal()
        val plan = plan(p, chosen = setOf("switch.kettle"))
        assertEquals(listOf("Кухня · Чайник"), plan.tiles.map { it.title })
        assertTidy(plan, 1920, 1200)
        assertTrue(plan(p, chosen = emptySet()).tiles.isEmpty())
        val unassigned = plan(p, chosen = setOf("light.garage"))
        assertEquals(listOf("Гараж"), unassigned.tiles.map { it.title })
    }

    @Test fun gatesAreNotDuplicatedAndLimited() {
        val p = proposal()
        val own = Gate("gate_7", "Моя калитка", Gate.Kind.LOCK, open = "lock.wicket")
        val plan = StarterPlan.layout(p, StarterPlan.defaults(p, 1920, 1200, 1.5f), 1920, 1200, 1.5f, listOf(own))
        assertEquals("уже настроенный проход не создаётся второй раз", listOf("cover.gate"), plan.gates.map { it.open })
        assertTrue(plan.tiles.any { GateTileConfig.gateId(it.config) == "gate_7" })
        val many = Home().area("a", "Двор").apply { repeat(12) { entity("lock.l$it", "Замок $it", "a", state = "locked") } }
        assertEquals(Gate.MAX, plan(StarterPlan.propose(many.read())).gates.size)
    }

    @Test fun namesGetRoomOnlyWhenNeeded() {
        val kitchen = StarterPlan.Room("k", "Кухня", emptyList())
        assertEquals("Кухня · Люстра", StarterPlan.withRoom("Люстра", kitchen))
        assertEquals("Свет в кухне", StarterPlan.withRoom("Свет в кухне", kitchen))
        assertEquals("Kitchen Lamp", StarterPlan.withRoom("Kitchen Lamp", StarterPlan.Room("k", "Kitchen", emptyList())))
        assertEquals("Люстра", StarterPlan.withRoom("Люстра", StarterPlan.Room("", StarterPlan.NO_ROOM, emptyList())))
        assertEquals("Зал · Люстра", StarterPlan.withRoom("Люстра", StarterPlan.Room("z", "Зал", emptyList())))
        assertEquals("Люстра", StarterPlan.short("Кухня Люстра", "Кухня"))
        assertEquals("Кухня", StarterPlan.short("Кухня", "Кухня"))
    }

    /** 200 случайных домов × 8 экранов: ничего не накладывается, по умолчанию — не больше трёх страниц. */
    @Test fun randomHomesNeverOverlap() {
        val screens = listOf(Triple(1920, 1200, 1.5f), Triple(2000, 1200, 2f), Triple(1280, 800, 1f), Triple(2560, 1600, 2f),
            Triple(1024, 600, 1f), Triple(2400, 1080, 2.625f), Triple(1200, 1920, 1.5f), Triple(800, 480, 1f))
        val random = Random(20261124)
        repeat(200) { n ->
            val home = Home()
            val rooms = random.nextInt(0, 14)
            repeat(rooms) { home.area("a$it", listOf("Кухня", "Спальня", "Детская", "Очень длинное название комнаты")[it % 4] + " $it") }
            repeat(random.nextInt(0, 80)) { i ->
                val area = if (rooms > 0 && random.nextInt(6) > 0) "a${random.nextInt(rooms)}" else null
                when (random.nextInt(8)) {
                    0, 1, 2 -> home.entity("light.l$i", "Свет $i", area)
                    3, 4 -> home.entity("switch.s$i", "Розетка $i", area)
                    5 -> home.entity("fan.f$i", "Вентилятор $i", area)
                    6 -> home.entity(if (i % 2 == 0) "lock.k$i" else "cover.c$i", "Проход $i", area, state = "closed", deviceClass = "garage")
                    else -> if (i % 3 == 0) home.entity("camera.v$i", "Камера $i", area, state = "idle")
                        else home.entity("sensor.t$i", "Температура $i", area, state = "2$i".take(2), deviceClass = "temperature", unit = "°C")
                }
            }
            val p = StarterPlan.propose(home.read())
            for ((w, h, d) in screens) {
                val wide = maxOf(w, h); val low = minOf(w, h)
                val defaults = StarterPlan.defaults(p, w, h, d)
                val plan = StarterPlan.layout(p, defaults, w, h, d)
                assertTidy(plan, wide, low)
                val movable = p.items.any { it.entity in defaults && it.kind != StarterPlan.Kind.GATE && it.kind != StarterPlan.Kind.CAMERA }
                assertTrue("дом $n на $w×$h: страниц ${plan.pages}", plan.pages <= 3 || !movable)
                // Всё отмеченное — тоже без наложений.
                assertTidy(StarterPlan.layout(p, p.items.mapTo(HashSet()) { it.entity } + StarterPlan.CLIMATE, w, h, d), wide, low)
            }
        }
    }

    // ---- Создание и отмена ----

    @Test fun tilesAreCreatedOnlyOnEmptyPanelAndCanBeRemovedUntilEdited() {
        val p = proposal()
        assertTrue(StarterTiles.canPropose(context))
        assertFalse(StarterTiles.untouched(context))
        assertTrue(StarterTiles.apply(context, plan(p)))
        assertEquals(plan(p).tiles.size, TileManager(context).loadTiles().size)
        assertEquals(2, prefs.getInt(DashboardPanelController.PREF_PAGE_COUNT, 0))
        assertEquals(2, GateStore.load(context).size)
        assertTrue(prefs.getBoolean("starter_tiles_done", false))
        assertFalse("подсказки новичка остаются", prefs.contains("welcome_shown"))
        // Второй раз (вернулись на шаг) — заменяется, а не добавляется.
        assertTrue(StarterTiles.apply(context, plan(p, chosen = setOf("switch.kettle", "lock.wicket"))))
        assertEquals(2, TileManager(context).loadTiles().size)
        assertEquals(listOf("lock.wicket"), GateStore.load(context).map { it.open })
        assertTrue(StarterTiles.undo(context))
        assertTrue(TileManager(context).loadTiles().isEmpty())
        assertTrue(GateStore.load(context).isEmpty())
        assertFalse(StarterTiles.undo(context))
        assertFalse("часы и погода снова появятся сами", prefs.getBoolean("starter_tiles_done", true))
    }

    @Test fun editedPanelIsNeverTouched() {
        assertTrue(StarterTiles.apply(context, plan(proposal())))
        val manager = TileManager(context)
        manager.updateTile(manager.loadTiles().first().copy(title = "Моя"))
        assertFalse(StarterTiles.untouched(context))
        assertFalse(StarterTiles.canPropose(context))
        assertFalse(StarterTiles.undo(context))
        assertFalse(StarterTiles.apply(context, plan(proposal())))
        assertTrue(TileManager(context).loadTiles().any { it.title == "Моя" })
        assertEquals(2, GateStore.load(context).size)
        // Правка прохода — тоже правка.
        clean()
        assertTrue(StarterTiles.apply(context, plan(proposal())))
        GateStore.save(context, GateStore.load(context).map { it.copy(sensor = "binary_sensor.gate") })
        assertFalse(StarterTiles.undo(context))
        assertEquals(2, GateStore.load(context).size)
    }

    @Test fun oldAndLimitedHomeAssistantIsStillRead() {
        val h = house()
        val home = HaHome.read { type, _ -> when (type) {
            "get_states" -> h.states
            "config/entity_registry/list" -> h.registry
            "config/area_registry/list" -> h.areas
            else -> throw HaCalls.Refused("unknown_command")
        } }
        assertTrue(home.floors.isEmpty() && home.devices.isEmpty())
        assertTrue(StarterPlan.propose(home).items.any { it.entity == "light.kitchen_led" })
        val nothing = HaHome.read { _, _ -> throw HaCalls.Refused("unauthorized") }
        assertTrue(StarterPlan.propose(nothing).isEmpty)
        // Мусор вместо полей не роняет.
        val broken = HaHome.parse(JSONArray("[null,{},{\"area_id\":5}]"), JSONArray("[{\"floor_id\":null}]"), JSONArray("[1,\"x\"]"),
            JSONArray("[{\"entity_id\":\"без точки\"},{\"entity_id\":null}]"), JSONArray("[{\"entity_id\":\"light.x\"},{\"state\":\"on\"}]"))
        StarterPlan.propose(broken)
    }
}
