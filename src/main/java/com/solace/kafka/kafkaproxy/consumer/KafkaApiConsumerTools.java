/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.solace.kafka.kafkaproxy.consumer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.Base64.Decoder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Assignment;
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.HeartbeatResponseData;
import org.apache.kafka.common.message.JoinGroupRequestData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.LeaveGroupRequestData;
import org.apache.kafka.common.message.LeaveGroupResponseData;
import org.apache.kafka.common.message.ListOffsetsRequestData;
import org.apache.kafka.common.message.ListOffsetsResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.message.OffsetFetchRequestData;
import org.apache.kafka.common.message.OffsetFetchResponseData;
import org.apache.kafka.common.message.OffsetForLeaderEpochRequestData;
import org.apache.kafka.common.message.OffsetForLeaderEpochRequestData.OffsetForLeaderTopic;
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.OffsetFetchResponseData.OffsetFetchResponseGroup;
import org.apache.kafka.common.message.OffsetFetchResponseData.OffsetFetchResponsePartition;
import org.apache.kafka.common.message.OffsetFetchResponseData.OffsetFetchResponsePartitions;
import org.apache.kafka.common.message.OffsetFetchResponseData.OffsetFetchResponseTopic;
import org.apache.kafka.common.message.OffsetFetchResponseData.OffsetFetchResponseTopics;
import org.apache.kafka.common.message.OffsetCommitResponseData;
import org.apache.kafka.common.message.OffsetCommitResponseData.OffsetCommitResponsePartition;
import org.apache.kafka.common.message.OffsetCommitResponseData.OffsetCommitResponseTopic;
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData.EpochEndOffset;
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData.OffsetForLeaderTopicResultCollection;
import org.apache.kafka.common.message.JoinGroupResponseData.JoinGroupResponseMember;
import org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBroker;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.message.FetchResponseData.FetchableTopicResponse;
import org.apache.kafka.common.message.FetchResponseData.PartitionData;
import org.apache.kafka.common.message.JoinGroupRequestData.JoinGroupRequestProtocol;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MemoryRecordsBuilder;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.FetchResponse;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.FindCoordinatorResponse;
import org.apache.kafka.common.requests.HeartbeatRequest;
import org.apache.kafka.common.requests.HeartbeatResponse;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.requests.JoinGroupResponse;
import org.apache.kafka.common.requests.LeaveGroupRequest;
import org.apache.kafka.common.requests.LeaveGroupResponse;
import org.apache.kafka.common.requests.ListOffsetsRequest;
import org.apache.kafka.common.requests.ListOffsetsResponse;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.OffsetCommitResponse;
import org.apache.kafka.common.requests.OffsetFetchRequest;
import org.apache.kafka.common.requests.OffsetFetchResponse;
import org.apache.kafka.common.requests.OffsetsForLeaderEpochRequest;
import org.apache.kafka.common.requests.OffsetsForLeaderEpochResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.SyncGroupRequest;
import org.apache.kafka.common.requests.SyncGroupResponse;
import org.apache.kafka.common.utils.Time;

import com.solace.kafka.kafkaproxy.ProxyReactor.ListenPort;
import com.solace.kafka.kafkaproxy.consumer.SolaceQueueConsumer.SolaceMessageEntryException;
import com.solace.kafka.kafkaproxy.util.OpConstants;
import com.solace.kafka.kafkaproxy.util.ProxyUtils;
// import com.solacesystems.common.util.Topic;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStateException;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.XMLMessage;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KafkaApiConsumerTools {

    public static final int 
                DEFAULT_BROKER_MAX_WAIT_TIME_MS = 500,
                DEFAULT_BROKER_MIN_BYTES = 1,
                DEFAULT_BROKER_MAX_BYTES = 1048576,
                DEFAULT_BROKER_MESSAGE_MAX_SIZE_BYTES = 1048576;

    public static final String
                PROP_FETCH_MAX_WAIT_MS = "fetch.max.wait.ms",
                PROP_FETCH_MIN_BYTES = "fetch.min.bytes",
                PROP_FETCH_MAX_BYTES = "fetch.max.bytes",
                PROP_MESSAGE_MAX_BYTES = "message.max.bytes",
                PROP_PARTITIONS_PER_TOPIC = "partitions.per.topic";

    public static final byte MAGIC_BYTE = 0x02;

    /**
     * Active JCSMPSession passed as argument from ProxyChannel
     * This object is passed to the SolaceQueueConsumer on construction
     */
    private JCSMPSession solaceSession;

    /**
     * This object will be created on JoinGroup request
     * and deleted on LeaveGroup
     */
    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PRIVATE)
    private SolaceQueueConsumer solaceQueueConsumer;

    @Getter(AccessLevel.PROTECTED)
    private volatile boolean subscribed;

    private volatile String consumerGroupId;

    @Getter(AccessLevel.PROTECTED)
    private volatile String subscribedTopicName;

    // @Getter(AccessLevel.PROTECTED)
    // private volatile Topic subscribedTopic;

    @Getter(AccessLevel.PROTECTED)
    private volatile Uuid subscribedTopicId;

    @Getter(AccessLevel.PROTECTED)
    private volatile Integer subscribedPartitionIndex;

    // Kafka-side cached values for fetch management
    @Getter
    private volatile Integer cachedSessionId;

    private boolean firstSessionFetch;

    private volatile Long cachedLastDeliveredOffset;

    private volatile Integer cachedLastPartitionMaxBytes;

    private volatile Long lastSuccessfulFetchTimestamp;

    // Values provided on configuration of the instance
    private final Integer defaultBrokerMaxWaitTimeMs;

    private final Integer defaultBrokerMinBytes;

    private final Integer absoluleBrokerMaxBytes;

    private final Object fetchChannelLock = new Object();

    // TODO - Investigate 'throttleTimeMs' parameter in between requests. This is the mechanism used by Kafka
    // for flow control. The cluster can slow down response times, but also reports to the clients how
    // much delay was added between request and reply, so that the client knows to behave better.
    // - network bandwidth control is configured using: producer_byte_rate, consumer_byte_rate
    // - also request_percentage_per_second - which defines amount of CPU + IO utilization is allowed per client/group

    @Getter
    private static final Map<ConsumerToolsKey, Queue<KafkaApiConsumerTools>> consumerInstances = new ConcurrentHashMap<>();

    private static final Map<Uuid, Integer> partitionIndexAssigner = new ConcurrentHashMap<>();

    private static final int DEFAULT_PARTITIONS_PER_TOPIC = 100;

    // TODO: Make partitionsPerTopic configurable
    private static int partitionsPerTopic = DEFAULT_PARTITIONS_PER_TOPIC;

    private static int fetchSessionIdAssigner = 1_000;      // Start new fetch sessionId at 1000

    private static int generationIdAssigner = 1;

    private static synchronized int assignGenerationId() {
        return generationIdAssigner++;
    }

    private static synchronized int assignFetchSessionId() {
        return fetchSessionIdAssigner++;
    }

    /**
     * Call from Metadata connection to assign a new Partition Index if not already assigned
     * @param topicName
     * @param partitionIndex
     * @return
     */
    public static Integer assignPartitionIndex(String topicName, Integer partitionIndex) {
        if (partitionIndex != null && partitionIndex >= 0) {
            return partitionIndex;
        }
        Uuid topicId = Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
        return assignPartitionIndex(topicId, partitionIndex);
    }

    /**
     * Call from Metadata connection to assign a new Partition Index if not already assigned
     * @param topicId
     * @param partitionIndex
     * @return
     */
    public static Integer assignPartitionIndex(Uuid topicId, Integer partitionIndex) {
        if (partitionIndex != null && partitionIndex >= 0) {
            return partitionIndex;
        }
        if (partitionIndexAssigner.containsKey(topicId)) {
            int newIndex = ( partitionIndexAssigner.get(topicId) + 1 ) % partitionsPerTopic;
            partitionIndexAssigner.put(topicId, newIndex);
            return newIndex;
        }
        partitionIndexAssigner.put(topicId, 0);
        return 0;
    }

    /**
     * Used as Map Index to store and retrieve instances of KafkaApiConsumerTools
     */
    public record ConsumerToolsKey(
        String clientId,
        Uuid topicId,
        Integer partitionIndex)  {}

    /**
     * Call this method to 
     * @param key
     * @param value
     */
    public static void addAvailableConsumerToolsEntry(ConsumerToolsKey key, KafkaApiConsumerTools value) {
        if (consumerInstances.containsKey(key)) {
            consumerInstances.get(key).add(value);
            return;
        }
        consumerInstances.put(key, new ConcurrentLinkedQueue<>(List.of(value)));
    }

    public KafkaApiConsumerTools(
            final JCSMPSession jcsmpSession, 
            final Properties kafkaProperties) throws Exception
    {
        this.solaceSession = jcsmpSession;
        resetSubscription();
        try {
            String maxWaitTimeMs = kafkaProperties.getProperty(PROP_FETCH_MAX_WAIT_MS, Integer.toString(DEFAULT_BROKER_MAX_WAIT_TIME_MS));
            String minBytes = kafkaProperties.getProperty(PROP_FETCH_MIN_BYTES, Integer.toString(DEFAULT_BROKER_MIN_BYTES));
            String maxBytes = kafkaProperties.getProperty(PROP_FETCH_MAX_BYTES, Integer.toString(DEFAULT_BROKER_MAX_BYTES));
            // TODO: Implement max message size
            // String messageMaxSizeBytes = kafkaProperties.getProperty(PROP_MESSAGE_MAX_BYTES, Integer.toString(DEFAULT_BROKER_MESSAGE_MAX_SIZE_BYTES));

            this.defaultBrokerMaxWaitTimeMs = Integer.parseInt(maxWaitTimeMs);
            this.defaultBrokerMinBytes = Integer.parseInt(minBytes);
            this.absoluleBrokerMaxBytes = Integer.parseInt(maxBytes);
        } catch (Exception exc) {
            log.error(
                    "Error parsing one of configuration properties on initialization: {}",
                    List.of(
                        PROP_FETCH_MAX_WAIT_MS,
                        PROP_FETCH_MIN_BYTES,
                        PROP_FETCH_MAX_BYTES,
                        PROP_MESSAGE_MAX_BYTES
                    ));
            log.error("Error message: {}", exc.getLocalizedMessage());
            throw exc;
        }
    }

    private void resetSubscription() {
        this.subscribed = false;
        this.consumerGroupId = "";
        this.subscribedTopicName = "";
        this.subscribedTopicId = null;
        this.subscribedPartitionIndex = null;
        this.cachedSessionId = null;
        this.cachedLastDeliveredOffset = -1L;
        this.cachedLastPartitionMaxBytes = 1048576;
        this.firstSessionFetch = true;
        this.lastSuccessfulFetchTimestamp = Time.SYSTEM.milliseconds();
    }

    /**
     * Safely shutdown Solace consumer flow associated with KafkaApiConsumerTools instance.
     * Ignores NULL parameter and flow instances that are not active
     * @param consumerToolsInstance
     * @return Kafka Errors.NONE if successful or no operation performed, Errors.UKNOWN_SERVER_ERROR if fails
     */
    public static Errors stopConsumerFlowIfRunning(KafkaApiConsumerTools consumerToolsInstance) {
        if (consumerToolsInstance == null) {
            return Errors.NONE;
        }
        Errors errorCode = Errors.NONE;
        if (consumerFlowSubscribed(consumerToolsInstance) && consumerToolsInstance.getSolaceQueueConsumer() != null) {
            try {
                if (consumerToolsInstance.getSolaceQueueConsumer().isFlowActive()) {
                    final String subscribedQueue = consumerToolsInstance.getSolaceQueueConsumer().getQueueName();
                    log.debug("Unsubscribing from queue: {}", subscribedQueue);
                    consumerToolsInstance.getSolaceQueueConsumer().stopQueueConsumer();
                    log.info("Unsubscribed from queue: {}", subscribedQueue);
                }
            } catch (Exception exc) {
                log.error("Failed to unsubscribe from queue normally");
                errorCode = Errors.UNKNOWN_SERVER_ERROR;
            } finally {
                consumerToolsInstance.resetSubscription();
                consumerToolsInstance.setSolaceQueueConsumer(null);
            }
        } else if (consumerFlowSubscribed(consumerToolsInstance)) {
            // Should not end up here
            consumerToolsInstance.resetSubscription();
        }
        return errorCode;
    }

    /**
     * Test if the consumerToolsInstance contains an active consumer FlowReceiver
     * @param consumerToolsInstance
     * @return true if consumerToolsInstance is subscribed; false if not subscribed or null
     */
    public static boolean consumerFlowSubscribed(KafkaApiConsumerTools consumerToolsInstance) {
        return consumerToolsInstance != null && consumerToolsInstance.isSubscribed();
    }

    /**
     * The purpose of this method is to get KafkaApiConsumerTools instance for Data/Fetch channel
     * @param request - AbstractRequest of sub-type FetchOffsetsForLeaderEpochRequest, OffsetsRequest, FetchRequest
     * @param requestHeader - RequestHeader of the message
     * @return KafkaApiConsumerTools instance matching criteria contained in the request message
     * @throws InvalidRequestException
     * @throws UnknownServerException
     */
    public static KafkaApiConsumerTools getConsumerToolsInstance(
        AbstractRequest request, 
        RequestHeader requestHeader) throws InvalidRequestException, UnknownServerException
    {
        final String clientId = requestHeader.clientId();
        String topicName;
        Uuid topicId;
        Integer partitionIndex;

        /**
         * TODO: Force consumers to re-join if consumerToolsInstance is null when receiving OffsetCommit and Heartbeat requests
         * *** OR if group member Id is not found in mechanisms below (try this first)
         * - When received OffsetCommit or HeartBeat request and KafkaApiConsumerTools instance is null on channel then
         * - Return REBALANCE_IN_PROGRESS (kafka >= v2.5) -- Need ApiVersion
         * - Return MEMBER_ID_NOT_FOUND (kafka < v2.5) -- Need ApiVersion
         * --> If this works, it should cause consumers to re-join
         * 
         * TODO: make sure that if sessionId != cached session Id, then return FETCH_SESSION_ID_NOT_FOUND

         * TODO: Lookup consumerToolsInstance by sessionId
         * - Add ConsumerToolsKey to KafkaApiConsumerTools
         * - When finding new key in map indexed by ConsumerToolsKey, set KafkaApiConsumerTools.savedConsumerToolsKey = the new key (security)
         * 
         * Create sessionId (int) --> KafkaApiConsumerTools Map (consumerInstancesBySessionId)
         * - Get sessionId from Fetch request --> If found and >= 1000, use sessionId to get KafkaApiConsumerTools instance from map
         * - AND check that ConsumerToolsKey matches (security)
         * - If found and matches, return KafkaApiConsumerTools
         * 
         * Check if sessionId changes in fetch session, if it does:
         * - find sessionId in consumerInstancesBySessionId map
         * - Add the newSessionId --> KafkaConsumerToolsKey instance
         * - Remove the old sessionId key
         */

        /**
         * TODO: Lookup consumerToolsInstace by groupMemberId
         * Create group memberId (string) --> KafkaConsumerTools Map (consumerInstancesByGroupMemberId)
         * - When assigning a new Group member Id, (Join Group, SyncGroup), add memberId --> KafkaApiConsumerTools instance to map
         * - When handling LeaveGroup, use memberId to remove memberId
         * - AND get cachedSessionId and try to remove reference in map cachedSessionId --> consumerInstancesBySessionId
         * 
         * When handling Heartbeat and OffsetCommit requests and consumerToolsInstance is NULL
         * - Get group member Id from request
         * - If group memberId is not null and size > X
         * - lookup consumerTools instance using memberId in consumerInstancesByGroupMemberId map
         * - AND check that ConsumerToolsKey matches (security)
         * - If found and ConsumerToolsKey matches, return KafkaApiConsumerTools instance
         */
        /**
         * TODO: Handle stale consumerToolsInstances in separate thread (manage potential memory leaks)
         * - If consumer does not exit cleanly, then LeaveGroup may not be called leaving stale instances
         * - This can be quite large if there are many uncommitted messages or bytes
         * - create lastCalledTimestamp (long) entry on KafkaApiConsumerTools
         * - update timestamp with each Fetch, Heartbeat, and OffsetCommit request
         * - In separate cleanup thread, check last called for both + check for object in channels
         * - If threshold exceeded AND not found in live channel, then attempt to CLOSE and DELETE instance
         * 
         */

        try {
            if (request instanceof FetchRequest) {
                final FetchRequest fetchRequest = (FetchRequest)request;
                final FetchRequestData requestData = fetchRequest.data();
                topicName = requestData.topics().get(0).topic();
                if (topicName == null || topicName.isEmpty()) {
                    topicId = fetchRequest.data().topics().get(0).topicId();
                } else {
                    log.trace("Get Uuid from topic name: {}", topicName);
                    log.trace("The Base64 encoded UUIDv4 topic ID: {}", ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
                    topicId = Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
                }
                partitionIndex = requestData.topics().get(0).partitions().get(0).partition();
            } else if (request instanceof OffsetsForLeaderEpochRequest) {
                // TODO: Evaluate if this request should be here - may be best handled as a static request and always assign
                // consumer group instance on first fetch request
                // If we should assign here, then eveluate if ListOffsets should also be handled here.
                final OffsetsForLeaderEpochRequest offsetsRequest = (OffsetsForLeaderEpochRequest)request;
                final OffsetForLeaderTopic offsetForLeaderTopic = offsetsRequest.data().topics().iterator().next();
                topicName = offsetForLeaderTopic.topic();
                log.trace("Get Uuid from topic name: {}", topicName);
                log.trace("The Base64 encoded UUIDv4 topic ID: {}", ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
                topicId = Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
                partitionIndex = offsetForLeaderTopic.partitions().get(0).partition();
            } else {
                log.error("Request type not supported for this operation: {}", request.apiKey().name());
                throw new InvalidRequestException("Request type not supported for this operation: " + request.apiKey().name());
            }
        } catch (Exception exc) {
            log.error("Error parsing request type: {} -- Exception: {}", request.apiKey().name(), exc.getLocalizedMessage());
            throw new InvalidRequestException("Error parsing request type: " + request.apiKey().name());
        }
        final ConsumerToolsKey key = new ConsumerToolsKey(clientId, topicId, partitionIndex);
        if (consumerInstances.containsKey(key) && consumerInstances.get(key).peek() != null) {
            /**
             * TODO: Ensure that we don't receive a stale instance in the hand-off
             * May require either a separate processor to check and discard stale instances
             * OR check queued timestamp and discard if > threshold
             */
            return consumerInstances.get(key).poll();
        } else {
            log.warn("No Consumer Flow to Assign");
            // throw new UnknownServerException(new IllegalStateException("No Consumer Flow to Assign"));
            return null;
        }
    }

    /**
     * Execute create response operations for requests on the consumer Data/Fetch channel.
     * Supports FetchRequest, ListOffsetsRequest, and OffsetsForLeaderEpochRequest as request types.
     * Processing these operations must be executed in series to support the Kafka protocol
     * (returned to client in sequence by correlation Id)
     * Called from ProxyReactor thread and worker threads processing Fetch Requests
     * @param request
     * @param requestHeader
     * @return FetchResponse, ListOffsetsResponse, OffsetsForLeaderEpochResponse, ErrorResponse of type AbstractResponse
     */
    public AbstractResponse createFetchChannelResponseWithLock(AbstractRequest request, RequestHeader requestHeader) {
        synchronized (fetchChannelLock) {
            if (request instanceof FetchRequest) {
                // Called from worker threads
                return createFetchResponse((FetchRequest)request, requestHeader);
            } else if (request instanceof ListOffsetsRequest) {
                return createListOffsetsResponse((ListOffsetsRequest)request, requestHeader);
            } else if (request instanceof OffsetsForLeaderEpochRequest) {
                return createOffsetsForLeaderEpochResponse((OffsetsForLeaderEpochRequest)request, requestHeader);
            }
        }
        return null;
    }
    
    /**
     * This method creates FetchResponse to request for APIKey=1
     * The session must have an active queue subscription.
     * apiVersion > 6 uses session fetch (sessionId)
     * apiVersion > 12 uses topicId
     * Should be called using createFetchChannelResponseWithLock() for sequential processing and safe multi-threading
     * DATA/FETCH CHANNEL
     * @param request
     * @param requestHeader
     * @return
     */
    protected AbstractResponse createFetchResponse(
        final FetchRequest request,
        final RequestHeader requestHeader)
    {
        final FetchRequestData requestData = request.data();

        Integer requestMaxWaitTimeMs = 0;
        Integer requestMinBytes = 0;
        Integer requestMaxBytes = 0;
        Integer requestPartitionMaxBytes = 0;
        Long requestOffset = -1L;
        Integer sessionId = 0;
        String topicName = "";
        Uuid topicId = null;
        Integer partitionIndex = 0;

        // Get data from request
        boolean sessionEligible = requestHeader.apiVersion() > 6;
        boolean sessionFetch = sessionEligible && requestData.topics().isEmpty();
        boolean topicIdEligible = requestHeader.apiVersion() > 12;

        try {
            requestMaxWaitTimeMs = requestData.maxWaitMs();
            requestMinBytes = requestData.minBytes();
            requestMaxBytes = requestData.maxBytes();

            if (sessionEligible) {
                // Get or create sessionId
                sessionId = requestData.sessionId();
                if (sessionId == 0 && subscribed) {
                    // Create new session Id
                    sessionId = assignFetchSessionId();
                }
            }
            if (sessionFetch) {
                log.trace("This is a session fetch request -- only sessionId is available -- sessionId: {}", sessionId);
                if (topicIdEligible) {
                    topicId = this.subscribedTopicId;
                } else {
                    topicName = this.subscribedTopicName;
                }
                requestPartitionMaxBytes = this.cachedLastPartitionMaxBytes;
                partitionIndex = this.subscribedPartitionIndex;
                requestOffset = this.cachedLastDeliveredOffset + 1L;
            } else {
                if (topicIdEligible) {
                    topicId = requestData.topics().get(0).topicId();
                } else {
                    topicName = requestData.topics().get(0).topic();
                }
                requestPartitionMaxBytes = requestData.topics().get(0).partitions().get(0).partitionMaxBytes();
                partitionIndex = requestData.topics().get(0).partitions().get(0).partition();
                requestOffset = requestData.topics().get(0).partitions().get(0).fetchOffset();
            }   
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createFetchResponse() -- Failed to parse Fetch request");
            return request.getErrorResponse(new InvalidRequestException(exc.getLocalizedMessage()));
        }

        if (subscribed) {
            if (sessionEligible && (this.cachedSessionId == null || this.cachedSessionId.intValue() != sessionId.intValue())) {
                this.cachedSessionId = sessionId.intValue();
            }
            this.cachedLastPartitionMaxBytes = requestPartitionMaxBytes;
        } else {
            log.warn("Attempted to process Fetch request but no active subscription -- Consumer possibly shutting down");
            return request.getErrorResponse(new UnknownServerException("No active subscription"));
        }

        log.trace("Requested Fetch Offset: {} -- [{}]", requestOffset, sessionFetch ? "SESSION Fetch" : "STANDARD Fetch");

        /**
         * Get messages and create response
         */

        // From request message
        // - requestMaxWaitTimeMs
        // - requestMinBytes
        // - requestMaxBytes
        // - requestPartitionMaxBytes
        // - requestOffset

        // From config
        // - defaultBrokerMaxWaitTimeMs     - Default if not present in request
        // - defaultBrokerMinBytes          - Default if not present in request
        // - absoluleBrokerMaxBytes         - Max regardless of request value

        // Compute the control values to use
        requestMaxWaitTimeMs = requestMaxWaitTimeMs == null || requestMaxWaitTimeMs < 1 ? this.defaultBrokerMaxWaitTimeMs : requestMaxWaitTimeMs;
        requestMinBytes = requestMinBytes == null ? this.defaultBrokerMinBytes : requestMinBytes < 1 ? 1 : requestMinBytes;
        requestMaxBytes = 
            Math.min(
                Math.min(
                    requestMaxBytes == null ? DEFAULT_BROKER_MAX_BYTES : requestMaxBytes, 
                    requestPartitionMaxBytes == null ? DEFAULT_BROKER_MAX_BYTES : requestPartitionMaxBytes
                ),
                absoluleBrokerMaxBytes
            );

        Errors partitionErrorCode = Errors.NONE;
        boolean dataToReturn = false;
        ByteBuffer buffer = ByteBuffer.allocate(requestMaxBytes);
        long startTimestamp = Time.SYSTEM.milliseconds();     // Use a consistent timestamp for all records in this batch
        long endTimestamp = startTimestamp + requestMaxWaitTimeMs;
        int remainingTime = requestMaxWaitTimeMs;
        long currentOffset = requestOffset;
        // long newReceivedOffset = -1L, newCommittedOffset = -1L;
        long lastOffsetAddedToBundle = requestOffset - 1L;
        MemoryRecords memoryRecords = null;
        MemoryRecordsBuilder builder = 
                new MemoryRecordsBuilder(
                        buffer,
                        RecordBatch.CURRENT_MAGIC_VALUE,
                        // CompressionType.NONE,               // Kafka < 3.9
                        Compression.NONE,                // Kafka >= 3.9
                        TimestampType.CREATE_TIME,
                        requestOffset,          // Starting offset to return
                        startTimestamp,       // This will be the maxTimestamp for the batch with CREATE_TIME
                        RecordBatch.NO_PRODUCER_ID,
                        RecordBatch.NO_PRODUCER_EPOCH,
                        RecordBatch.NO_SEQUENCE,
                        false, // isTransactional
                        false, // isControlBatch
                        RecordBatch.NO_PARTITION_LEADER_EPOCH,
                        requestMaxBytes // writeLimit
                );
        try {
            SolaceMessageEntry messageEntry;
            boolean moreRoom = true, minBytesThresholdMet = false;
            final Decoder base64Decoder = Base64.getDecoder();

            ByteBuffer payloadBuffer;
            ByteBuffer recordKeyBuffer;
            SDTMap messageProperties;
            RecordHeaders recordHeaders;
            long messageTimestamp;

            while (remainingTime > 0 && moreRoom) {
                messageEntry = solaceQueueConsumer.getMessage(currentOffset++, minBytesThresholdMet ? 1 : remainingTime);
                if (messageEntry == null) {
                    break;
                }
                if (messageEntry.getMessage().getRedelivered()) {
                    if (solaceQueueConsumer.messageIsInMemory(messageEntry.getMessage())) {
                        // Do not add this message if it is in memory -- duplicate
                        remainingTime = (int)(endTimestamp - Time.SYSTEM.milliseconds());
                        continue;
                    }
                }
                messageTimestamp = messageEntry.getMessage().getReceiveTimestamp();
                payloadBuffer = messageEntry.getMessage().getAttachmentByteBuffer();
                // TODO: Skip over records with payload size > max size in config (implement max message size)
                messageProperties = messageEntry.getMessage().getProperties();
                recordKeyBuffer = null;
                recordHeaders = new RecordHeaders();
                if (messageProperties != null) {
                    try {
                        for (String p : messageProperties.keySet()) {
                            if (p.contentEquals(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)) {
                                String solaceKey = messageProperties.getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
                                recordKeyBuffer = ByteBuffer.wrap(base64Decoder.decode(solaceKey));
                            } else {
                                recordHeaders.add(p, messageProperties.getBytes(p));
                            }
                        }
                    } catch (Exception exc) {
                        log.trace("Error handling Solace user property -> Kafka header conversion -- Exception: {}", exc.getLocalizedMessage());
                    }
                }

                if (builder.hasRoomFor(messageTimestamp, recordKeyBuffer, payloadBuffer, recordHeaders.toArray())) {
                    builder.append(messageTimestamp, recordKeyBuffer, payloadBuffer, recordHeaders.toArray());
                    lastOffsetAddedToBundle = currentOffset;
                } else {
                    break;
                }

                // TODO: when adding compression, add a corrective factor
                if (!minBytesThresholdMet && builder.estimatedSizeInBytes() > requestMinBytes) {
                    minBytesThresholdMet = true;
                }
                remainingTime = (int)(endTimestamp - Time.SYSTEM.milliseconds());
                dataToReturn = true;
            }
            memoryRecords = builder.build();

        } catch (SolaceMessageEntryException smExc) {
            log.debug("Exception caught processing consumer -- message: {}", smExc.getMessage());
            log.debug("Encapsulated error: {}", smExc.getError() != null ? smExc.getError().name() : "null");

            if (smExc.getCause() != null && smExc.getCause() instanceof JCSMPStateException) {
                // Unrecoverable
                log.error("JCSMP Exception from Solace Broker Session -- Cause: {}", smExc.getCause().getLocalizedMessage());
                return request.getErrorResponse(new UnknownServerException(smExc.getMessage(), smExc.getCause()));
            }

            if (smExc.getError() == Errors.UNKNOWN_SERVER_ERROR) {
                log.error("Unknown server error caught processing consumer -- message: {}", smExc.getMessage());
                if (smExc.getCause() != null) {
                    log.error("Unknown server error Cause: {}", smExc.getCause().getLocalizedMessage(), smExc.getCause());
                }
                return request.getErrorResponse(new UnknownServerException(smExc.getMessage()));
            } else {
                partitionErrorCode = smExc.getError();
            }
        } catch (Exception exc) {
            log.error("Unknown error caught processing consumer -- message: {}", exc.getLocalizedMessage());
            return request.getErrorResponse(new UnknownServerException(exc.getLocalizedMessage()));
        } finally {
            builder.close();
        }

        // Create response
        log.trace("Creating FetchResponse");
        final FetchResponseData responseData = new FetchResponseData();
        responseData.setThrottleTimeMs(0);
        if (sessionEligible) {
            responseData.setSessionId(sessionId);
            responseData.setErrorCode(Errors.NONE.code());
            if (sessionFetch && !dataToReturn && !firstSessionFetch) {
                log.trace("No data to return and not first session fetch, returning abbreviated response");
                responseData.setResponses(Collections.emptyList());
                return new FetchResponse(responseData);
            }
        }
        final FetchableTopicResponse topicResponse = new FetchableTopicResponse();
        if (topicIdEligible) {
            topicResponse.setTopicId(topicId);
        } else {
            topicResponse.setTopic(topicName);
        }
        final PartitionData partitionData = new PartitionData()
            .setErrorCode(partitionErrorCode.code())
            .setPartitionIndex(partitionIndex)
            .setHighWatermark(lastOffsetAddedToBundle + 1L)
            .setLastStableOffset(lastOffsetAddedToBundle + 1L)
            .setLogStartOffset(0L)
            .setRecords(memoryRecords);
        // TODO: May need to make Log start offset == OffsetCommit
        topicResponse.setPartitions(List.of(partitionData));
        responseData.setResponses(List.of(topicResponse));

        // Update last delivered offset for session fetches
        this.cachedLastDeliveredOffset = lastOffsetAddedToBundle;

        if (dataToReturn) {
            log.trace("Highest record offset delivered in last fetch response: {}", this.cachedLastDeliveredOffset);
            log.trace("Offsets returned in FetchResponse -- HighWaterMark: {}, LastStableOffset: {}, LogStartOffset: {}", partitionData.highWatermark(), partitionData.lastStableOffset(), partitionData.logStartOffset());
            log.trace("Consumer Flow Offsets: Highest Received: {}, (Actual) Committed: {}", solaceQueueConsumer.getReceivedOffset(), solaceQueueConsumer.getCommittedOffset());
            log.trace("Highest Offset Received and Committed in FetchResponse -- Received: {}, Committed: {}", this.cachedLastDeliveredOffset, solaceQueueConsumer.getCommittedOffset());
        } else {
            log.trace("Returning empty FETCH response");
        }
        lastSuccessfulFetchTimestamp = Time.SYSTEM.milliseconds();
        return new FetchResponse(responseData);
    }

    /**
     * This method will create a response to Offsets request from client APIKey=2
     * Should be called using createFetchChannelResponseWithLock() for sequential processing and safe multi-threading
     * DATA/FETCH CHANNEL
     * @param request 
     * @param requestHeader
     * @return AbstractResponse of sub-type FetchResponse or AbstractReponse containing an error
     */
    public AbstractResponse createListOffsetsResponse(
        final ListOffsetsRequest request,
        final RequestHeader requestHeader)
    {
        if (!subscribed) {
            log.error("Attempted to process ListOffsets request but no active subscription");
            return request.getErrorResponse(new InvalidRequestException("No active subscription"));
        }

        final ListOffsetsRequestData requestData = request.data();
        String topicName, subscribedTopicName;
        Integer partitionIndex;
        Long timestamp;
        Long offsetToStart;

        // Get data from request
        try {
            if (requestData.topics().size() != 1 || requestData.topics().get(0).partitions().size() != 1) {
                log.warn("Received {} request with topic/partition counts out of bounds, must be 1 each", ApiKeys.LIST_OFFSETS.name());
                return request.getErrorResponse(new InvalidRequestException("Received invalid ListOffsets request, topic/partition counts out of bounds, must be 1 each"));
            }
            topicName = requestData.topics().get(0).name();
            partitionIndex = requestData.topics().get(0).partitions().get(0).partitionIndex();
            timestamp = requestData.topics().get(0).partitions().get(0).timestamp();
            subscribedTopicName = this.subscribedTopicName;
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createListOffsetsResponse() -- Failed to parse ListOffsets request");
            return request.getErrorResponse(0, new InvalidRequestException("Invalid request"));
        }

        try {
            offsetToStart = OpConstants.LOG_START_OFFSET;   // TODO: Depending upon consumer behavior, may need to use committed offset (+1)
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createListOffsetsResponse() -- Failed to get Solace data for ListOffsets request");
            return request.getErrorResponse(0, new InternalError("Failed to get Solace data for ListOffsets request"));
        }

        log.trace("ListOffsetsResponse -- Offset To Start: {}", offsetToStart);
        if (timestamp > 0) {
            log.warn("Requested to find offset at timestamp: {} -- Not supported, returning next available offset", timestamp);
        }

        // Create Response
        Errors errorCode = Errors.NONE;
        int leaderEpoch = OpConstants.LEADER_ID_TOPIC_PARTITION;
        if (!topicName.contentEquals(subscribedTopicName) || partitionIndex != subscribedPartitionIndex) {
            offsetToStart = OpConstants.UNKNOWN_OFFSET;
            leaderEpoch = OpConstants.UNKNOWN_LEADER_EPOCH;
            errorCode = Errors.UNKNOWN_TOPIC_OR_PARTITION;
        }
        final ListOffsetsResponseData.ListOffsetsPartitionResponse responsePartition = 
            new ListOffsetsResponseData.ListOffsetsPartitionResponse()
                .setPartitionIndex(partitionIndex)
                .setLeaderEpoch(leaderEpoch)
                .setTimestamp(OpConstants.TIMESTAMP_START_LATEST)
                .setErrorCode(errorCode.code())
                .setOffset(offsetToStart);
        final ListOffsetsResponseData.ListOffsetsTopicResponse responseTopic = 
            new ListOffsetsResponseData.ListOffsetsTopicResponse()
                .setName(topicName)
                .setPartitions(List.of(responsePartition));
        final ListOffsetsResponseData responseData = new ListOffsetsResponseData()
                .setThrottleTimeMs(0)
                .setTopics(List.of(responseTopic));
        return new ListOffsetsResponse(responseData);
    }

    /**
     * This method creates Metadata response for a single topic, 
     * reporting a configured number of partitions -- Metadata, ApiKey=3
     * METADATA CHANNEL
     * @param request
     * @param requestHeader
     * @param listenPort
     * @return
     */
    public static AbstractResponse createMetadataResponse(
        final MetadataRequest request,
        final RequestHeader requestHeader,
        ListenPort listenPort)
    {
        final MetadataRequestData requestData = request.data();

        String topicName;
        try {
            topicName = requestData.topics().get(0).name();
        } catch (UnknownTopicOrPartitionException unknownTopicExc) {
            log.error("KafkaApiConsumerTools.createMetadataResponse() -- Only one topic is supported");
            return request.getErrorResponse(new InvalidRequestException("Only one topic is supported"));
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createMetadataResponse() -- Failed to parse Metadata request");
            return request.getErrorResponse(new InvalidRequestException("Failed to parse Metadata request"));
        }

        final List<MetadataResponseData.MetadataResponsePartition> partitionList = new ArrayList<>();

        for (int partitionId = 0; partitionId < partitionsPerTopic; partitionId++) {
            MetadataResponseData.MetadataResponsePartition partitionMetadata = new MetadataResponseData.MetadataResponsePartition()
                    .setPartitionIndex(partitionId).setErrorCode(Errors.NONE.code()).setLeaderEpoch(OpConstants.LEADER_EPOCH).setLeaderId(OpConstants.LEADER_ID_TOPIC_PARTITION)
                    .setReplicaNodes(Arrays.asList(0)).setIsrNodes(Arrays.asList(0))
                    .setOfflineReplicas(Collections.emptyList());
            partitionList.add(partitionMetadata);
        }
        MetadataResponseData.MetadataResponseTopicCollection topics = new MetadataResponseData.MetadataResponseTopicCollection();
        MetadataResponseData.MetadataResponseTopic topicMetadata = new MetadataResponseData.MetadataResponseTopic()
                .setName(topicName).setTopicId(Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topicName)))
                .setErrorCode(Errors.NONE.code()).setPartitions(partitionList)
                .setIsInternal(false);
        topics.add(topicMetadata);

        MetadataResponse metadataResponse = new MetadataResponse(
                new MetadataResponseData().setThrottleTimeMs(0).setBrokers(listenPort.brokers())
                        .setClusterId(listenPort.clusterId()).setControllerId(0).setTopics(topics),
                requestHeader.apiVersion());

        return metadataResponse;
    }

    /**
     * This method creates OffsetCommitResponse to request for ApiKey=8
     * The session must have an active queue subscription
     * GROUP CHANNEL
     * @param request
     * @param requestHeader
     * @return
     */
    public synchronized AbstractResponse createOffsetCommitResponse(
        final OffsetCommitRequest request,
        final RequestHeader requestHeader)
    {
        final OffsetCommitRequestData requestData = request.data();
        String topicName = "";
        Integer partitionIndex = -1;
        Long commitToOffset = -1L;

        try {
            topicName = requestData.topics().get(0).name();
            partitionIndex = requestData.topics().get(0).partitions().get(0).partitionIndex();
            commitToOffset = requestData.topics().get(0).partitions().get(0).committedOffset();
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createOffsetCommitResponse() -- Failed to parse OffsetCommit request");
            return request.getErrorResponse(new InvalidRequestException(exc.getMessage()));
        }

        if (!subscribed) {
            log.error("Attempted to process OffsetCommit request but no active subscription");
            return request.getErrorResponse(new UnknownServerException("No active subscription"));
        }

        // Delete old ack'd messages from memory
        // TODO: Evaluate if there is a better place to do this, maybe separate thread
        try {
            solaceQueueConsumer.deleteEligibleMessages();
        } catch (Exception exc) {
            // This should not happen
            log.error("Failed to delete eligible messages from memory", exc);
        }

        // Perform commit operation
        Errors errorCode = Errors.NONE;
        try {
            // Processed on the same channel as LeaveGroup
            solaceQueueConsumer.commitUpToOffset(commitToOffset);
        } catch (IllegalStateException exc) {
            if (! solaceQueueConsumer.isFlowActive()) {
                // Most likely shutting down
                errorCode = Errors.UNSTABLE_OFFSET_COMMIT;
                log.debug("Shutting down consumer flow");
            } else {
                // Probably something more serious
                log.error("Failed to commit offset to Solace queue: {}", exc.getLocalizedMessage());
                return request.getErrorResponse(exc);
            }
        } catch (OffsetOutOfRangeException offsetExc) {
            errorCode = Errors.UNKNOWN_SERVER_ERROR;
            log.error(offsetExc.getLocalizedMessage());
        } catch (Exception exc) {
            errorCode = Errors.UNKNOWN_SERVER_ERROR;
            log.error("OffsetCommit failed: {}", exc.getLocalizedMessage(), exc);
        }

        // Create response to operation
        OffsetCommitResponseData responseData = new OffsetCommitResponseData()
            .setThrottleTimeMs(0);
        OffsetCommitResponseTopic responseTopic = new OffsetCommitResponseTopic()
            .setName(topicName);
        OffsetCommitResponsePartition responsePartition = new OffsetCommitResponsePartition()
            .setPartitionIndex(partitionIndex)
            .setErrorCode(errorCode.code());
        responseTopic.setPartitions(List.of(responsePartition));
        responseData.setTopics(List.of(responseTopic));
        return new OffsetCommitResponse(responseData);
    }

    /**
     * This method creates the OffsetFetchResponse message for APIKey=9
     * Active Subscription to Solace Queue required
     * Called from Group Channel
     * @param request
     * @param requestHeader
     * @return
     */
    public synchronized AbstractResponse createOffsetFetchResponse(
        final OffsetFetchRequest request,
        final RequestHeader requestHeader)
    {
        if (!subscribed) {
            return request.getErrorResponse(new InvalidRequestException("No active subscription"));
        }
        final OffsetFetchRequestData requestData = request.data();
        final boolean topLevelTopic = requestHeader.apiVersion() <= 7;
        String groupId = null;
        String topicName = null;
        Integer partitionIndex = null;

        try {
            if (topLevelTopic) {
                groupId = requestData.groupId();
                topicName = requestData.topics().get(0).name();
                partitionIndex = requestData.topics().get(0).partitionIndexes().get(0);
            } else {
                groupId = requestData.groups().get(0).groupId();
                topicName = requestData.groups().get(0).topics().get(0).name();
                partitionIndex = requestData.groups().get(0).topics().get(0).partitionIndexes().get(0);
            }
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createOffsetFetchResponse() -- failed to parse OffsetFetch request");
            return request.getErrorResponse(0, Errors.INVALID_REQUEST);
        }

        /**
         * If the subscription exists then return the current offset
         * If a subscription exists and topic is different from the existing one, then return an error
         * If the subscription DNE failed then return error
         */
        // TODO: Set subscribed, errorCode based upon result
        Long committedOffset = OpConstants.UNKNOWN_OFFSET;
        if (topicName.contentEquals(this.subscribedTopicName) && groupId.contentEquals(this.consumerGroupId)) {
            try {
                committedOffset = solaceQueueConsumer.getCommittedOffset();
            } catch (Exception exc) {
                log.error("Error processing OffsetFetch request: {}", exc.getLocalizedMessage());
                return request.getErrorResponse(0, new UnknownServerException(exc.getLocalizedMessage()));
            }
        } else {
            log.warn(
                "Received {} request, subscribed topic {} does not match request topic {} -- returning error {}", 
                ApiKeys.OFFSET_FETCH.name(), 
                this.subscribedTopicName, 
                topicName,
                Errors.UNKNOWN_TOPIC_OR_PARTITION.code()
            );
            return request.getErrorResponse(0, 
                new UnknownTopicOrPartitionException("Topic subscribed does not match request"));
        }

        log.debug("OffsetFetch -- Committed Offset: {}", committedOffset);
        log.debug("Group Id: {}, TopicName: {}, PartitionIndex: {}", groupId, topicName, partitionIndex);

        // Create response
        final OffsetFetchResponseData responseData = new OffsetFetchResponseData();
        responseData
            .setThrottleTimeMs(0)
            .setErrorCode(Errors.NONE.code());
        if (topLevelTopic) {
            final OffsetFetchResponsePartition responsePartition = 
                new OffsetFetchResponsePartition()
                    .setPartitionIndex(partitionIndex)
                    .setCommittedOffset(committedOffset)
                    .setCommittedLeaderEpoch(OpConstants.UNKNOWN_COMMITTED_LEADER_EPOCH)
                    .setErrorCode(Errors.NONE.code());
            final OffsetFetchResponseTopic responseTopic = 
                new OffsetFetchResponseTopic()
                    .setName(topicName)
                    .setPartitions(List.of(responsePartition));
            responseData.setTopics(List.of(responseTopic));
        } else {
            final OffsetFetchResponsePartitions responsePartitions = 
                new OffsetFetchResponsePartitions()
                    .setPartitionIndex(partitionIndex)
                    .setCommittedOffset(committedOffset)
                    .setCommittedLeaderEpoch(OpConstants.UNKNOWN_COMMITTED_LEADER_EPOCH)
                    .setErrorCode(Errors.NONE.code());
            final OffsetFetchResponseTopics responseTopics = 
                new OffsetFetchResponseTopics()
                    .setName(topicName)
                    .setPartitions(List.of(responsePartitions));
            final OffsetFetchResponseGroup responseGroup = 
                new OffsetFetchResponseGroup()
                    .setGroupId(groupId)
                    .setTopics(List.of(responseTopics));
            responseData.setGroups(List.of(responseGroup));
        }
        return new OffsetFetchResponse(responseData, requestHeader.apiVersion());
    }

    /**
     * this method will create FindCoordinatorResponse for ApiKey=10 request
     * Subscription not required
     * @param request
     * @param listenPort
     * @return
     */
    public static AbstractResponse createFindCoordinatorResponse(
        FindCoordinatorRequest request, 
        RequestHeader requestHeader,
        ListenPort listenPort)
    {   
        // This is just a canned response to the request
        final FindCoordinatorRequestData requestData = request.data();
        final boolean multipleCoordinatorsEligible = requestHeader.apiVersion() > 3;
        String coordinatorKey = "";
        int nodeId = -1;
        String host = "unknown";
        int port = 0;

        Errors errorCode = Errors.NONE;
        String errorMessage = "";
        if (multipleCoordinatorsEligible) {
            try {
                coordinatorKey = requestData.coordinatorKeys().get(0);
            } catch (Exception exc) {
                errorCode = Errors.INVALID_REQUEST;
                errorMessage = "Failed to parse FindCoordinator request";
                log.error("KafkaApiConsumerTools.createFindCoordinatorResponse() -- Failed to parse FindCoordinator request");
            }
        } else {
            coordinatorKey = requestData.key();
        }

        if (errorCode == Errors.NONE) {
            try {
                final MetadataResponseBroker broker = listenPort.brokers().iterator().next();
                nodeId = broker.nodeId();
                host = broker.host();
                port = broker.port();
            } catch (Exception exc) {
                log.error("Could not extract broker details from Listeners");
                errorMessage = "Could not extract broker details from Listeners";
                errorCode = Errors.UNKNOWN_SERVER_ERROR;
            }
        }

        if (errorCode != Errors.NONE) {
            log.warn("Returning validation error response to {} request", ApiKeys.FIND_COORDINATOR.name);
            return request.getErrorResponse(0, new InvalidRequestException(errorMessage));
        }

        // Create response
        final FindCoordinatorResponseData responseData = new FindCoordinatorResponseData();
        responseData.setThrottleTimeMs(0);      // Don't care about throttling these
        if (!multipleCoordinatorsEligible) {
            responseData.setErrorCode(errorCode.code())
                .setErrorMessage(errorMessage)
                .setNodeId(nodeId)
                .setHost(host)
                .setPort(port);
        } else {
            FindCoordinatorResponseData.Coordinator coordinator = 
                new FindCoordinatorResponseData.Coordinator()
                    .setErrorCode(errorCode.code())
                    .setErrorMessage(errorMessage)
                    .setKey(coordinatorKey)
                    .setNodeId(nodeId)
                    .setHost(host)
                    .setPort(port);
            responseData.setCoordinators(List.of(coordinator));
        }
        return new FindCoordinatorResponse(responseData);
    }

    /**
     * this method creates a response to the JoinGroupRequest APIKey=11
     * It will select the first protocol in request. The protocol doesn't really matter
     * because Kafka consumers will never re-balance against the proxy.
     * Provided protocol metadata is reflected back in the response member metadata.
     * @param request
     * @return
     */
    public synchronized AbstractResponse createJoinGroupResponse(
        final JoinGroupRequest request,
        final RequestHeader requestHeader
    )
    {
        // TODO - Add capability to validate if Consumer Group exists?
        // "Consumer Group" exists would mean that there is a queue with prefix == requested group
        // TODO - Evaluate if proxy should select protocol by precedence (probably doesn not matter)
        // 'cooperative-sticky', 'range', 'round-robin' -- currently always uses 1st in list

        final JoinGroupRequestData requestData = request.data();

        // final String clientId = requestHeader.clientId();
        final String memberId = requestData.memberId();
        final String protocolType = requestData.protocolType();
        // final String groupId = requestData.groupId();

        String protocolName = null;
        byte[] protocolMetadata = null;

        try {
            final JoinGroupRequestProtocol protocol = requestData.protocols().iterator().next();
            protocolName = protocol.name();
            protocolMetadata = protocol.metadata();
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createJoinGroupResponse() -- Failed to parse JoinGroup request: -- Exception: {}", exc.getLocalizedMessage());
            return request.getErrorResponse(0, new InvalidRequestException(exc.getLocalizedMessage()));
        }

        final JoinGroupResponseData responseData = new JoinGroupResponseData();

        // This is normal - effectively asking the server for an ID
        if (memberId == null || memberId.isEmpty()) {
            log.info("Received {} Request with no memberId, assigning one", ApiKeys.JOIN_GROUP.name());
            responseData
                .setThrottleTimeMs(0)
                .setErrorCode(Errors.MEMBER_ID_REQUIRED.code())
                .setGenerationId(-1)
                .setMemberId(requestHeader.clientId() + "-" + UUID.randomUUID().toString());
            return new JoinGroupResponse(responseData, requestHeader.apiVersion());
        }

        // Create response
        responseData
            .setThrottleTimeMs(0)
            .setErrorCode(Errors.NONE.code())
            .setGenerationId(assignGenerationId())
            .setProtocolType(protocolType)
            .setProtocolName(protocolName)
            .setLeader(memberId)
            .setSkipAssignment(false)
            .setMemberId(memberId);
        responseData.setMembers(List.of(
            new JoinGroupResponseMember()
                .setMemberId(memberId)
                .setGroupInstanceId(null)
                .setMetadata(protocolMetadata)
        ));

        return new JoinGroupResponse(responseData, requestHeader.apiVersion());
    }

    /**
     * Create heartbeat response to request APIKey=12
     * Requires subscription
     * @param request
     * @param requestHeader
     * @return
     */
    public synchronized AbstractResponse createHeartbeatResponse(
        final HeartbeatRequest request,
        final RequestHeader requestHeader)
    {
        // Validations
        if (!subscribed) {
            log.warn("Received {} request but no active subscription", ApiKeys.HEARTBEAT.name());
            return request.getErrorResponse(0, new InvalidRequestException("No active subscription"));
        }
        HeartbeatResponseData responseData = new HeartbeatResponseData();
        responseData.setThrottleTimeMs(0);
        responseData.setErrorCode(Errors.NONE.code());
        return new HeartbeatResponse(responseData);
    }

    public static AbstractResponse createHeartbeatResponseRebalancing()
    {
        HeartbeatResponseData responseData = new HeartbeatResponseData();
        responseData.setThrottleTimeMs(0);
        responseData.setErrorCode(Errors.REBALANCE_IN_PROGRESS.code());
        return new HeartbeatResponse(responseData);
    }

    public static AbstractResponse createOffsetCommitResponseRebalancing(
        final OffsetCommitRequest request,
        final RequestHeader requestHeader
    )
    {
        final OffsetCommitRequestData requestData = request.data();
        final OffsetCommitResponseData responseData = new OffsetCommitResponseData();
        responseData.setThrottleTimeMs(0);
        responseData.setTopics(Collections.emptyList());
        requestData.topics().forEach( topic -> {
            OffsetCommitResponseData.OffsetCommitResponseTopic responseTopic = 
                new OffsetCommitResponseData.OffsetCommitResponseTopic()
                .setName(topic.name());
            responseTopic.setPartitions(Collections.emptyList());
            topic.partitions().forEach( partition -> {
                OffsetCommitResponseData.OffsetCommitResponsePartition responsePartition = 
                    new OffsetCommitResponseData.OffsetCommitResponsePartition()
                    .setPartitionIndex(partition.partitionIndex())
                    .setErrorCode(Errors.UNKNOWN_MEMBER_ID.code());
                responseTopic.partitions().add(responsePartition);
            });
            responseData.topics().add(responseTopic);
        });
        return new OffsetCommitResponse(responseData);
    }

    /**
     * Create LeaveGroupResponse to request APIKey=13
     * The method will also remove queue subscription
     * Active subscription required else return error
     * @param request
     * @param requestHeader
     * @return
     */
    public synchronized AbstractResponse createLeaveGroupResponse(
        final LeaveGroupRequest request,
        final RequestHeader requestHeader)
    {
        if (!subscribed) {
            log.error("Received {} request but no active subscription", ApiKeys.LEAVE_GROUP.name());
            return request.getErrorResponse(0, new InvalidRequestException("No active subscription"));
        }

        final LeaveGroupRequestData requestData = request.data();
        String memberId = "";
        String groupInstanceId = "";
        // Member ID can be in different places based upon Api version
        try {
            if (requestHeader.apiVersion() > 2) {
                memberId = requestData.members().get(0).memberId();
                groupInstanceId = requestData.members().get(0).groupInstanceId();
            } else {
                memberId = requestData.memberId();
            }
        } catch (Exception exc) {
            log.warn("Failed to parse LeaveGroup request -- but terminating consumer flow anyway");
        }

        final Errors leaveGroupResult = KafkaApiConsumerTools.stopConsumerFlowIfRunning(this);

        final LeaveGroupResponseData responseData = 
                new LeaveGroupResponseData()
                    .setThrottleTimeMs(0)
                    .setErrorCode(Errors.NONE.code());
        responseData.setMembers(List.of(
                new LeaveGroupResponseData.MemberResponse()
                    .setMemberId(memberId)
                    .setGroupInstanceId(groupInstanceId)
                    .setErrorCode(leaveGroupResult.code())));
        return new LeaveGroupResponse(responseData);
    }

    /**
     * Create SyncGroupResponse to requet APIKey=14
     * This method will create the subscription to Solace queue
     * @param request
     * @param requestHeader
     * @return
     */
    public synchronized AbstractResponse createSyncGroupResponse(
        final SyncGroupRequest request,
        final RequestHeader requestHeader)
    {
        final SyncGroupRequestData requestData = request.data();

        final String protocolName = requestData.protocolName();
        final String protocolType = requestData.protocolType();
        final String clientId = requestHeader.clientId();
        final String groupId = requestData.groupId();
        ByteBuffer memberAssignment, consumerAssignment;
        String topicName;
        Integer partitionIndex;

        try {
            memberAssignment = ByteBuffer.wrap(requestData.assignments().get(0).assignment());
            final Assignment assignment = ConsumerProtocol.deserializeAssignment(memberAssignment);
            topicName = assignment.partitions().get(0).topic();

            // Here we get the next partition index to use for this topic
            // The index must be unique while associating the Data/Fetch channel to the group coordinator channel
            partitionIndex = KafkaApiConsumerTools.assignPartitionIndex(topicName, this.subscribedPartitionIndex);
            final TopicPartition topicPartition = new TopicPartition(topicName, partitionIndex);          // Assign to one known partition
            consumerAssignment = ConsumerProtocol.serializeAssignment(new Assignment(List.of(topicPartition)));
        } catch (Exception exc) {
            log.error("Failed to parse SyncGroup request/member assignment -- Exception: {}", exc.getLocalizedMessage());
            return request.getErrorResponse(0, new InvalidRequestException("Failed to parse SyncGroup request/member assignment"));
        }
        
        /**
         * Here is where we subscribe to the queue
         */
        // TODO: Make Queue name prefix an optional configuration item
        String queueName = "KAFKA-PROXY-QUEUE/" + topicName + (groupId != null && !groupId.isEmpty() ? ("/" + groupId) : "");        // TODO: Derive queue name
        try {
            log.debug("Subscribing to queue: {}", queueName);
            this.solaceQueueConsumer = SolaceQueueConsumer.createAndStartQueueConsumer(solaceSession, queueName);
            this.subscribed = true;
            // Assign consumer identifiers
            this.consumerGroupId = groupId;
            this.subscribedTopicName = topicName;
            this.subscribedTopicId = Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topicName));
            this.subscribedPartitionIndex = partitionIndex;

            // Make this consumer tools instance/jcsmp session available to fetcher
            addAvailableConsumerToolsEntry(new ConsumerToolsKey(clientId, this.subscribedTopicId, partitionIndex), this);
            log.info("Subscribed to queue: {}", queueName);
        } catch (Exception exc) {
            /**
             * TODO: Return a better error here
             */
            log.error("Failed to subscribe to solace queue: {} -- Exception: {}", queueName, exc.getLocalizedMessage());
            return request.getErrorResponse(0, new UnknownServerException(Errors.UNKNOWN_SERVER_ERROR.message() + " - Subscription to solace queue failed"));
        }

        SyncGroupResponseData responseData = new SyncGroupResponseData();
        responseData
            .setThrottleTimeMs(0)
            .setErrorCode(Errors.NONE.code())
            .setProtocolType(protocolType)
            .setProtocolName(protocolName)
            .setAssignment(consumerAssignment.array());
        return new SyncGroupResponse(responseData);
    }

    /**
     * this method will create OffsetsForLeaderEpochResponse to request APIKey=23
     * --> The consumer flow must be associated from group connection and active
     * @param request
     * @param requestHeader
     * @return
     */
    public AbstractResponse createOffsetsForLeaderEpochResponse(
        final OffsetsForLeaderEpochRequest request,
        final RequestHeader requestHeader)
    {
        if (!consumerFlowSubscribed(this)) {
            log.error("Consumer tools cannot be null when processing {} request", ApiKeys.OFFSET_FOR_LEADER_EPOCH.name);
            return request.getErrorResponse(0, new UnknownServerException(new IllegalStateException("Consumer flow not subscribed or is null")));
        }
        final OffsetForLeaderEpochRequestData requestData = request.data();

        // long endOffset;
        String  subscribedTopicName;
        Integer subscribedPartitionIndex;
        try {
            if (requestData.topics().size() != 1 || requestData.topics().iterator().next().partitions().size() != 1 ) {
                log.warn("Received {} request with topic/partition counts out of bounds, must be 1 each", ApiKeys.OFFSET_FOR_LEADER_EPOCH.name);
                return request.getErrorResponse(new InvalidRequestException("Received invalid OffsetsForLeaderEpoch request, topic/partition counts out of bounds, must be 1 each"));
            }
            // endOffset = this.getSolaceQueueConsumer().getReceivedOffset();
            subscribedTopicName = this.getSubscribedTopicName();
            subscribedPartitionIndex = this.getSubscribedPartitionIndex();
        } catch (Exception exc) {
            log.error("KafkaApiConsumerTools.createOffsetsForLeaderEpochResponse() -- Exception: {}", exc.getLocalizedMessage());
            return request.getErrorResponse(0, new UnknownServerException(exc.getLocalizedMessage()));
        }

        // Create response
        final OffsetForLeaderEpochResponseData responseData = new OffsetForLeaderEpochResponseData();
        final OffsetForLeaderTopicResultCollection resultTopicCollection = new OffsetForLeaderTopicResultCollection();
        responseData.setThrottleTimeMs(0);
        requestData.topics().forEach( requestTopic -> {
            final OffsetForLeaderEpochResponseData.OffsetForLeaderTopicResult topicResult = 
                new OffsetForLeaderEpochResponseData.OffsetForLeaderTopicResult()
                    .setTopic(requestTopic.topic());
            requestTopic.partitions().forEach( requestPartition -> {
                final EpochEndOffset epochEndOffset = new EpochEndOffset();
                if (requestPartition.partition() != subscribedPartitionIndex || (!requestTopic.topic().contentEquals(subscribedTopicName))) {
                    epochEndOffset
                        .setErrorCode(Errors.UNKNOWN_TOPIC_OR_PARTITION.code())
                        .setPartition(requestPartition.partition())
                        .setLeaderEpoch(OpConstants.UNKNOWN_LEADER_EPOCH)
                        .setEndOffset(OpConstants.UNKNOWN_OFFSET);
                } else {
                    epochEndOffset
                        .setErrorCode(Errors.NONE.code())
                        .setPartition(requestPartition.partition())
                        // .setEndOffset(endOffset)
                        .setEndOffset(this.cachedLastDeliveredOffset + 1L)
                        .setLeaderEpoch(OpConstants.LEADER_EPOCH);
                }
                topicResult.setPartitions(List.of(epochEndOffset));
            });
            resultTopicCollection.add(topicResult);
        });
        responseData.setTopics(resultTopicCollection);
        return new OffsetsForLeaderEpochResponse(responseData);
    }
}
