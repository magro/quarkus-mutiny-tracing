package io.ino.oss.quarkus

import io.kotest.assertions.nondeterministic.eventually
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun <T> blockingEventually(duration: Duration = 15.seconds, test: () -> T): T = runBlocking {
    eventually(duration) {
        test()
    }
}
