package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity

/** Размер ограничен текущими плитками. Не хранит Views, Context или историю прежних конфигураций. */
internal class PanelDependencyIndex(tiles: List<TileEntity>) {
    private val byEntity = mutableMapOf<String, MutableList<TileEntity>>()
    private val fallback = mutableListOf<TileEntity>()
    val entityIds: Set<String> get() = byEntity.keys

    init {
        tiles.forEach { tile ->
            val dependencies = PanelEntityIds.describe(tile)
            dependencies.ids.forEach { id -> byEntity.getOrPut(id) { mutableListOf() }.add(tile) }
            if (dependencies.malformed) fallback.add(tile)
        }
    }

    fun affected(entityIds: Collection<String>): List<TileEntity> {
        val result = linkedMapOf<String, TileEntity>()
        entityIds.forEach { id -> byEntity[id]?.forEach { result[it.id] = it } }
        // При повреждении пересчитывается только сомнительная карточка, не вся панель.
        fallback.forEach { result[it.id] = it }
        return result.values.toList()
    }
}
