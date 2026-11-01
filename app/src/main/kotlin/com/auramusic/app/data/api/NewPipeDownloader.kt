package com.auramusic.app.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor() : Downloader() {

    private val okhttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val reqBuilder = okhttp3.Request.Builder().url(request.url())

        // Copiar headers de NewPipe al request
        request.headers().forEach { (name, values) ->
            values.forEach { value -> reqBuilder.addHeader(name, value) }
        }
        if (reqBuilder.build().header("User-Agent") == null) {
            reqBuilder.header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
        }

        // Body para POST
        val dataToSend = request.dataToSend()
        val okBody = if (dataToSend != null && dataToSend.isNotEmpty())
            dataToSend.toRequestBody("application/json; charset=utf-8".toMediaType())
        else null

        val okRequest = when (request.httpMethod()) {
            "POST" -> reqBuilder.post(okBody
                ?: "".toRequestBody("application/json".toMediaType())).build()
            "HEAD" -> reqBuilder.head().build()
            else   -> reqBuilder.get().build()
        }

        val resp = okhttp.newCall(okRequest).execute()
        if (resp.code == 429) throw ReCaptchaException("Rate limited", request.url())

        // Construir headers de respuesta
        val respHeaders = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until resp.headers.size) {
            val name  = resp.headers.name(i)
            val value = resp.headers.value(i)
            respHeaders.getOrPut(name) { mutableListOf() }.add(value)
        }

        val body = resp.body?.string()
        val finalUrl = resp.request.url.toString()
        resp.close()

        return Response(resp.code, resp.message, respHeaders, body, finalUrl)
    }

    companion object {
        val instance by lazy { NewPipeDownloader() }
    }
}
