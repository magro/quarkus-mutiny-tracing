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

The `SimpleTracingQuarkusTest` shows that "simple" trace propagation works for a `Multi`,
so that the trace context attached to each item is as expected - and changed with the next item
accordingly.

The other tests like `GroupedTracingQuarkusTest` show that when the incoming items are grouped
somehow (into substreams), the trace context of the first item of the substream is used for
the subsequent items in the same substream.

E.g. the output for `GroupedTracingQuarkusTest` is
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