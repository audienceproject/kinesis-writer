//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.amazonaws.kinesis.agg;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.concurrent.NotThreadSafe;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

@NotThreadSafe
public class RecordAggregator {
    private AggRecord currentRecord = new AggRecord();
    private List<RecordAggregator.ListenerExecutorPair> listeners = new LinkedList();

    public RecordAggregator() {
    }

    public int getNumUserRecords() {
        return this.currentRecord.getNumUserRecords();
    }

    public long getSizeBytes() {
        return (long)this.currentRecord.getSizeBytes();
    }

    public void clearRecord() {
        this.currentRecord = new AggRecord();
    }

    public void clearListeners() {
        this.listeners.clear();
    }

    public void onRecordComplete(RecordAggregator.RecordCompleteListener listener) {
        this.onRecordComplete(listener, ForkJoinPool.commonPool());
    }

    public void onRecordComplete(RecordAggregator.RecordCompleteListener listener, Executor executor) {
        this.listeners.add(new RecordAggregator.ListenerExecutorPair(listener, executor));
    }

    public AggRecord clearAndGet() {
        if (this.getNumUserRecords() == 0) {
            return null;
        } else {
            AggRecord out = this.currentRecord;
            this.clearRecord();
            return out;
        }
    }

    public AggRecord addUserRecord(KinesisClientRecord userRecord) throws Exception {
        if (userRecord == null) {
            throw new IllegalArgumentException("Input user record cannot be null.");
        } else if (!userRecord.data().hasArray()) {
            throw new IllegalStateException("The addUserRecord method only works for UserRecord objects whose data ByteBuffer  has a backing byte[] available.");
        } else {
            return this.addUserRecord(userRecord.partitionKey(), userRecord.explicitHashKey(), userRecord.data().array());
        }
    }

    public AggRecord addUserRecord(String partitionKey, byte[] data) throws Exception {
        return this.addUserRecord(partitionKey, (String)null, data);
    }

    public AggRecord addUserRecord(String partitionKey, String explicitHashKey, byte[] data) throws Exception {
        boolean success = this.currentRecord.addUserRecord(partitionKey, explicitHashKey, data);
        if (success) {
            return null;
        } else {
            AggRecord completeRecord = this.currentRecord;
            Iterator var6 = this.listeners.iterator();

            while(var6.hasNext()) {
                RecordAggregator.ListenerExecutorPair pair = (RecordAggregator.ListenerExecutorPair)var6.next();
                pair.getExecutor().execute(() -> {
                    pair.getListener().recordComplete(completeRecord);
                });
            }

            this.clearRecord();
            success = this.currentRecord.addUserRecord(partitionKey, explicitHashKey, data);
            if (!success) {
                throw new Exception(String.format("Unable to add User Record %s, %s with data length %s", partitionKey, explicitHashKey, data.length));
            } else {
                return completeRecord;
            }
        }
    }

    private class ListenerExecutorPair {
        private RecordAggregator.RecordCompleteListener listener;
        private Executor executor;

        public ListenerExecutorPair(RecordAggregator.RecordCompleteListener listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }

        public RecordAggregator.RecordCompleteListener getListener() {
            return this.listener;
        }

        public Executor getExecutor() {
            return this.executor;
        }
    }

    public interface RecordCompleteListener {
        void recordComplete(AggRecord var1);
    }
}
