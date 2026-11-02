package com.example.smarthomedashboard

import android.content.Intent
import com.example.smarthomedashboard.data.TileEntity
import com.google.gson.Gson

/**
 * Черновик плитки между редактором и вложенными экранами («Расширенные», «Устройства группы»).
 * Вложенный экран правит копию и возвращает её; в хранилище пишет только «Сохранить» главного редактора,
 * поэтому «Отмена» откатывает все правки сеанса.
 */
internal object TileDraft {
    const val EXTRA = "tile_draft"
    private val gson = Gson()

    fun encode(tile: TileEntity): String = gson.toJson(tile)

    fun decode(json: String?): TileEntity? =
        json?.let { runCatching { gson.fromJson(it, TileEntity::class.java) }.getOrNull() }

    fun from(intent: Intent?): TileEntity? = decode(intent?.getStringExtra(EXTRA))

    fun result(tile: TileEntity): Intent = Intent().putExtra(EXTRA, encode(tile))
}
