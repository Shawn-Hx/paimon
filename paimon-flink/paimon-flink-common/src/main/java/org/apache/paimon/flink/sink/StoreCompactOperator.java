/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.flink.utils.RuntimeContextUtils;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.DataFileMetaSerializer;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.ChannelComputer;
import org.apache.paimon.utils.Pair;
import org.apache.paimon.utils.Preconditions;

import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.data.RowData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.apache.paimon.utils.SerializationUtils.deserializeBinaryRow;

/**
 * A dedicated operator for manual triggered compaction.
 *
 * <p>In-coming records are generated by sources built from {@link
 * org.apache.paimon.flink.source.CompactorSourceBuilder}. The records will contain partition keys
 * in the first few columns, and bucket number in the last column.
 */
public class StoreCompactOperator extends PrepareCommitOperator<RowData, Committable> {

    private static final Logger LOG = LoggerFactory.getLogger(StoreCompactOperator.class);

    private final FileStoreTable table;
    private final StoreSinkWrite.Provider storeSinkWriteProvider;
    private final String initialCommitUser;
    private final boolean fullCompaction;

    private transient StoreSinkWriteState state;
    private transient StoreSinkWrite write;
    private transient DataFileMetaSerializer dataFileMetaSerializer;
    private transient Set<Pair<BinaryRow, Integer>> waitToCompact;

    public StoreCompactOperator(
            StreamOperatorParameters<Committable> parameters,
            FileStoreTable table,
            StoreSinkWrite.Provider storeSinkWriteProvider,
            String initialCommitUser,
            boolean fullCompaction) {
        super(parameters, Options.fromMap(table.options()));
        Preconditions.checkArgument(
                !table.coreOptions().writeOnly(),
                CoreOptions.WRITE_ONLY.key() + " should not be true for StoreCompactOperator.");
        this.table = table;
        this.storeSinkWriteProvider = storeSinkWriteProvider;
        this.initialCommitUser = initialCommitUser;
        this.fullCompaction = fullCompaction;
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);

        // Each job can only have one username and this name must be consistent across restarts.
        // We cannot use job id as commit username here because user may change job id by creating
        // a savepoint, stop the job and then resume from savepoint.
        String commitUser =
                StateUtils.getSingleValueFromState(
                        context, "commit_user_state", String.class, initialCommitUser);

        state =
                new StoreSinkWriteStateImpl(
                        RuntimeContextUtils.getIndexOfThisSubtask(getRuntimeContext()),
                        context,
                        (tableName, partition, bucket) ->
                                ChannelComputer.select(
                                                partition,
                                                bucket,
                                                RuntimeContextUtils.getNumberOfParallelSubtasks(
                                                        getRuntimeContext()))
                                        == RuntimeContextUtils.getIndexOfThisSubtask(
                                                getRuntimeContext()));
        write =
                storeSinkWriteProvider.provide(
                        table,
                        commitUser,
                        state,
                        getContainingTask().getEnvironment().getIOManager(),
                        memoryPool,
                        getMetricGroup());
    }

    @Override
    public void open() throws Exception {
        super.open();
        dataFileMetaSerializer = new DataFileMetaSerializer();
        waitToCompact = new LinkedHashSet<>();
    }

    @Override
    public void processElement(StreamRecord<RowData> element) throws Exception {
        RowData record = element.getValue();

        long snapshotId = record.getLong(0);
        BinaryRow partition = deserializeBinaryRow(record.getBinary(1));
        int bucket = record.getInt(2);
        byte[] serializedFiles = record.getBinary(3);
        List<DataFileMeta> files = dataFileMetaSerializer.deserializeList(serializedFiles);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "Store compact operator received record, snapshotId {}, partition {}, bucket {}, files {}",
                    snapshotId,
                    partition,
                    bucket,
                    files);
        }

        if (write.streamingMode()) {
            write.notifyNewFiles(snapshotId, partition, bucket, files);
        } else {
            Preconditions.checkArgument(
                    files.isEmpty(),
                    "Batch compact job does not concern what files are compacted. "
                            + "They only need to know what buckets are compacted.");
        }

        waitToCompact.add(Pair.of(partition, bucket));
    }

    @Override
    protected List<Committable> prepareCommit(boolean waitCompaction, long checkpointId)
            throws IOException {

        try {
            for (Pair<BinaryRow, Integer> partitionBucket : waitToCompact) {
                write.compact(partitionBucket.getKey(), partitionBucket.getRight(), fullCompaction);
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception happens while executing compaction.", e);
        }
        waitToCompact.clear();
        return write.prepareCommit(waitCompaction, checkpointId);
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        write.snapshotState();
        state.snapshotState();
    }

    @Override
    public void close() throws Exception {
        super.close();
        write.close();
    }

    @VisibleForTesting
    public Set<Pair<BinaryRow, Integer>> compactionWaitingSet() {
        return waitToCompact;
    }

    /** {@link StreamOperatorFactory} of {@link StoreCompactOperator}. */
    public static class Factory extends PrepareCommitOperator.Factory<RowData, Committable> {
        private final FileStoreTable table;
        private final StoreSinkWrite.Provider storeSinkWriteProvider;
        private final String initialCommitUser;
        private final boolean fullCompaction;

        public Factory(
                FileStoreTable table,
                StoreSinkWrite.Provider storeSinkWriteProvider,
                String initialCommitUser,
                boolean fullCompaction) {
            super(Options.fromMap(table.options()));
            Preconditions.checkArgument(
                    !table.coreOptions().writeOnly(),
                    CoreOptions.WRITE_ONLY.key() + " should not be true for StoreCompactOperator.");
            this.table = table;
            this.storeSinkWriteProvider = storeSinkWriteProvider;
            this.initialCommitUser = initialCommitUser;
            this.fullCompaction = fullCompaction;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends StreamOperator<Committable>> T createStreamOperator(
                StreamOperatorParameters<Committable> parameters) {
            return (T)
                    new StoreCompactOperator(
                            parameters,
                            table,
                            storeSinkWriteProvider,
                            initialCommitUser,
                            fullCompaction);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
            return StoreCompactOperator.class;
        }
    }
}
