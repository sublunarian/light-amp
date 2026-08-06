package com.thelightphone.sdk.emulator.http

import android.content.Context
import android.util.Log
import com.thelightphone.sdk.server.BuildConfig
import com.thelightphone.sdk.server.LightPushDistributor
import com.thelightphone.sdk.server.LightPushRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

class EmulatorHttpServer(private val context: Context) {

    companion object {
        private const val TAG = "EmulatorHttpServer"
        private const val PORT = 8090
    }

    private val server = embeddedServer(Netty, port = PORT) {
        install(ContentNegotiation) { json() }

        routing {
            get("/version") {
                call.respondText(BuildConfig.SDK_VERSION, status = HttpStatusCode.OK)
            }
            post("/push/{token}") {
                val token = call.parameters["token"]
                if (token == null) {
                    call.respondText("Missing token", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val registration = LightPushRegistry.getByToken(context, token)
                if (registration == null) {
                    call.respondText("Unknown token", status = HttpStatusCode.NotFound)
                    return@post
                }
                val body = call.receiveText()
                Log.d(TAG, "Received push for ${registration.packageName}: $body")
                LightPushDistributor.sendMessage(
                    context,
                    registration.packageName,
                    token,
                    body.toByteArray()
                )
                call.respondText("OK", status = HttpStatusCode.OK)
            }
        }
    }

    fun start() {
        server.start(wait = false)
        Log.i(TAG, "HTTP server started on port $PORT")
    }

    fun stop() {
        server.stop(1000, 2000)
        Log.i(TAG, "HTTP server stopped")
    }
}
