package io.ino.oss.quarkus

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.reactive.messaging.providers.helpers.DefaultKeyedMulti
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing

@ApplicationScoped
class TestProcessor() {

    // Tracing works as expected
    @Incoming("items1")
    @Outgoing("processed-items")
    fun processItems(items: Multi<String>): Multi<Triple<String, String, String>> {
        return items
            .map { item ->
                Log.infof("Processing item: %s", item)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                item to traceId
            }
            .map { (item, originalTraceId) ->
                Log.infof("Processing item: %s with traceId: %s", item, originalTraceId)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                Triple(item, originalTraceId, traceId)
            }
    }

    // Tracing broken for the 2nd element of the substream
    @Incoming("items2")
    @Outgoing("processed-keyedMulti-items")
    fun processItemsByKey(items: Multi<String>): Multi<Triple<String, String, String>> {
        return items
            .map { item ->
                Log.infof("Processing item: %s", item)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                item to traceId
            }
            .group().by { it.first[0] }
            .map { DefaultKeyedMulti(it.key(), it) }
            .flatMap { keyedStream ->
                Log.infof("Processing keyedStream for key %s", keyedStream.key())
                keyedStream
                    .onItem().transform { (item, originalTraceId) ->
                        // The traceId for the 2nd item in each keyed substream is wrong (it's the traceId of the first item)
                        Log.infof("Keyed processing item: %s with traceId: %s", item, originalTraceId)
                        val traceId = Span.fromContext(Context.current()).spanContext.traceId
                        Triple(item, originalTraceId, traceId)
                    }
            }
    }

    // Tracing broken for the 2nd element of the substream
    @Incoming("items3")
    @Outgoing("processed-grouped-items")
    fun processItemsFlat(items: Multi<String>): Multi<Triple<String, String, String>> {
        return items
            .map { item ->
                Log.infof("Processing item: %s", item)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                item to traceId
            }
            .group().by { it.first[0] }
            .flatMap {
                Log.infof("Processing substream for group %s", it.key())
                it.onItem().transform { (item, originalTraceId) ->
                    // The traceId for the 2nd item in each keyed substream is wrong (it's the traceId of the first item)
                    Log.infof("Processing flat item: %s with traceId: %s", item, originalTraceId)
                    val traceId = Span.fromContext(Context.current()).spanContext.traceId
                    Triple(item, originalTraceId, traceId)
                }
            }
    }

    // Tracing broken for the 2nd element of the substream
    @Incoming("items4")
    @Outgoing("processed-groupedMulti-items")
    fun processItemsGrouped(items: Multi<String>): Multi<Triple<String, String, String>> {
        return items
            .map { item ->
                Log.infof("Processing item: %s", item)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                item to traceId
            }
            // if multi size is changed to 1 traceIds are as expecxted
            .group().intoMultis().of(2)
            .flatMap {
                Log.infof("Processing substream for multi %s", it.group())
                it.onItem().transform { (item, originalTraceId) ->
                    // The traceId for the 2nd item in each keyed substream is wrong (it's the traceId of the first item)
                    Log.infof("Processing multi item: %s with traceId: %s", item, originalTraceId)
                    val traceId = Span.fromContext(Context.current()).spanContext.traceId
                    Triple(item, originalTraceId, traceId)
                }
            }
    }

}