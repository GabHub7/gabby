package com.gabby.assistant

/** role: "user" atau "assistant". image: JPEG (opsional, mis. hasil scan layar). */
class Msg(val role: String, val text: String, val image: ByteArray? = null)
