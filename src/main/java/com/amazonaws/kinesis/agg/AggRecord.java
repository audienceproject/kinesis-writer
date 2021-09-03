//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.amazonaws.kinesis.agg;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.kinesis.retrieval.kpl.Messages.AggregatedRecord;
import software.amazon.kinesis.retrieval.kpl.Messages.Record;
import software.amazon.kinesis.retrieval.kpl.Messages.AggregatedRecord.Builder;

@NotThreadSafe
public class AggRecord {
    private static final byte[] AGGREGATED_RECORD_MAGIC = new byte[]{-13, -119, -102, -62};
    protected static final String MESSAGE_DIGEST_NAME = "MD5";
    private static final BigInteger UINT_128_MAX = new BigInteger(StringUtils.repeat("FF", 16), 16);
    protected static final int MAX_BYTES_PER_RECORD = 1048576;
    protected static final int AGGREGATION_OVERHEAD_BYTES = 256;
    protected static final int PARTITION_KEY_MIN_LENGTH = 1;
    protected static final int PARTITION_KEY_MAX_LENGTH = 256;
    private int aggregatedMessageSizeBytes = 0;
    private final AggRecord.KeySet explicitHashKeys = new AggRecord.KeySet();
    private final AggRecord.KeySet partitionKeys = new AggRecord.KeySet();
    private Builder aggregatedRecordBuilder = AggregatedRecord.newBuilder();
    private final MessageDigest md5;
    private String aggPartitionKey = "";
    private String aggExplicitHashKey = "";

    public AggRecord() {
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException var2) {
            throw new IllegalStateException("Could not create an MD5 message digest.", var2);
        }
    }

    public int getNumUserRecords() {
        return this.aggregatedRecordBuilder.getRecordsCount();
    }

    public int getSizeBytes() {
        return this.getNumUserRecords() == 0 ? 0 : AGGREGATED_RECORD_MAGIC.length + this.aggregatedMessageSizeBytes + this.md5.getDigestLength();
    }

    public byte[] toRecordBytes() {
        if (this.getNumUserRecords() == 0) {
            return new byte[0];
        } else {
            byte[] messageBody = this.aggregatedRecordBuilder.build().toByteArray();
            this.md5.reset();
            byte[] messageDigest = this.md5.digest(messageBody);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(this.getSizeBytes());
            baos.write(AGGREGATED_RECORD_MAGIC, 0, AGGREGATED_RECORD_MAGIC.length);
            baos.write(messageBody, 0, messageBody.length);
            baos.write(messageDigest, 0, messageDigest.length);
            return baos.toByteArray();
        }
    }

    public void clear() {
        this.md5.reset();
        this.aggExplicitHashKey = "";
        this.aggPartitionKey = "";
        this.aggregatedMessageSizeBytes = 0;
        this.explicitHashKeys.clear();
        this.partitionKeys.clear();
        this.aggregatedRecordBuilder = AggregatedRecord.newBuilder();
    }

    public String getPartitionKey() {
        return this.getNumUserRecords() == 0 ? null : this.aggPartitionKey;
    }

    public String getExplicitHashKey() {
        return this.getNumUserRecords() == 0 ? null : this.aggExplicitHashKey;
    }

    private int calculateRecordSize(String partitionKey, String explicitHashKey, byte[] data) {
        int messageSize = 0;
        int ehkLength;
        if (!this.partitionKeys.contains(partitionKey)) {
            ehkLength = partitionKey.getBytes().length;
            ++messageSize;
            messageSize += this.calculateVarintSize((long)ehkLength);
            messageSize += ehkLength;
        }

        if (!this.explicitHashKeys.contains(explicitHashKey)) {
            ehkLength = explicitHashKey.getBytes().length;
            ++messageSize;
            messageSize += this.calculateVarintSize((long)ehkLength);
            messageSize += ehkLength;
        }

        long innerRecordSize = 0L;
        ++innerRecordSize;
        innerRecordSize += (long)this.calculateVarintSize(this.partitionKeys.getPotentialIndex(partitionKey));
        if (explicitHashKey != null) {
            ++innerRecordSize;
            innerRecordSize += (long)this.calculateVarintSize(this.explicitHashKeys.getPotentialIndex(explicitHashKey));
        }

        ++innerRecordSize;
        innerRecordSize += (long)this.calculateVarintSize((long)data.length);
        innerRecordSize += (long)data.length;
        ++messageSize;
        messageSize += this.calculateVarintSize(innerRecordSize);
        messageSize = (int)((long)messageSize + innerRecordSize);
        return messageSize;
    }

    private int calculateVarintSize(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("Size values should not be negative.");
        } else {
            int numBitsNeeded = 0;
            if (value == 0L) {
                numBitsNeeded = 1;
            } else {
                while(value > 0L) {
                    ++numBitsNeeded;
                    value >>= 1;
                }
            }

            int numVarintBytes = numBitsNeeded / 7;
            if (numBitsNeeded % 7 > 0) {
                ++numVarintBytes;
            }

            return numVarintBytes;
        }
    }

    public boolean addUserRecord(String partitionKey, String explicitHashKey, byte[] data) {
        explicitHashKey = explicitHashKey != null ? explicitHashKey : this.createExplicitHashKey(partitionKey);
        this.validatePartitionKey(partitionKey);
        this.validateExplicitHashKey(explicitHashKey);
        this.validateData(data);
        int sizeOfNewRecord = this.calculateRecordSize(partitionKey, explicitHashKey, data);
        if (this.getSizeBytes() + sizeOfNewRecord > 1048576) {
            return false;
        } else if (sizeOfNewRecord > 1048576) {
            throw new IllegalArgumentException("Input record (PK=" + partitionKey + ", EHK=" + explicitHashKey + ", SizeBytes=" + sizeOfNewRecord + ") is larger than the maximum size before Aggregation encoding of " + 1048320 + " bytes");
        } else {
            software.amazon.kinesis.retrieval.kpl.Messages.Record.Builder newRecord = Record.newBuilder().setData(data != null ? ByteString.copyFrom(data) : ByteString.EMPTY);
            AggRecord.ExistenceIndexPair pkAddResult = this.partitionKeys.add(partitionKey);
            if (pkAddResult.getFirst()) {
                this.aggregatedRecordBuilder.addPartitionKeyTable(partitionKey);
            }

            newRecord.setPartitionKeyIndex(pkAddResult.getSecond());
            AggRecord.ExistenceIndexPair ehkAddResult = this.explicitHashKeys.add(explicitHashKey);
            if (ehkAddResult.getFirst()) {
                this.aggregatedRecordBuilder.addExplicitHashKeyTable(explicitHashKey);
            }

            newRecord.setExplicitHashKeyIndex(ehkAddResult.getSecond());
            this.aggregatedMessageSizeBytes += sizeOfNewRecord;
            this.aggregatedRecordBuilder.addRecords(newRecord.build());
            if (this.aggregatedRecordBuilder.getRecordsCount() == 1) {
                this.aggPartitionKey = partitionKey;
                this.aggExplicitHashKey = explicitHashKey;
            }

            return true;
        }
    }

    public PutRecordsRequest toPutRecordRequest(String streamName) {
        byte[] recordBytes = this.toRecordBytes();
        return (PutRecordsRequest)PutRecordsRequest.builder().streamName(streamName).records(new PutRecordsRequestEntry[]{this.toPutRecordsRequestEntry()}).build();
    }

    public PutRecordsRequestEntry toPutRecordsRequestEntry() {
        return (PutRecordsRequestEntry)PutRecordsRequestEntry.builder().explicitHashKey(this.getExplicitHashKey()).partitionKey(this.getPartitionKey()).data(SdkBytes.fromByteBuffer(ByteBuffer.wrap(this.toRecordBytes()))).build();
    }

    private void validateData(byte[] data) {
        int maxAllowableDataLength = 1048576 - AGGREGATED_RECORD_MAGIC.length - this.md5.getDigestLength();
        if (data != null && data.length > maxAllowableDataLength) {
            throw new IllegalArgumentException("Data must be less than or equal to " + maxAllowableDataLength + " bytes in size, got " + data.length + " bytes");
        }
    }

    private void validatePartitionKey(String partitionKey) {
        if (partitionKey == null) {
            throw new IllegalArgumentException("Partition key cannot be null");
        } else if (partitionKey.getBytes().length >= 1 && partitionKey.getBytes().length <= 256) {
            try {
                partitionKey.getBytes(StandardCharsets.UTF_8);
            } catch (Exception var3) {
                throw new IllegalArgumentException("Partition key must be valid " + StandardCharsets.UTF_8.displayName());
            }
        } else {
            throw new IllegalArgumentException("Invalid partition key. Length must be at least 1 and at most 256, got length of " + partitionKey.getBytes().length);
        }
    }

    private void validateExplicitHashKey(String explicitHashKey) {
        if (explicitHashKey != null) {
            BigInteger b = null;

            try {
                b = new BigInteger(explicitHashKey);
                if (b.compareTo(UINT_128_MAX) > 0 || b.compareTo(BigInteger.ZERO) < 0) {
                    throw new IllegalArgumentException("Invalid explicitHashKey, must be greater or equal to zero and less than or equal to (2^128 - 1), got " + explicitHashKey);
                }
            } catch (NumberFormatException var4) {
                throw new IllegalArgumentException("Invalid explicitHashKey, must be an integer, got " + explicitHashKey);
            }
        }
    }

    private String createExplicitHashKey(String partitionKey) {
        BigInteger hashKey = BigInteger.ZERO;
        this.md5.reset();
        byte[] pkDigest = this.md5.digest(partitionKey.getBytes(StandardCharsets.UTF_8));

        for(int i = 0; i < this.md5.getDigestLength(); ++i) {
            BigInteger p = new BigInteger(String.valueOf(pkDigest[i] & 255));
            BigInteger shifted = p.shiftLeft((16 - i - 1) * 8);
            hashKey = hashKey.add(shifted);
        }

        return hashKey.toString(10);
    }

    private class ExistenceIndexPair {
        private Boolean first;
        private Long second;

        public ExistenceIndexPair(Boolean first, Long second) {
            this.first = first;
            this.second = second;
        }

        public Boolean getFirst() {
            return this.first;
        }

        public Long getSecond() {
            return this.second;
        }
    }

    private class KeySet {
        private List<String> keys = new LinkedList();
        private Map<String, Long> lookup = new TreeMap();

        public KeySet() {
        }

        public Long getPotentialIndex(String s) {
            Long it = (Long)this.lookup.get(s);
            return it != null ? it : (long)this.keys.size();
        }

        public AggRecord.ExistenceIndexPair add(String s) {
            Long it = (Long)this.lookup.get(s);
            if (it != null) {
                return AggRecord.this.new ExistenceIndexPair(false, it);
            } else {
                if (!this.lookup.containsKey(s)) {
                    this.lookup.put(s, (long)this.keys.size());
                }

                this.keys.add(s);
                return AggRecord.this.new ExistenceIndexPair(true, (long)(this.keys.size() - 1));
            }
        }

        public boolean contains(String s) {
            return s != null && this.lookup.containsKey(s);
        }

        public void clear() {
            this.keys.clear();
            this.lookup.clear();
        }
    }
}
