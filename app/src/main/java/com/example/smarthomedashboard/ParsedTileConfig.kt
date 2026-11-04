package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity

/**
 * Разобранные настройки плиток одного вида. Строка `config` меняется только при сохранении редактора,
 * а события HA приходят каждую секунду: JSON разбирается один раз на версию настроек плитки, а не на
 * каждое событие. Сравнение сначала по ссылке (TileManager отдаёт те же экземпляры), затем по содержимому.
 *
 * Только главный поток. [parse] должен возвращать неизменяемую модель — она отдаётся всем вызывающим.
 */
internal class ParsedTileConfig<T>(private val parse: (String) -> T) {
    private class Entry<T>(val source: String, val value: T)
    private val byTile = HashMap<String, Entry<T>>()

    fun of(tile: TileEntity): T {
        val config = tile.config
        byTile[tile.id]?.let { if (it.source === config || it.source == config) return it.value }
        return parse(config).also { byTile[tile.id] = Entry(config, it) }
    }

    /** Панель перестроена: удалённые плитки не держат свои настройки. */
    fun clear() = byTile.clear()
}
