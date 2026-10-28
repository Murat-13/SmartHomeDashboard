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

    fun setField(target: Any, name: String, value: Any?) {
        if (target is MainActivity && name == "isEditMode") {
            MainActivity::class.java.getDeclaredMethod("setEditMode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(target, value)
        } else ReflectionHelpers.setField(target, name, value)
    }

    fun <T> getField(target: Any, name: String): T {
        if (target is MainActivity && name in setOf("singleStates", "groupStates", "entityAttributes", "syntheticForecasts", "isEditMode")) {
            val getter = if (name == "isEditMode") name else "get" + name.replaceFirstChar { it.uppercaseChar() }
            @Suppress("UNCHECKED_CAST")
            return MainActivity::class.java.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(target) as T
        }
        return ReflectionHelpers.getField(target, name)
    }
}
