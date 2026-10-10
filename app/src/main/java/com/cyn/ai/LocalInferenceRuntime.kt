package com.cyn.ai

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * CYN's narrow adapter around llama.cpp's Android JNI inference engine.
 * SAF model URIs are staged in app-private storage because the native engine
 * loads GGUF files by filesystem path.
 */
class LocalInferenceRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private var loadedPath: String? = null

    suspend fun loadModel(model: LocalModel) = withContext(Dispatchers.IO) {
        val modelDir = File(appContext.filesDir, "models").apply { mkdirs() }
        val safeName = model.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelDir, safeName)

        if (!destination.exists() || (model.sizeBytes > 0L && destination.length() != model.sizeBytes)) {
            val required = if (model.sizeBytes > 0L) model.sizeBytes else 1L
            if (modelDir.usableSpace < required + 128L * 1024L * 1024L) {
                throw IOException("Not enough free storage to stage this model. Free space and try again.")
            }
            appContext.contentResolver.openInputStream(model.uri)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IOException("Android could not open the selected model file.")
            if (destination.length() < 8L || (model.sizeBytes > 0L && destination.length() != model.sizeBytes)) {
                destination.delete()
                throw IOException("The copied file is incomplete or is not a valid GGUF-sized file.")
            }
        }

        if (loadedPath == destination.absolutePath && engine.state.value is InferenceEngine.State.ModelReady) {
            return@withContext
        }

        when (engine.state.value) {
            is InferenceEngine.State.ModelReady -> engine.cleanUp()
            is InferenceEngine.State.Error -> engine.cleanUp()
            else -> Unit
        }

        engine.loadModel(destination.absolutePath)
        engine.setSystemPrompt(
            "You are CYN, a concise and capable local coding assistant. Help with programming, " +
                "debugging, software design, and supplied file context. Be honest about uncertainty. " +
                "Only use attachment contents actually included in the prompt; do not claim to have " +
                "visually inspected files whose image data was not provided."
        )
        loadedPath = destination.absolutePath
    }

    fun generate(prompt: String): Flow<String> =
        engine.sendUserPrompt(prompt, predictLength = 768)
}
