package io.ino.oss.quarkus

import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class TestProducer() {

    @Channel("items1")
    lateinit var emitter1: Emitter<String>

    @Channel("items2")
    lateinit var emitter2: Emitter<String>

    @Channel("items3")
    lateinit var emitter3: Emitter<String>

    @Channel("items4")
    lateinit var emitter4: Emitter<String>

    @WithSpan
    fun emitItem1(item: String) {
        emitter1.send(item)
    }

    @WithSpan
    fun emitItem2(item: String) {
        emitter2.send(item)
    }

    @WithSpan
    fun emitItem3(item: String) {
        emitter3.send(item)
    }

    @WithSpan
    fun emitItem4(item: String) {
        emitter4.send(item)
    }

}