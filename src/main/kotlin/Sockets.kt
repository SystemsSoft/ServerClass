package com.class_erp

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 60_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}
