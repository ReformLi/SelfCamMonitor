package com.hpu.selfcammonitor

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.util.Base64
import java.io.ByteArrayInputStream

class StreamServer(port: Int = 8080) : NanoHTTPD(port) {

    private lateinit var mjpegStreamer: MJPEGStreamer

    var isMjpegEnabled: Boolean = true   // 新增状态

    var username: String? = null
    var password: String? = null

    fun setMJPEGStreamer(streamer: MJPEGStreamer) {
        this.mjpegStreamer = streamer
    }

    override fun serve(session: IHTTPSession?): Response {
        // 增加 username 和 password 属性验证
        // 认证检查
        if (username != null && password != null) {
            val auth = session?.headers?.get("authorization")
            if (auth == null || !auth.startsWith("Basic ")) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "需要认证"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
            val cred = String(
                Base64.decode(auth.substring(6), Base64.DEFAULT)
            ).split(":")
            if (cred.size != 2 || cred[0] != username || cred[1] != password) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "认证失败"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
        }

        if (session?.uri == "/video") {
            // 检查推流开关
            if (!isMjpegEnabled) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "MJPEG 推流已关闭，请在 App 中开启。"
                )
            }

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut)

            mjpegStreamer.addClient(pipedOut)

            return newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=${MJPEGStreamer.BOUNDARY}",
                pipedIn
            )
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }
}