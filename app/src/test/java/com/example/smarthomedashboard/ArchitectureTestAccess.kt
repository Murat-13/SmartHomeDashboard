package com.example.smarthomedashboard

import org.robolectric.util.ReflectionHelpers

/** Доступ фикстур к вычисляемым свойствам после переноса владельца состояния. */
internal object ArchitectureTestAccess {
    class Field(private val name: String) {
        var isAccessible = false
        fun get(target: Any): Any? = getField<Any?>(target, name)
        fun set(target: Any, value: Any?) = setField(target, name, value)
    }
    fun field(name: String) = Field(name)

    private fun panel(activity: MainActivity): Any = MainActivity::class.java
        .getDeclaredMethod("getPanelController").apply { isAccessible = true }.invoke(activity)

    private val panelFields = setOf("handler", "longPressRunnable", "collapseChildrenRunnable",
        "expandedChildButtons", "closingChildButtons", "expandedGroupId", "expandedSourceButton", "expandedTile",
        "sensorExpansion", "expandedSensorView", "weatherForecastScroll", "isResizing", "resizeHandlers",
        "sensorWindowInput", "editDragHandlers", "panelEntityTiles", "panelEntityMotionSensor", "panelEntityIds")

    fun setField(target: Any, name: String, value: Any?) {
        if (target is MainActivity && name == "isEditMode") {
            MainActivity::class.java.getDeclaredMethod("setEditMode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(target, value)
        } else if (target is MainActivity && name in panelFields) {
            ReflectionHelpers.setField(panel(target), name, value)
        } else ReflectionHelpers.setField(target, name, value)
    }

    fun <T> getField(target: Any, name: String): T {
        if (target is MainActivity && name in setOf("singleStates", "groupStates", "entityAttributes", "syntheticForecasts")) {
            val owner = androidx.lifecycle.ViewModelProvider(target)[MainViewModel::class.java]
            return ReflectionHelpers.getField(owner.devices, name)
        }
        val receiver = if (target is MainActivity && name in panelFields) panel(target) else target
        if ((target is MainActivity && name == "isEditMode") || name in setOf("expandedSensorView", "sensorWindowInput", "weatherForecastScroll")) {
            val getter = if (name == "isEditMode") name else "get" + name.replaceFirstChar { it.uppercaseChar() }
            @Suppress("UNCHECKED_CAST")
            return receiver.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(receiver) as T
        }
        return ReflectionHelpers.getField(receiver, name)
    }

    class Method(private val receiver: Any, private val method: java.lang.reflect.Method) {
        val name get() = method.name
        val parameterTypes get() = method.parameterTypes
        var isAccessible: Boolean
            get() = method.isAccessible
            set(value) { method.isAccessible = value }
        fun invoke(ignored: Any, vararg arguments: Any?): Any? = method.invoke(receiver, *arguments)
    }

    fun methods(target: Any): List<Method> {
        val own = target.javaClass.declaredMethods.toList()
        val result = own.map { Method(target, it) }
        if (target !is MainActivity) return result
        val controller = panel(target)
        val panelMethods = controller.javaClass.declaredMethods.toList()
        val content = controller.javaClass.getDeclaredMethod("getContentController")
            .apply { isAccessible = true }.invoke(controller)
        val inherited = own + panelMethods
        return result + panelMethods.filter { candidate ->
            own.none { it.name == candidate.name && it.parameterTypes.size == candidate.parameterTypes.size }
        }.map { Method(controller, it) } + content.javaClass.declaredMethods.filter { candidate ->
            inherited.none { it.name == candidate.name && it.parameterTypes.size == candidate.parameterTypes.size }
        }.map { Method(content, it) }
    }

    fun method(target: Any, name: String, vararg types: Class<*>?): Method = methods(target).single {
        it.name == name && it.parameterTypes.toList() == types.toList()
    }
}
