package io.ino.oss.quarkus

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.quarkus.logging.Log
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Channel
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@QuarkusTest
@TestProfile(SimpleTracingQuarkusTest::class)
class SimpleTracingQuarkusTest : StreamTracingQuarkusTestBase() {

    @Test
    fun `tracing with stream`() =
        testStreamTracing(testProducer::emitItem1, processedItems)

}

@QuarkusTest
@TestProfile(KeyedMultiTracingQuarkusTest::class)
class KeyedMultiTracingQuarkusTest : StreamTracingQuarkusTestBase() {

    @Test
    fun `tracing with KeyedMulti substream`() =
        testStreamTracing(testProducer::emitItem2, processedKeyedMultiItems)

}

@QuarkusTest
@TestProfile(GroupedTracingQuarkusTest::class)
class GroupedTracingQuarkusTest : StreamTracingQuarkusTestBase() {

    @Test
    fun `tracing with grouped substream`() =
        testStreamTracing(testProducer::emitItem3, processedGroupedItems)

}

@QuarkusTest
@TestProfile(GroupedMultiTracingQuarkusTest::class)
class GroupedMultiTracingQuarkusTest : StreamTracingQuarkusTestBase() {

    @Test
    fun `tracing with grouped multi substream`() =
        testStreamTracing(testProducer::emitItem4, processedGroupedMultiItems)

}

abstract class StreamTracingQuarkusTestBase() : QuarkusTestProfile {

    @Inject
    lateinit var testProducer: TestProducer

    @Channel("processed-items")
    lateinit var processedItems: Multi<Triple<String, String, String>>

    @Channel("processed-keyedMulti-items")
    lateinit var processedKeyedMultiItems: Multi<Triple<String, String, String>>

    @Channel("processed-grouped-items")
    lateinit var processedGroupedItems: Multi<Triple<String, String, String>>

    @Channel("processed-groupedMulti-items")
    lateinit var processedGroupedMultiItems: Multi<Triple<String, String, String>>

    fun testStreamTracing(emitItem: (String) -> Unit, processedItems: Multi<Triple<String, String, String>>) {

        val receivedItems = mutableListOf<Triple<String, String, String>>()

        processedItems.subscribe().with { item ->
            Log.infof("Test subscriber received item: %s", item)
            receivedItems.add(item)
        }

        emitItem("a1")
        emitItem("a2")

        blockingEventually {
            receivedItems shouldHaveSize 2
        }

        receivedItems.forAll { (_, originalTraceId, traceId) ->
            traceId shouldBe originalTraceId
        }

    }

    private fun <T> blockingEventually(duration: Duration = 15.seconds, test: () -> T): T = runBlocking {
        eventually(duration) {
            test()
        }
    }

}