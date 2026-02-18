package io.ino.oss.quarkus

import io.kotest.matchers.collections.shouldHaveSize
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Duration
import java.util.*

fun bootstrapServers(): String {
    return ConfigProvider.getConfig()
        .getValue("kafka.bootstrap.servers", String::class.java)
}

fun kafkaProducer(bootstrapServers: String = bootstrapServers()): KafkaProducer<String, String> {
    val config = Properties()
    config[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    config[ProducerConfig.CLIENT_ID_CONFIG] = "test-producer-${UUID.randomUUID()}"
    config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.canonicalName
    config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] =
        StringSerializer::class.java.canonicalName
    return KafkaProducer(config)
}

class ProcessedItemDeserializer : ObjectMapperDeserializer<ProcessedItem>(ProcessedItem::class.java)

fun kafkaConsumer(bootstrapServers: String = bootstrapServers()): KafkaConsumer<String, ProcessedItem> {
    val consumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer")
        put(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer::class.java.canonicalName
        )
        put(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            ProcessedItemDeserializer::class.java.canonicalName
        )
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    }
    return KafkaConsumer(consumerProps)
}

fun KafkaConsumer<String, ProcessedItem>.collectItems(count: Int): List<Pair<ProcessedItem, TraceId>> {
    var collectedItems = listOf<Pair<ProcessedItem, TraceId>>()
    return blockingEventually {
        val records = poll(Duration.ofSeconds(10))
        collectedItems = collectedItems + records.map { record ->
            val traceId = record.extractTraceId()
            record.value() to traceId
        }
        collectedItems shouldHaveSize count
        collectedItems
    }
}

/**
 * Extracts the TraceId from the Kafka record headers.
 * Supports W3C traceparent format: "00-<trace-id>-<span-id>-<flags>"
 */
private fun ConsumerRecord<*, *>.extractTraceId(): TraceId {
    val traceparent = headers().lastHeader("traceparent")?.value()?.toString(Charsets.UTF_8)
    return if (traceparent != null) {
        // Parse W3C traceparent: "00-<trace-id>-<span-id>-<flags>"
        val parts = traceparent.split("-")
        if (parts.size >= 3) {
            parts[1] // trace-id is the second part
        } else {
            "invalid-traceparent"
        }
    } else {
        "no-traceparent-header"
    }
}
