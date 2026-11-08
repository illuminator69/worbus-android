package app.worbus.util

import app.worbus.generated.BuildInfo

/**
 * Worbus P5 (design §6.2, §6.7): how this app names itself to the servers it talks to. Navidrome's
 * Players list shows [CLIENT_NAME], so Worbus for Android and Worbus for desktop are told apart.
 */
const val CLIENT_NAME = "Worbus-Android"

/** `Worbus-Android/0.7.0`, the one User-Agent every HTTP client of this app sends. */
fun userAgent(): String = "$CLIENT_NAME/${BuildInfo.VERSION_NAME}"

/** The name a device has until the user types one: the phone's model, else `Worbus`. */
fun defaultDeviceName(model: String?): String = model?.trim()?.ifEmpty { null } ?: "Worbus"

/** The name sent to the Worbus Server: the one the user saved, else [defaultDeviceName]. A saved name is never replaced. */
fun deviceName(saved: String, model: String?): String = saved.trim().ifEmpty { defaultDeviceName(model) }
