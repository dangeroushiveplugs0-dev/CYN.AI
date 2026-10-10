package com.cyn.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class LocalModel(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
) {
    val sizeLabel: String
        get() = when {
            sizeBytes <= 0L -> "Size unknown"
            sizeBytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024L * 1024L -> String.format("%.0f MB", sizeBytes / (1024.0 * 1024.0))
            else -> String.format("%.0f KB", sizeBytes / 1024.0)
        }
}

class ModelLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("cyn_model_library", Context.MODE_PRIVATE)

    fun list(): List<LocalModel> {
        val saved = preferences.getString("models", "[]") ?: "[]"
        val array = runCatching { JSONArray(saved) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = runCatching { Uri.parse(item.optString("uri")) }.getOrNull() ?: continue
                if (uri.scheme != "content") continue
                add(
                    LocalModel(
                        uri = uri,
                        name = item.optString("name", "GGUF model"),
                        sizeBytes = item.optLong("sizeBytes", 0L)
                    )
                )
            }
        }
    }

    fun import(uri: Uri): LocalModel {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val metadata = queryMetadata(uri)
        val imported = LocalModel(uri, metadata.first, metadata.second)
        val models = list().filterNot { it.uri == uri } + imported
        save(models)
        return imported
    }

    fun remove(model: LocalModel) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                model.uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        save(list().filterNot { it.uri == model.uri })
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "GGUF model"
        var size = 0L
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun save(models: List<LocalModel>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("uri", model.uri.toString())
                    .put("name", model.name)
                    .put("sizeBytes", model.sizeBytes)
            )
        }
        preferences.edit().putString("models", array.toString()).apply()
    }
}
