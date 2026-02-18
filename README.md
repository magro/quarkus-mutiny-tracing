# Grouped Multi Trace Propagation Reproducer

This project reproduces an issue with Quarkus/Mutiny trace propagation with grouped `Multi`:
for a KeyedMulti (or GroupedMulti in general) the traceId / trace context is wrong for all elements after the first one of a given substream.

With the following example:
```kotlin
@Incoming("items")
@Outgoing("processed-items")
fun processItems(items: Multi<String>): Multi<Triple<String, String, String>> {
    return items
        .map { item ->
            val traceId = Span.fromContext(Context.current()).spanContext.traceId
            item to traceId
        }
        .group().by { it.first[0] }
        .flatMap {
            it.onItem().transform { (item, originalTraceId) ->
                // The traceId for the 2nd item in each substream is wrong (it's the traceId of the first item)
                val traceId = Span.fromContext(Context.current()).spanContext.traceId
                Triple(item, originalTraceId, traceId)
            }
        }
}
```
* for input items "a1" and "a2"
* with traceIds "t1" (for "a1") and "t2" (for "a2"),
* the outgoing/processed results are `Triple("a1", "t1", "t1")`  and `Triple("a2", "t2", "t1")`,
* while for the latter `Triple("a2", "t2", "t2")` would be expected.

## About the test code

### StreamTracingQuarkusTest

The `StreamTracingQuarkusTest` shows that "simple" trace propagation works for a `Multi`,
so that the trace context attached to each item is as expected - and changed with the next item
accordingly.

When the incoming items are grouped (into substreams), the trace context of the first item of the substream is used for
the subsequent items in the same substream.

E.g. the output for a grouped test is:
```
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.TestProcessor] (main) Processing item: a1
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.TestProcessor] (main) Processing substream for group a
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.TestProcessor] (main) Processing flat item: a1 with traceId: df64da8adff8f1f6d046f3edd70aa47a
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.StreamTracingQuarkusTestBase] (main) Test subscriber received item: (a1, df64da8adff8f1f6d046f3edd70aa47a, df64da8adff8f1f6d046f3edd70aa47a)
16:35:47 INFO  traceId=79196acd7a07d99ffd6021f381b231af, spanId=30964b5dca37c964 [io.in.os.qu.TestProcessor] (main) Processing item: a2
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.TestProcessor] (main) Processing flat item: a2 with traceId: 79196acd7a07d99ffd6021f381b231af
16:35:47 INFO  traceId=df64da8adff8f1f6d046f3edd70aa47a, spanId=0e36d4cbd280619e [io.in.os.qu.StreamTracingQuarkusTestBase] (main) Test subscriber received item: (a2, 79196acd7a07d99ffd6021f381b231af, df64da8adff8f1f6d046f3edd70aa47a)
```
where for item "a2" the incoming traceId is *79196acd7a07d99ffd6021f381b231af*, which is then changed to 
*df64da8adff8f1f6d046f3edd70aa47a* (which is the traceId of the previous item "a1")

The test then fails, because the traceId captured within the flatMap onItem processor is not the original
one that was captured for the item earlier.


### KafkaProcessorTest

The `KafkaProcessorTest` is an integration test using real Kafka (via Quarkus Dev Services) that demonstrates the trace propagation issue with grouped Multi streams.

The test verifies trace propagation at multiple points:
1. **Input**: TraceIds are injected into Kafka message headers (W3C traceparent format) by the test producer
2. **Processing stages**: The processor captures traceIds at three stages:
    - Initial `consume()` - when messages are first received
    - `processKeyedStream()` - after grouping by key
    - `publish()` - before sending to output topic

   At each stage, two traceIds are captured:
    - **Metadata traceId**: from `Message.TracingMetadata`
    - **Context traceId**: from the current `Context.current()`
3. **Output**: TraceId is extracted from the Kafka output record headers

**Expected behavior**: All traceIds should match the original input traceId for each message.

**Actual behavior**: For the second message in a grouped substream (same key), the context traceId becomes `00000000000000000000000000000000` in the `processKeyedStream()` stage, while the metadata traceId remains correct.

Example failure output:
```
The following elements failed:
  [2] (ProcessedItem(item=b2, traceIds=[(f2603eaebec04e8c993435a643de1d2c, f2603eaebec04e8c993435a643de1d2c), (f2603eaebec04e8c993435a643de1d2c, 00000000000000000000000000000000), (f2603eaebec04e8c993435a643de1d2c, f2603eaebec04e8c993435a643de1d2c)]), f2603eaebec04e8c993435a643de1d2c) 
  => expected:<f2603eaebec04e8c993435a643de1d2c> but was:<00000000000000000000000000000000>
```

This shows that for item "b2" (second item with key "b"), the context traceId at position [1] (processKeyedStream stage) is invalid, even though the TracingMetadata contains the correct traceId. And this happens even after setting the current context from the TracingMetadata, which should be a workaround for the basic issue - so even the workaround doesn't work.
