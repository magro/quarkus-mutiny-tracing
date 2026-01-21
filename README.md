# Grouped Multi Trace Propagation Reproducer

This project reproduces an issue with Quarkus/Mutiny trace propagation with grouped `Multi`.

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