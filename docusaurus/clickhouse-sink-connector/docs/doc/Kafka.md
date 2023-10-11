20

Getting data into and out of Kafka correctly can be challenging, and Kafka Connect makes this easier since it uses best practices and hides many of the complexities. For sink connectors, Kafka Connect reads messages from a topic, sends them to your connector, and then periodically commits the largest offsets for the various topic partitions that have been read and processed.

Note that "sending them to your connector" corresponds to the put(Collection) method, and this may be called many times before Kafka Connect commits the offsets. You can control how frequently Kafka Connect commits offsets, but Kafka Connect ensures that it will only commit an offset for a message when that message was successfully processed by the connector.

When the connector is operating nominally, everything is great and your connector sees each message once, even when the offsets are committed periodically. However, should the connector fail, then when it restarts the connector will start at the last committed offset. That might mean your connector sees some of the same messages that it processed just before the crash. This usually is not a problem if you carefully write your connector to have at least once semantics.

Why does Kafka Connect commit offsets periodically rather than with every record? Because it saves a lot of work and doesn't really matter when things are going nominally. It's only when things go wrong that the offset lag matters. And even then, if you're having Kafka Connect handle offsets your connector needs to be ready to handle messages at least once. Exactly once is possible, but your connector has to do more work (see below).

Writing Records

You have a lot of flexibility in writing a connector, and that's good because a lot will depend on the capabilities of the external system to which it's writing. Let's look at different ways of implementing put and flush.

If the system supports transactions or can handle a batch of updates, your connector's put(Collection) could write all of the records in that collection using a single transaction / batch, retrying as many times as needed until the transaction / batch completes or before finally throwing an error. In this case, put does all the work and will either succeed or will fail. If it succeeds, then Kafka Connect knows all of the records were handled properly and can thus (at some point) commit the offsets. If your put call fails, then Kafka Connect assumes doesn't know whether any of the records were processed, so it doesn't update its offsets and it stops your connector. Your connector's flush(...) would need to do nothing, since Kafka Connect is handling all the offsets.

If the system doesn't support transactions and instead you can only submit items one at a time, you might have have your connector's put(Collection) attempt to write out each record individually, blocking until it succeeds and retrying each as needed before throwing an error. Again, put does all the work, and the flush method might not need to do anything.

So far, my examples do all the work in put. You always have the option of having put simply buffer the records and to instead do all the work of writing to the external service in flush or preCommit. One reason you might do this is so that you're writes are time-based just like flush and preCommit. If you don't want your writes to be time-based, you probably don't want to do the writes in flush or preCommit.

To Record Offsets or Not To Record

As mentioned above, by default Kafka Connect will periodically record the offsets so that upon restart the connector can begin where it last left off.

However, sometimes it is desirable for a connector to record the offsets in the external system, especially when that can be done atomically. When such a connector starts up, it can look in the external system to find out the offset that was last written, and can then tell Kafka Connect where it wants to start reading. With this approach your connector may be able to do exactly once processing of messages.

When sink connectors do this, they actually don't need Kafka Connect to commit any offsets at all. The flush method is simply an opportunity for your connector to know which offsets that Kafka Connect is committing for you, and since it doesn't return anything it can't modify those offsets or tell Kafka Connect which offsets the connector is handling.

This is where the preCommit method comes in. It really is a replacement for flush (it actually takes the same parameters as flush), except that it is expected to return the offsets that Kafka Connect should commit. By default, preCommit just calls flush and then returns the same offsets that were passed to preCommit, which means Kafka Connect should commit all the offsets it passed to the connector via preCommit. But if your preCommit returns an empty set of offsets, then Kafka Connect will record no offsets at all.

So, if your connector is going to handle all offsets in the external system and doesn't need Kafka Connect to record anything, then you should override the preCommit method instead of flush, and return an empty set of offsets.

The issue here was my lack of understanding of how Kafka works. Basically the offset progress is being tracked in memory and the flush/preCommit is just for taking what's in memory and write to disk.

That means flushing an older offset will be taken only the next time the consumer will be restarted (as it reads the offset it needs to start from, from disk).

There is another method inside the SinkTaskContext called "offset" that takes a map of topic partition and offset and setting it in memory for the consumer.

The KafkaConnect runtime, before each poll, is taking the tracked offset in memory and if needed, calls the underline consumer seek method according to the map stored by the offset method mentioned before.

This, in turn, will actually make the consumer to read the offset as it was set in the "offset" method.

Sink Kafka Connector-Commit

If the option(enable.auto.commit) is False, automatically commit every 60 seconds according to the option(offset.flush.interval.ms) below. and if there is no error in your put() method, it will be committed normally.

offset.flush.interval.ms
Interval at which to try committing offsets for tasks.

Type: long
Default: 60000
Importance: low
To manage offset in Sink Kafka

Kafka Connect should commit all the offsets it passed to the connector via preCommit. But if your preCommit returns an empty set of offsets, then Kafka Connect will record no offsets at all. enter link description here

SinkTask.java

/**
* Pre-commit hook invoked prior to an offset commit.
*
* The default implementation simply invokes {@link #flush(Map)} and is thus able to assume all {@code currentOffsets} are committable.
*
* @param currentOffsets the current offset state as of the last call to {@link #put(Collection)}},
*                       provided for convenience but could also be determined by tracking all offsets included in the {@link SinkRecord}s
*                       passed to {@link #put}.
*
* @return an empty map if Connect-managed offset commits are not desired, otherwise a map of committable offsets by topic-partition.
  */
  public Map<TopicPartition, OffsetAndMetadata> preCommit(Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
  flush(currentOffsets);
  return currentOffsets;
  }
  or

SinkTaskContext.java

/**
* Request an offset commit. Sink tasks can use this to minimize the potential for redelivery
* by requesting an offset commit as soon as they flush data to the destination system.
* 
* This is a hint to the runtime and no timing guarantee should be assumed.
  */
  void requestCommit();
  void offset(Map<TopicPartition,Long> offsets)
  Reset the consumer offsets for the given topic partitions. SinkTasks should use this if they manage offsets in the sink data store rather than using Kafka consumer offsets. For example, an HDFS connector might record offsets in HDFS to provide exactly once delivery. When the SinkTask is started or a rebalance occurs, the task would reload offsets from HDFS and use this method to reset the consumer to those offsets. SinkTasks that do not manage their own offsets do not need to use this method.


https://kafka.apache.org/26/javadoc/index.html?org/apache/kafka/connect/sink/SinkTaskContext.html
