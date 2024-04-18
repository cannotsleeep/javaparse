/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.indices.flush;

import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

/**
 * Result for all copies of a shard
 */
public class ShardsSyncedFlushResult implements Writeable {
    private String failureReason;
    private Map<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> shardResponses;
    private String syncId;
    private ShardId shardId;
    // some shards may be unassigned, so we need this as state
    private int totalShards;

    public ShardsSyncedFlushResult(StreamInput in) throws IOException {
        failureReason = in.readOptionalString();
        int numResponses = in.readInt();
        shardResponses = new HashMap<>();
        for (int i = 0; i < numResponses; i++) {
            ShardRouting shardRouting = new ShardRouting(in);
            SyncedFlushService.ShardSyncedFlushResponse response = SyncedFlushService.ShardSyncedFlushResponse.readSyncedFlushResponse(in);
            shardResponses.put(shardRouting, response);
        }
        syncId = in.readOptionalString();
        shardId = new ShardId(in);
        totalShards = in.readInt();
    }

    public ShardId getShardId() {
        return shardId;
    }

    /**
     * failure constructor
     */
    public ShardsSyncedFlushResult(ShardId shardId, int totalShards, String failureReason) {
        this.syncId = null;
        this.failureReason = failureReason;
        this.shardResponses = emptyMap();
        this.shardId = shardId;
        this.totalShards = totalShards;
    }

    /**
     * success constructor
     */
    public ShardsSyncedFlushResult(
        ShardId shardId,
        String syncId,
        int totalShards,
        Map<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> shardResponses
    ) {
        this.failureReason = null;
        this.shardResponses = unmodifiableMap(new HashMap<>(shardResponses));
        this.syncId = syncId;
        this.totalShards = totalShards;
        this.shardId = shardId;
    }

    /**
     * @return true if the operation failed before reaching step three of synced flush. {@link #failureReason()} can be used for
     * more details
     */
    public boolean failed() {
        return failureReason != null;
    }

    /**
     * @return the reason for the failure if synced flush failed before step three of synced flush
     */
    public String failureReason() {
        return failureReason;
    }

    public String syncId() {
        return syncId;
    }

    /**
     * @return total number of shards for which a sync attempt was made
     */
    public int totalShards() {
        return totalShards;
    }

    /**
     * @return total number of successful shards
     */
    public int successfulShards() {
        int i = 0;
        for (SyncedFlushService.ShardSyncedFlushResponse result : shardResponses.values()) {
            if (result.success()) {
                i++;
            }
        }
        return i;
    }

    /**
     * @return an array of shard failures
     */
    public Map<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> failedShards() {
        Map<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> failures = new HashMap<>();
        for (Map.Entry<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> result : shardResponses.entrySet()) {
            if (result.getValue().success() == false) {
                failures.put(result.getKey(), result.getValue());
            }
        }
        return failures;
    }

    /**
     * @return Individual responses for each shard copy with a detailed failure message if the copy failed to perform the synced flush.
     * Empty if synced flush failed before step three.
     */
    public Map<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> shardResponses() {
        return shardResponses;
    }

    public ShardId shardId() {
        return shardId;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(failureReason);
        out.writeInt(shardResponses.size());
        for (Map.Entry<ShardRouting, SyncedFlushService.ShardSyncedFlushResponse> entry : shardResponses.entrySet()) {
            entry.getKey().writeTo(out);
            entry.getValue().writeTo(out);
        }
        out.writeOptionalString(syncId);
        shardId.writeTo(out);
        out.writeInt(totalShards);
    }
}
