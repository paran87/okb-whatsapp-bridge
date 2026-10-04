package com.okb.whatsappbridge.whatsapp

/** WhatsApp application packages the bridge monitors. Any other package is ignored. */
object WhatsAppPackages {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    val ALL: Set<String> = setOf(WHATSAPP, WHATSAPP_BUSINESS)

    fun isWhatsApp(packageName: String?): Boolean = packageName != null && packageName in ALL

    fun displayName(packageName: String): String = when (packageName) {
        WHATSAPP -> "WhatsApp"
        WHATSAPP_BUSINESS -> "WhatsApp Business"
        else -> packageName
    }
}
