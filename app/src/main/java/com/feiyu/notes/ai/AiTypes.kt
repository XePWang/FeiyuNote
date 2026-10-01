package com.feiyu.notes.ai

import java.io.File

/** Provider-neutral request types; business code never sees DeepSeek DTOs. */
data class AiInput(val systemText: String, val messages: List<AiMessage>)

enum class AiRole { USER, ASSISTANT }

/** [images] are already-resolved local files; only USER messages may carry images. */
data class AiMessage(val role: AiRole, val text: String, val images: List<File> = emptyList()) {
    init {
        require(role == AiRole.USER || images.isEmpty()) { "Only user messages may carry images" }
    }
}

data class AiReply(val text: String)

data class AiConfig(
    val apiKey: String,
    val model: String = AiDefaults.MODEL,
    val endpoint: String = AiDefaults.ENDPOINT,
) {
    override fun toString(): String = "AiConfig(apiKey=<redacted>, model=$model, endpoint=$endpoint)"
}

/** Central defaults (spec §6). Endpoint is fixed; the model is user-editable. */
object AiDefaults {
    const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    const val MODEL = "deepseek-flash"
    /** Longest image edge sent to the model; originals stay on disk untouched. */
    const val IMAGE_MAX_EDGE = 1600
    const val JPEG_QUALITY = 85
}

/** Distinguishable failures shown to the user; none triggers an automatic retry. */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : AiError("未设置 DeepSeek API Key")
    class Auth(val code: Int) : AiError("认证失败（HTTP $code），请检查 API Key")
    class Quota(val code: Int) : AiError("额度不足或请求过于频繁（HTTP $code），请稍后重试")
    class TooLarge(code: Int, detail: String) : AiError("请求超出接口限制（HTTP $code）：$detail")
    class Server(val code: Int, detail: String) : AiError("服务返回错误（HTTP $code）：$detail")
    class Network(cause: Throwable) : AiError("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
    class EmptyAnswer : AiError("模型没有返回有效回答")
    class BadResponse(detail: String) : AiError("无法解析服务响应：$detail")
    class ImageUnreadable(name: String) : AiError("照片无法读取：$name")
}
