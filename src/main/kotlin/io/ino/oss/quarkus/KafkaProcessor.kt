package io.ino.oss.quarkus

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.TracingMetadata
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.keyed.KeyedMulti
import io.smallrye.reactive.messaging.providers.helpers.DefaultKeyedMulti
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Outgoing
import kotlin.jvm.optionals.getOrNull

typealias Key = String
typealias Value = String
typealias TraceId = String

data class ProcessedItem(val item: Value, val traceIds: List<Pair<TraceId, TraceId>>) {

    fun withTraceId(metadataTraceId: TraceId, contextTraceId: TraceId): ProcessedItem =
        copy(traceIds = traceIds + (metadataTraceId to contextTraceId)).also {
            Log.infov(
                "Added traceIds (metadata={0}, context={1}) for item {2}",
                metadataTraceId,
                contextTraceId,
                item
            )
        }
}

@ApplicationScoped
class KafkaProcessor(
    @param:Channel("output")
    private val emitter: MutinyEmitter<ProcessedItem>
) {

    @Incoming("input")
    @Outgoing("resultToPublish")
    fun consume(msgs: Multi<Message<Value>>): Multi<Message<ProcessedItem>> =
        msgs
            .map { msg ->
                val metadataTraceId = msg.metadataTraceId()
                val contextTraceId = currentContextTraceId()
                val result = ProcessedItem(
                    item = msg.payload,
                    traceIds = listOf(metadataTraceId to contextTraceId)
                )
                msg.withPayload(result)
            }
            .group().by { it.metadata[IncomingKafkaRecordMetadata::class.java].get().key as Key }
            .map { DefaultKeyedMulti(it.key(), it) }
            .flatMap { keyedStream ->
                processKeyedStream(keyedStream)
            }

    private fun processKeyedStream(
        keyedStream: KeyedMulti<Key, Message<ProcessedItem>>
    ): Multi<Message<ProcessedItem>> = keyedStream.map { msg ->
        msg.tracingMetadata()?.currentContext?.makeCurrent()
            ?: throw IllegalArgumentException("Missing tracing metadata for item ${msg.payload.item}")
        // Here the contextTraceId is wrong, it doesn't match the metadataTraceId
        msg.captureTraceIds()
    }

    @Incoming("resultToPublish")
    fun publish(msg: Message<ProcessedItem>): Uni<Void> {
        val processedItem = msg.captureTraceIds().payload
        return emitter.send(processedItem)
    }
}

private fun Message<ProcessedItem>.captureTraceIds(): Message<ProcessedItem> {
    val metadataTraceId = metadataTraceId()
    val contextTraceId = currentContextTraceId()
    return withPayload(payload.withTraceId(metadataTraceId, contextTraceId))
}

private fun Message<*>.metadataTraceId(): TraceId =
    tracingMetadata()?.currentContext?.let {
        Span.fromContext(it).spanContext.traceId
    } ?: "no-trace-id"

private fun currentContextTraceId(): TraceId =
    Span.fromContext(Context.current()).spanContext.traceId
        .takeIf { it.isNotEmpty() } ?: "no-context-trace-id"

private fun Message<*>.tracingMetadata(): TracingMetadata? {
    val tracing = metadata[TracingMetadata::class.java].getOrNull()
    if (tracing == null) {
        logMissingTracingMetadata()
    }
    return tracing
}

private fun Message<*>.logMissingTracingMetadata() =
    metadata[IncomingKafkaRecordMetadata::class.java].getOrNull()?.let {
        Log.warnv("Missing tracing metadata for key {0}", it.key)
    } ?: Log.warnv("Missing tracing metadata for message with payload {0}", payload)
