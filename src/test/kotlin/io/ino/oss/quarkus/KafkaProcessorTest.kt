package io.ino.oss.quarkus

import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Future

@QuarkusTest
class KafkaProcessorTest {

    @Inject
    private lateinit var tracer: Tracer

    private val inputTopic: String = ConfigProvider.getConfig().getValue(
        "mp.messaging.incoming.input.topic",
        String::class.java
    )
    private val outputTopic: String = ConfigProvider.getConfig().getValue(
        "mp.messaging.outgoing.output.topic",
        String::class.java
    )

    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var consumer: KafkaConsumer<String, ProcessedItem>

    @BeforeEach
    fun setup() {
        producer = kafkaProducer()
        consumer = kafkaConsumer().apply {
            subscribe(listOf(outputTopic))
        }
    }

    @Test
    fun `test KafkaProcessor`() {
        // Send test messages to the input topic with tracing headers
        val (_, traceId1) = publish("a", "a1")
        val (_, traceId2) = publish("b", "b1")
        val (_, traceId3) = publish("b", "b2")

        // Consume processed items from output topic with their Kafka record traceIds
        val itemsWithTraceIds = consumer.collectItems(3)

        // Verify items
        val processedItems = itemsWithTraceIds.map { it.first }
        processedItems.map { it.item } shouldBe listOf("a1", "b1", "b2")

        // Verify that all metadata traceIds and Kafka record traceIds match the original ones
        itemsWithTraceIds.forAll { (item, kafkaRecordTraceId) ->
            val expectedTraceId = when (item.item) {
                "a1" -> traceId1
                "b1" -> traceId2
                "b2" -> traceId3
                else -> throw IllegalArgumentException("Unknown item $item")
            }

            // Verify the Kafka record traceId matches the original
            kafkaRecordTraceId shouldBe expectedTraceId

            // All traceId pairs should have the same metadata traceId (first element of pair)
            item.traceIds.forAll { (metadataTraceId, contextTraceId) ->
                metadataTraceId shouldBe expectedTraceId
                contextTraceId shouldBe expectedTraceId
            }
        }
    }

    private fun publish(key: String, value: String): Pair<Future<RecordMetadata>, String> {
        val record = ProducerRecord(inputTopic, key, value)

        // Create a new span for this message
        val span = tracer.spanBuilder("kafka-send")
            .setSpanKind(SpanKind.PRODUCER)
            .startSpan()

        val traceId = span.spanContext.traceId

        // Inject trace context into Kafka headers
        val context = Context.current().with(span)
        GlobalOpenTelemetry.getPropagators().textMapPropagator
            .inject(context, record.headers(), HeadersSetter)

        span.end()

        return producer.send(record) to traceId
    }
}

// TextMapSetter for injecting trace context into Kafka headers
private object HeadersSetter : TextMapSetter<Headers> {
    override fun set(carrier: Headers?, key: String, value: String) {
        carrier?.add(key, value.toByteArray())
    }
}
