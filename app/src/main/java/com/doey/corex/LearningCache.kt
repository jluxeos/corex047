package com.doey.corex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LearningCache(context: Context) {

    private val file = File(context.filesDir, "learning.json")

    data class LearnedAction(
        val key: String,
        val packageName: String,
        val elementIndex: Int,
        val elementText: String,
        val x: Float,
        val y: Float,
        val uses: Int = 1
    )

    fun learn(key: String, pkg: String, index: Int, text: String, x: Float, y: Float) {
        val all = loadAll().toMutableList()
        val existing = all.indexOfFirst { it.key == key && it.packageName == pkg }
        if (existing >= 0) {
            all[existing] = all[existing].copy(
                elementIndex = index, elementText = text, x = x, y = y,
                uses = all[existing].uses + 1
            )
        } else {
            all.add(LearnedAction(key, pkg, index, text, x, y))
        }
        persist(all)
    }

    fun find(key: String, pkg: String): LearnedAction? {
        return loadAll().firstOrNull {
            it.key.equals(key, ignoreCase = true) && it.packageName == pkg
        }
    }

    fun getAll(): List<LearnedAction> = loadAll()

    fun forget(key: String, pkg: String) {
        persist(loadAll().filter { !(it.key == key && it.packageName == pkg) })
    }

    fun clear() { if (file.exists()) file.delete() }

    fun getSummary(): String {
        val all = loadAll()
        if (all.isEmpty()) return ""
        return all.joinToString("; ") { "${it.key}@${it.packageName.substringAfterLast('.')}=${it.elementText}" }
    }

    private fun loadAll(): List<LearnedAction> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LearnedAction(
                    key = o.getString("key"),
                    packageName = o.getString("pkg"),
                    elementIndex = o.getInt("idx"),
                    elementText = o.getString("txt"),
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    uses = o.optInt("uses", 1)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun persist(list: List<LearnedAction>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("key", a.key)
                put("pkg", a.packageName)
                put("idx", a.elementIndex)
                put("txt", a.elementText)
                put("x", a.x)
                put("y", a.y)
                put("uses", a.uses)
            })
        }
        file.writeText(arr.toString())
    }
}
