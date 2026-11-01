package com.auramusic.app.util

// En release se conservan Log.w/Log.e, y los mensajes de error de Ktor, OkHttp y
// ExoPlayer pueden traer la URL completa (las de googlevideo, con parámetros
// firmados). En el registro solo queda el host: "https://host/…".
private val URL = Regex("(https?://[^/\\s?#]+)\\S*")

fun String?.sinUrls(): String? = this?.replace(URL, "$1/…")
