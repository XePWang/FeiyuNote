package com.feiyu.notes.support

import okhttp3.HttpUrl

/** The project's own support host (spec §9): update index, download page and feedback. Never GitHub. */
object SupportServer {
    const val HOST = "feiyunote.cangming.fyi"
    const val UPDATE_MANIFEST = "https://$HOST/updates/android.json"
    const val DOWNLOAD_PAGE = "https://$HOST/feiyu/"
    const val FEEDBACK = "https://$HOST/api/v1/feedback"

    /** Every URL we fetch or open, including each redirect hop, must pass this check. */
    fun isTrusted(url: HttpUrl): Boolean =
        url.isHttps && url.host == HOST && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
}
