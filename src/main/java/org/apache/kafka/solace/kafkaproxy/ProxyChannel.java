package org.apache.kafka.solace.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import static org.apache.kafka.common.protocol.ApiKeys.API_VERSIONS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.InitProducerIdRequestData;
import org.apache.kafka.common.message.InitProducerIdResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.message.ProduceResponseData.PartitionProduceResponse;
import org.apache.kafka.common.message.ProduceResponseData.TopicProduceResponse;
import org.apache.kafka.common.message.SaslAuthenticateResponseData;
import org.apache.kafka.common.message.SaslHandshakeResponseData;
import org.apache.kafka.common.network.ByteBufferSend;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.ApiVersionsRequest;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.HeartbeatRequest;
import org.apache.kafka.common.requests.InitProducerIdRequest;
import org.apache.kafka.common.requests.InitProducerIdResponse;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.requests.LeaveGroupRequest;
import org.apache.kafka.common.requests.ListOffsetsRequest;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.OffsetFetchRequest;
import org.apache.kafka.common.requests.OffsetsForLeaderEpochRequest;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.RequestAndSize;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.SaslAuthenticateRequest;
import org.apache.kafka.common.requests.SaslAuthenticateResponse;
import org.apache.kafka.common.requests.SaslHandshakeResponse;
import org.apache.kafka.common.requests.SyncGroupRequest;
import org.apache.kafka.common.utils.AbstractIterator;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.CloseableIterator;
import org.apache.kafka.solace.kafkaproxy.consumer.KafkaApiConsumerTools;
import org.apache.kafka.solace.kafkaproxy.consumer.SupportedApiVersions;
import org.apache.kafka.solace.kafkaproxy.util.OpConstants;
import org.apache.kafka.solace.kafkaproxy.util.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

public class ProxyChannel {
	private static final Logger log = LoggerFactory.getLogger(ProxyChannel.class);
	private final Queue<Send> sendQueue;
	private final TransportLayer transportLayer;
	private ProxyPubSubPlusSession session;
	private final ByteBuffer size; // holds the size field (first 4 bytes) of a received message
	private ByteBuffer buffer; // byte buffer used to hold all of message except for first 4 bytes
	private int requestedBufferSize = -1;
	private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
	private static final List<String> saslMechanisms = List.of("PLAIN");
	private ProxySasl proxySasl = new ProxySasl();
	private boolean enableKafkaSaslAuthenticateHeaders;
	private ProxyReactor.ListenPort listenPort;
	private ProduceResponseProcessing produceResponseProcesssing;
    private int inFlightRequestCount = 0;   // count of requests being processed asynchronously (e.g. authentication or produce requests)
    private RequestAndSize savedRequestAndSize = null;   // saved information for request that could not be processed immediately
    private RequestHeader savedRequestHeader = null;     // saved information for request that could not be processed immediately
	private KafkaApiConsumerTools kafkaApiConsumerTools;
	private boolean idempotentProducer = false;
	private boolean firstRequestWithProducerId = true;
	private long recordBatchSequenceNumber = 0L;
	private boolean consumerMetadataChannel = false;

	private static int channelIdGenerator = 0;
	private int channelId = channelIdGenerator++;

	final static class ProduceAckState extends ProxyReactor.WorkEntry {
		private final String topic;
		private final ProduceResponseData.TopicProduceResponseCollection topicProduceResponseCollection;
		private final RequestHeader requestHeader;
		private final boolean lastInTopic;
		private final boolean lastInRequest;
		private final boolean worked;

		public ProduceAckState(ProxyChannel proxyChannel, String topic,
				ProduceResponseData.TopicProduceResponseCollection topicProduceResponseCollection,
				RequestHeader requestHeader, boolean lastInTopic, boolean lastInRequest) {
			super(proxyChannel);
			this.topic = topic;
			this.topicProduceResponseCollection = topicProduceResponseCollection;
			this.requestHeader = requestHeader;
			this.lastInTopic = lastInTopic;
			this.lastInRequest = lastInRequest;
			this.worked = true;
		}

		// creates new ProduceAckState from existing one but sets new value for 'worked'
		// Everything is final and it is very unusual for 'worked' to not be true, so in
		// failure cases we construct a new entry from an existing entry
		public ProduceAckState(ProduceAckState srcState, boolean worked) {
			super(srcState.getProxyChannel());
			this.topic = srcState.getTopic();
			this.topicProduceResponseCollection = srcState.getTopicProduceResponseCollection();
			this.requestHeader = srcState.getRequestHeader();
			this.lastInTopic = srcState.getLastInTopic();
			this.lastInRequest = srcState.getLastInRequest();
			this.worked = worked;
		}

		public String getTopic() {
			return topic;
		}

		public ProduceResponseData.TopicProduceResponseCollection getTopicProduceResponseCollection() {
			return topicProduceResponseCollection;
		}

		public RequestHeader getRequestHeader() {
			return requestHeader;
		}

		public boolean getLastInTopic() {
			return lastInTopic;
		}

		public boolean getLastInRequest() {
			return lastInRequest;
		}

		public boolean getWorked() {
			return worked;
		}

		@Override
		public String toString() {
			final String colString = (topicProduceResponseCollection == null) ? "null"
					: topicProduceResponseCollection.toString();
			final String hdrString = (requestHeader == null) ? "null" : requestHeader.toString();
			return "ProduceAckState{" + "topic=" + topic + ", topicProduceResponseCollection=" + colString
					+ ", requestHeader=" + hdrString + ", lastInTopic=" + lastInTopic + ", lastInRequest="
					+ lastInRequest + ", worked=" + worked + "}";
		}
	}

	final static class AuthorizationResult extends ProxyReactor.WorkEntry {
		private final RequestHeader requestHeader;
		private final boolean worked;

		public AuthorizationResult(ProxyChannel proxyChannel, RequestHeader requestHeader) {
			super(proxyChannel);
			this.requestHeader = requestHeader;
			this.worked = true;
		}

		// creates new AuthorizationResult from existing one but sets new value for
		// 'worked'
		// Everything is final and it is very unusual for 'worked' to not be true, so in
		// failure cases we construct a new entry from an existing entry
		public AuthorizationResult(AuthorizationResult srcResult, boolean worked) {
			super(srcResult.getProxyChannel());
			this.requestHeader = srcResult.getRequestHeader();
			this.worked = worked;
		}

		public RequestHeader getRequestHeader() {
			return requestHeader;
		}

		public boolean getWorked() {
			return worked;
		}

		@Override
		public String toString() {
			final String hdrString = (requestHeader == null) ? "null" : requestHeader.toString();
			return "AuthorizationResult{" + ", requestHeader=" + hdrString + ", worked=" + worked + "}";
		}
	}

	// Add this inner class to ProxyChannel.java
	final static class FetchResponseResult extends ProxyReactor.WorkEntry {
	    private final AbstractResponse response;
	    private final RequestHeader requestHeader;
	    private final ApiKeys apiKey = ApiKeys.FETCH;
	    private final boolean worked;
	    private final Exception exception;

	    public FetchResponseResult(ProxyChannel proxyChannel, AbstractResponse response, RequestHeader requestHeader, boolean worked, Exception exception) {
	        super(proxyChannel);
	        this.response = response;
	        this.requestHeader = requestHeader;
	        this.worked = worked;
	        this.exception = exception;
	    }

	    public AbstractResponse getResponse() { return response; }
	    public RequestHeader getRequestHeader() { return requestHeader; }
	    public ApiKeys getApiKey() { return apiKey; }
	    public boolean getWorked() { return worked; }
	    public Exception getException() { return exception; }

	    @Override
	    public String toString() {
	        return "FetchResponseResult{response=" + (response != null ? response.getClass().getSimpleName() : "null") + ", requestHeaderCorId=" + (requestHeader != null ? requestHeader.correlationId() : "null") + ", worked=" + worked + ", exception=" + (exception != null ? exception.getMessage() : "null") + "}";
	    }
	}

	final static class OffsetCommitResponseResult extends ProxyReactor.WorkEntry {
	    private final AbstractResponse response;
	    private final RequestHeader requestHeader;
	    private final ApiKeys apiKey = ApiKeys.OFFSET_COMMIT;
	    private final boolean worked;
	    private final Exception exception;

	    public OffsetCommitResponseResult(ProxyChannel proxyChannel, AbstractResponse response, RequestHeader requestHeader, boolean worked, Exception exception) {
	        super(proxyChannel);
	        this.response = response;
	        this.requestHeader = requestHeader;
	        this.worked = worked;
	        this.exception = exception;
	    }

	    public AbstractResponse getResponse() { return response; }
	    public RequestHeader getRequestHeader() { return requestHeader; }
	    public ApiKeys getApiKey() { return apiKey; }
	    public boolean getWorked() { return worked; }
	    public Exception getException() { return exception; }

	    @Override
	    public String toString() {
	        return "OffsetCommitResponseResult{response=" + (response != null ? response.getClass().getSimpleName() : "null") + ", requestHeaderCorId=" + (requestHeader != null ? requestHeader.correlationId() : "null") + ", worked=" + worked + ", exception=" + (exception != null ? exception.getMessage() : "null") + "}";
	    }
	}
	
	final static class Close extends ProxyReactor.WorkEntry {
		private final String reason;

		public Close(ProxyChannel proxyChannel, String reason) {
			super(proxyChannel);
			this.reason = reason;
		}

		public String getReason() {
			return reason;
		}

		@Override
		public String toString() {
			return "Close{" + "reason=" + reason + "}";
		}
	}

	private class ProduceResponseProcessing {
		private boolean ackAccumulator;
		private RequestHeader requestHeader;
		private ProduceResponseData.TopicProduceResponseCollection topicProduceResponseCollection;

		ProduceResponseProcessing() {
			ackAccumulator = true; // indicates publish worked
		}

		void handleProduceAckState(ProduceAckState produceAckState) {
			if (!produceAckState.getWorked())
				ackAccumulator = false; // set to false for any failure seen
			if (produceAckState.getTopicProduceResponseCollection() != null) {
				topicProduceResponseCollection = produceAckState.getTopicProduceResponseCollection();
			}
			if (produceAckState.getRequestHeader() != null) {
				requestHeader = produceAckState.getRequestHeader();
			}
			/// TBD properly set error code if there was an ack error
			if (produceAckState.getLastInTopic()) {
				topicProduceResponseCollection.add(new ProduceResponseData.TopicProduceResponse()
						.setName(produceAckState.getTopic()).setPartitionResponses(
								Collections.singletonList(new ProduceResponseData.PartitionProduceResponse().setIndex(0)
										// No error code that really maps well for error case
										.setErrorCode(
												ackAccumulator ? Errors.NONE.code() : Errors.KAFKA_STORAGE_ERROR.code())
										.setBaseOffset(-1).setLogAppendTimeMs(-1).setLogStartOffset(0))));
				ackAccumulator = true; // reset ack state for next topic
			}
			if (produceAckState.getLastInRequest()) {
				ProduceResponse produceResponse = new ProduceResponse(
						new ProduceResponseData().setThrottleTimeMs(0).setResponses(topicProduceResponseCollection));
				Send send = produceResponse.toSend(requestHeader.toResponseHeader(), requestHeader.apiVersion());
				try {
					ProxyChannel.this.dataToSend(send, requestHeader.apiKey());
					// /* no not log PRODUCE responses - too voluminous */);
					topicProduceResponseCollection = null;
					requestHeader = null;
					ackAccumulator = true; // set up for response
				} catch (IOException e) {
					ProxyChannel.this.close("Could not send PRODUCE response: " + e);
				}
			}
		}
	}

	ProxyChannel(SocketChannel socketChannel, TransportLayer transportLayer, ProxyReactor.ListenPort listenPort)
			throws IOException {
		this.transportLayer = transportLayer;
		this.listenPort = listenPort;
		size = ByteBuffer.allocate(4);
		enableKafkaSaslAuthenticateHeaders = false;
		produceResponseProcesssing = new ProxyChannel.ProduceResponseProcessing();
		listenPort.addChannel(this);
		sendQueue = new LinkedList<Send>();
	}

	String getHostName() {
		if (transportLayer != null) {
			return transportLayer.socketChannel().socket().getInetAddress().getHostName();
		} else {
			return "";
		}
	}

	ProxyReactor.ListenPort getListenPort() {
		return listenPort;
	}

	private Level logLevelForApiKey(ApiKeys apiKey) {
		return apiKey == ApiKeys.PRODUCE || apiKey == ApiKeys.FETCH || apiKey == ApiKeys.OFFSET_COMMIT || apiKey == ApiKeys.HEARTBEAT ? Level.TRACE : Level.DEBUG;
	}

	// normally we will not end up with buffered data so we avoid
	// adding the new send to the sendQueue, only doing so if necessary
	private void dataToSend(Send send, ApiKeys apiKey) throws IOException {
        // We do not log PRODUCE responses as too voluminous
        // if (apiKey != null) {
        //     log.debug("Sending " + apiKey + " response (remote " + 
        //               transportLayer.socketChannel().socket().getRemoteSocketAddress()
        //               + ")");
        // }
		log.atLevel(logLevelForApiKey(apiKey))
				.log("[Channel {}] SEND    -> {} APIKey={} -- Remote: {}", 
						this.channelId, apiKey.name(), apiKey.ordinal(), transportLayer.socketChannel().socket().getRemoteSocketAddress());

		if (sendQueue.isEmpty()) {
			send.writeTo(transportLayer);
			if (!send.completed()) {
				sendQueue.add(send);
				transportLayer.addInterestOps(SelectionKey.OP_WRITE);
			}
		} else {
			sendQueue.add(send);
			// send queue was not empty before so we must already have write interest
		}
	}

	public void authorizationResult(RequestHeader requestHeader, boolean worked) {
		try {
			SaslAuthenticateResponse saslAuthenticateResponse;
			// For versions with SASL_AUTHENTICATE header, send a response to
			// SASL_AUTHENTICATE request even if token is empty.
			if (worked) {
				proxySasl.setComplete(true);
			}
			if (enableKafkaSaslAuthenticateHeaders) {
				if (worked) {
					saslAuthenticateResponse = new SaslAuthenticateResponse(new SaslAuthenticateResponseData()
							.setErrorCode(Errors.NONE.code()).setAuthBytes(new byte[0]).setSessionLifetimeMs(0L));
				} else {
					saslAuthenticateResponse = new SaslAuthenticateResponse(
							new SaslAuthenticateResponseData().setErrorCode(Errors.SASL_AUTHENTICATION_FAILED.code())
									.setAuthBytes(new byte[0]).setSessionLifetimeMs(0L));
				}
				Send send = saslAuthenticateResponse.toSend(requestHeader.toResponseHeader(),
						requestHeader.apiVersion());
				dataToSend(send, ApiKeys.SASL_AUTHENTICATE);
			} else {
				if (worked) {
					Send netOutBuffer = ByteBufferSend.sizePrefixed(ByteBuffer.wrap(new byte[0]));
					dataToSend(netOutBuffer, ApiKeys.SASL_AUTHENTICATE);
				} else {
					// TBD - how to report an error with no kafka heaader for SASL?
					Send netOutBuffer = ByteBufferSend.sizePrefixed(ByteBuffer.wrap(new byte[0]));
					dataToSend(netOutBuffer, ApiKeys.SASL_AUTHENTICATE);
				}
			}
			if (!worked) {
				close("due to authentication failure");
			}
		} catch (Exception e) {
			log.info("Could not send authorization result: " + e);
			close("due to could not send authentication result");
		}
	}

	// returns true if caller should keep reading & parsing, false to stop
	private boolean parseRequest(ByteBuffer buffer) throws IOException, SaslAuthenticationException {
		RequestHeader header;
		ApiKeys apiKey;
		if (enableKafkaSaslAuthenticateHeaders || !proxySasl.authenticating()) {
			header = RequestHeader.parse(buffer);
			apiKey = header.apiKey();
            // do not log PRODUCE requests - too voluminous
            // if (apiKey != ApiKeys.PRODUCE) {
                // log.debug("Received " + apiKey + " request (remote " + 
                //           transportLayer.socketChannel().socket().getRemoteSocketAddress()
                //           + ")");
				//
				log.atLevel(logLevelForApiKey(apiKey))
						.log("[Channel {}] RECEIVE -> {} APIKey={}, v={} -- Remote: {}", 
								this.channelId, apiKey.name(), apiKey.ordinal(), header.apiVersion(), transportLayer.socketChannel().socket().getRemoteSocketAddress());

            // }
			proxySasl.adjustState(apiKey);
		} else {
            log.debug("Received SASL authentication request without Kafka header (remote " + 
                      transportLayer.socketChannel().socket().getRemoteSocketAddress()
                      + ")");
			byte[] clientToken = new byte[buffer.remaining()];
			buffer.get(clientToken, 0, clientToken.length);
			ProxyChannel.AuthorizationResult authResult = new ProxyChannel.AuthorizationResult(this, null);
            inFlightRequestCount++;
			try {
				session = proxySasl.authenticate(authResult, clientToken);
				// authorizationResult(null, true);				// This will report success before authentication completes
			} catch (Exception e) {
				inFlightRequestCount--; // Decrement since the async operation could not be started
				log.info("Sasl authentication failed: " + e);
				authorizationResult(null, false);
			}
			return false;
		}
        
		short apiVersion = header.apiVersion();
		RequestAndSize requestAndSize;
		if (apiKey == API_VERSIONS && !API_VERSIONS.isVersionSupported(apiVersion)) {
			// TODO: Figure out why this did not work for request from Kafka client v3.9
			ApiVersionsRequest apiVersionsRequest = new ApiVersionsRequest(new ApiVersionsRequestData(), (short) 0,
					Short.valueOf(header.apiVersion()));
			requestAndSize = new RequestAndSize(apiVersionsRequest, 0);
			return handleRequest(requestAndSize, header);
		} else {
			try {
				requestAndSize = AbstractRequest.parseRequest(apiKey, apiVersion, buffer);
			} catch (Throwable ex) {
				log.trace("Error thrown handing request: {}, v={}, ex", API_VERSIONS.name(), apiVersion, ex);
				throw new InvalidRequestException(
						"Error getting request for apiKey: " + apiKey + ", apiVersion: " + header.apiVersion(), ex);
			}
			return handleRequest(requestAndSize, header);
		}
	}
    
    // Delays a request that cannot be immediately handled due to other in-flight requests that are asynchronous
    // in nature (e.g. PRODUCE request). This delays a request that we normally handle synchronously.
	private boolean delayRequest(RequestAndSize requestAndSize, RequestHeader requestHeader)
            throws InvalidRequestException {
		log.trace("Delaying request: ApiKey={}, v={}", requestHeader.apiKey(), requestHeader.apiVersion());
        if ((savedRequestAndSize == null) && (savedRequestHeader == null)) {
            savedRequestAndSize = requestAndSize;
            savedRequestHeader = requestHeader;
            // We stop reading from the channel until this saved request can be processed (when no more requests in flight)
            transportLayer.removeInterestOps(SelectionKey.OP_READ);
        } else {
            throw new InvalidRequestException("Attempt to delay request when another request already delayed");
        }
        return false; // stop reading messages
    }

	// returns true if caller should keep reading & parsing, false to stop
	private boolean handleRequest(RequestAndSize requestAndSize, RequestHeader requestHeader)
			throws IOException, InvalidRequestException, SaslAuthenticationException {

		short version = requestHeader.apiVersion();
		ApiKeys apiKey = requestAndSize.request.apiKey();

		switch (apiKey) {
			case PRODUCE: {
				// ApiKey=0
				// Produce Data Channel

				ProduceRequest produceRequest = (ProduceRequest) requestAndSize.request;
				// First we need to determine the number of topic records

                Iterator<ProduceRequestData.TopicProduceData> it = produceRequest.data().topicData().iterator();
                String topicName = "", solaceTopicName = "";
                ProduceResponseData.TopicProduceResponseCollection topicResponseCollection = new ProduceResponseData.TopicProduceResponseCollection(
                        2);
                while (it.hasNext()) {
                    ProduceRequestData.TopicProduceData topicProduceData = it.next();
                    topicName = topicProduceData.name();
					solaceTopicName = ProxyUtils.getProducerTopicNameToPublish(topicProduceData.name());
					int partitionCount = 0;
                    for (ProduceRequestData.PartitionProduceData partitionData : topicProduceData.partitionData()) {
                        // We only advertise one partition per topic, so should only have one
                        // partition per topic that is published to, and it should always be
                        // partition 0
                        partitionCount++;
                        if (partitionCount > 1) {
                            throw new InvalidRequestException(
                                    "More than one partition per topic in PRODUCE request, topic: " + topicName);
                        }
                        if (partitionData.index() != 0) {
                            throw new InvalidRequestException("Invalid partition index in PRODUCE for topic: " + topicName
                                    + ", index: " + partitionData.index());
                        }

                        int recordCount = 0;
                        MemoryRecords records = (MemoryRecords) partitionData.records();

						// If idempotent producer, detect potential duplicates and reject if found
						// Assumes that the sequence numbers always increment between batches and gaps are Ok
						if (idempotentProducer) {
	                        AbstractIterator<MutableRecordBatch> batchIt = records.batchIterator();
							int lastSeqInRequest = 0;
							while (batchIt.hasNext()) {
								MutableRecordBatch batch = batchIt.next();
								if (firstRequestWithProducerId) {
									recordBatchSequenceNumber = batch.baseSequence();
									firstRequestWithProducerId = false;
								} else {
									if (recordBatchSequenceNumber >= batch.baseSequence() || lastSeqInRequest >= batch.baseSequence() ) {
										// WARN and REJECT the request
										log.warn("Invalid sequence number detected in PRODUCE request, possible duplicate records -- Rejecting with error: {}", Errors.DUPLICATE_SEQUENCE_NUMBER.message());
										ProduceResponseData responseData = new ProduceResponseData().setThrottleTimeMs(0);
										ProduceResponseData.TopicProduceResponseCollection responseCollection = new ProduceResponseData.TopicProduceResponseCollection(1);
										responseCollection.add(new TopicProduceResponse()
											.setName(topicName)
											.setPartitionResponses(List.of(
												// new PartitionProduceResponse().setIndex(assignedPartitionId)
												new PartitionProduceResponse().setIndex(0)
													.setErrorCode(Errors.DUPLICATE_SEQUENCE_NUMBER.code())
													.setBaseOffset(0)
													.setLogAppendTimeMs(-1)
													.setLogStartOffset(0))
											)
										);
										responseData.setResponses(responseCollection);
										ProduceResponse produceResponse = new ProduceResponse(responseData);
										Send send = produceResponse.toSend(requestHeader.toResponseHeader(), version);
										dataToSend(send, apiKey);
										break;
									}
								}
								lastSeqInRequest = batch.baseSequence();
							}
							recordBatchSequenceNumber = lastSeqInRequest;
						}

                        AbstractIterator<MutableRecordBatch> batchIt = records.batchIterator();
                        while (batchIt.hasNext()) {
                            recordCount++;
                            MutableRecordBatch batch = batchIt.next();
                            BufferSupplier.GrowableBufferSupplier supplier = new BufferSupplier.GrowableBufferSupplier();

							try(CloseableIterator<Record> recordIt = batch.streamingIterator(supplier);) {
								while (recordIt.hasNext()) {
									Record record = recordIt.next();
									final byte[] payload;
									if (record.hasValue()) {
										payload = new byte[record.value().remaining()];
										record.value().get(payload);
									} else {
										payload = new byte[0];
									}
									final byte[] key;
									if (record.hasKey()) {
										key = new byte[record.key().remaining()];
										record.key().get(key);
									} else {
										key = null;
									}
									final ProduceAckState produceAckState = new ProduceAckState(this, topicName,
											topicResponseCollection, requestHeader, !recordIt.hasNext() /* lastInTopic */,
											!recordIt.hasNext() && !it.hasNext());
									inFlightRequestCount++;
									topicResponseCollection = null;
									requestHeader = null;
									session.publish(solaceTopicName, payload, key, produceAckState);
								}

							} catch (Exception e) {
								throw e;
							}

                        }
						
                        // We do not want to deal with no records for a topic
                        if (recordCount == 0) {
                            throw new InvalidRequestException("No records in PRODUCE request, topic: " + topicName);
                        }
                    }
                }
                break;
            }
            case FETCH: {
				// ApiKey=1
				// Fetch Data Channel
				// Consumers can start reading without retrieving offsets
				if (kafkaApiConsumerTools == null) {
					try {
						log.debug("Assigning KafkaApiConsumerTools from Group channel in FETCH block");
						kafkaApiConsumerTools = KafkaApiConsumerTools.getConsumerToolsInstance(requestAndSize.request, requestHeader);
					} catch (Exception exc) {
						log.error("[Channel {}] Error obtaining KafkaApiConsumerTools for FETCH: {}", this.channelId, exc.getMessage(), exc);
						Send send = requestAndSize.request.getErrorResponse(0, exc).toSend(requestHeader.toResponseHeader(), version);
						dataToSend(send, apiKey);
						break;
					}
				}

				final FetchRequest fetchRequest = (FetchRequest) requestAndSize.request;
                final RequestHeader originalRequestHeader = requestHeader; // Capture for lambda/runnable

                inFlightRequestCount++;
                log.trace("[Channel {}] Offloading FETCH request (CorrId: {}) to executor. In-flight: {}", this.channelId, originalRequestHeader.correlationId(), inFlightRequestCount);

                ProxyReactor.getRequestHandlerExecutor().submit(() -> {
                    AbstractResponse taskResponse = null;
                    Exception taskException = null;
                    boolean taskWorked = false;
                    try {
                        // This is the blocking call
                        taskResponse = kafkaApiConsumerTools.createFetchChannelResponseWithLock(fetchRequest, originalRequestHeader);
                        taskWorked = true;
                    } catch (Exception e) {
                        log.error("[Channel {}] Exception in async FETCH processing (CorrId: {}): {}", this.channelId, originalRequestHeader.correlationId(), e.getMessage(), e);
                        taskException = e;
                        try { // Attempt to create a standard Kafka error response
                            taskResponse = fetchRequest.getErrorResponse(0, e);
                        } catch (Exception inner_e) {
                            log.error("[Channel {}] Could not create error response for FETCH (CorrId: {}): {}", this.channelId, originalRequestHeader.correlationId(), inner_e.getMessage(), inner_e);
                        }
                    } finally {
                        FetchResponseResult result = new FetchResponseResult(this, taskResponse, originalRequestHeader, taskWorked, taskException);
                        result.addToWorkQueue(); // Add to ProxyReactor's workQueue
                    }
                });
                return false; // Stop reading from channel, wait for async result via workQueue
            }
            case LIST_OFFSETS: {
				// ApiKey=2
				// Fetch Data Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

				// Get Consumer tools instance if not assigned to connection
				if (kafkaApiConsumerTools == null) {
					try {
						log.debug("Assigning KafkaApiConsumerTools from Group channel in LIST_OFFSETS block");
						kafkaApiConsumerTools = KafkaApiConsumerTools.getConsumerToolsInstance(requestAndSize.request, requestHeader);
					} catch (Exception exc) {
						Send send = requestAndSize.request.getErrorResponse(0, exc).toSend(requestHeader.toResponseHeader(), version);
						dataToSend(send, apiKey);
						break;
					}
				}

				ListOffsetsRequest listOffsetsRequest = (ListOffsetsRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse listOffsetsResponse = kafkaApiConsumerTools.createFetchChannelResponseWithLock(listOffsetsRequest, requestHeader);
				Send send = listOffsetsResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

				break;
            }
            case METADATA: {
				// ApiKey=3, Stateless (sort of)
				// METADATA Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

                MetadataRequest metadataRequest = (MetadataRequest) requestAndSize.request; // Safe cast after parseRequest
                MetadataRequestData data = metadataRequest.data();

				if (data.topics().size() != 1) {
					log.error("Received {} request with topic count == {}, must == 1", apiKey.name(), data.topics().size());
					Send send = requestAndSize.request.getErrorResponse(0, new InvalidRequestException("Received {} request with invalid topic count, must == 1")).toSend(requestHeader.toResponseHeader(), version);
					dataToSend(send, apiKey);
					break;
				}

				final String topicName = data.topics().get(0).name();
				if (ProxyUtils.isProducerTopic(topicName)) {
					log.debug("This is a PRODUCER connection");
				} else {
					log.debug("This is a CONSUMER connection");
					consumerMetadataChannel = true;
				}

				AbstractResponse metadataResponse;
				if (consumerMetadataChannel) {
					metadataResponse = KafkaApiConsumerTools.createMetadataResponse(metadataRequest, requestHeader, listenPort);
				} else {
					MetadataResponseData.MetadataResponsePartition partitionMetadata = new MetadataResponseData.MetadataResponsePartition()
							.setPartitionIndex(0).setErrorCode(Errors.NONE.code()).setLeaderEpoch(OpConstants.LEADER_EPOCH).setLeaderId(OpConstants.LEADER_ID_TOPIC_PARTITION)
							.setReplicaNodes(Arrays.asList(0)).setIsrNodes(Arrays.asList(0))
							.setOfflineReplicas(Collections.emptyList());
					List<MetadataResponseData.MetadataResponsePartition> partitionList = Collections.singletonList(partitionMetadata);
					MetadataResponseData.MetadataResponseTopicCollection topics = new MetadataResponseData.MetadataResponseTopicCollection();

					for (MetadataRequestData.MetadataRequestTopic topic : data.topics()) {
						MetadataResponseData.MetadataResponseTopic topicMetadata = new MetadataResponseData.MetadataResponseTopic()
								.setName(topic.name()).setTopicId(Uuid.fromString(ProxyUtils.generateMDAsUuidV4AsBase64String(topic.name())))
								.setErrorCode(Errors.NONE.code()).setPartitions(partitionList)
								.setIsInternal(false);
						topics.add(topicMetadata);
					}
					metadataResponse = new MetadataResponse(
							new MetadataResponseData().setThrottleTimeMs(0).setBrokers(listenPort.brokers())
									.setClusterId(listenPort.clusterId()).setControllerId(0).setTopics(topics),
							version);
				}
                Send send = metadataResponse.toSend(requestHeader.toResponseHeader(), version);
                dataToSend(send, apiKey);

				break; // Added break statement
            }
            case OFFSET_COMMIT: {
				// ApiKey=8
				// Group Coordinator Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

                final OffsetCommitRequest offsetCommitRequest = (OffsetCommitRequest) requestAndSize.request;
                final RequestHeader originalRequestHeader = requestHeader; // Capture for lambda

                if (kafkaApiConsumerTools == null) {
					// TODO: Test if this condition works as desired
					// If OffsetCommit is received on this channel and consumerTools is null, then return a response
					// indicating UNKNOWN_MEMBER_ID - which indicates that the consumer must re-join the group
					AbstractResponse response = KafkaApiConsumerTools.createOffsetCommitResponseRebalancing(null, requestHeader);
                    Send send = response.toSend(requestHeader.toResponseHeader(), version);
					dataToSend(send, apiKey);
                    break;
                }

                inFlightRequestCount++;
                log.trace("[Channel {}] Offloading OFFSET_COMMIT request (CorrId: {}) to executor. In-flight: {}", this.channelId, originalRequestHeader.correlationId(), inFlightRequestCount);

                ProxyReactor.getRequestHandlerExecutor().submit(() -> {
                    AbstractResponse taskResponse = null;
                    Exception taskException = null;
                    boolean taskWorked = false;
                    try {
                        taskResponse = kafkaApiConsumerTools.createOffsetCommitResponse(offsetCommitRequest, originalRequestHeader);
                        taskWorked = true;
                    } catch (Exception e) {
                        log.error("[Channel {}] Exception in async OFFSET_COMMIT processing (CorrId: {}): {}", this.channelId, originalRequestHeader.correlationId(), e.getMessage(), e);
                        taskException = e;
                        taskResponse = offsetCommitRequest.getErrorResponse(0, e); // KafkaApiConsumerTools.createOffsetCommitResponse might throw specific Kafka errors
                    } finally {
                        OffsetCommitResponseResult result = new OffsetCommitResponseResult(this, taskResponse, originalRequestHeader, taskWorked, taskException);
                        result.addToWorkQueue();
                    }
                });
                return false; // Stop reading from channel, wait for async result
            }
            case OFFSET_FETCH: {
				// ApiKey=9
				// Group Coordinator Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

                OffsetFetchRequest offsetFetchRequest = (OffsetFetchRequest) requestAndSize.request; // Safe cast after parseRequest
                AbstractResponse offsetFetchResponse = kafkaApiConsumerTools.createOffsetFetchResponse(offsetFetchRequest, requestHeader);
				Send send = offsetFetchResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

                break;
            }
            case FIND_COORDINATOR: {
				// ApiKey=10
				// Metadata Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

				FindCoordinatorRequest findCoordinatorRequest = (FindCoordinatorRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse findCoordinatorResponse = 
						KafkaApiConsumerTools.createFindCoordinatorResponse(findCoordinatorRequest, requestHeader, listenPort);
				Send send = findCoordinatorResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

                break;
            }
            case JOIN_GROUP: {
				// ApiKey=11
				// Group Channel
				// This request initiates Solace consumer flow
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);
				
				log.debug("Creating KafkaApiConsumerTools in handleRequest method with 'JOIN_GROUP' request");
				// If we are seeing joingroup request then we are about to subscribe. 
				// There may be an active consumer flow at this case if a rebalance operation
				KafkaApiConsumerTools.stopConsumerFlowIfRunning(kafkaApiConsumerTools);

				try {
					// Here we know that we are in the Group control thread, so create the consumerTools instance
					// JoinGroup is typically called twice, first to assign group memberId, next to join group
					// No need to wipe out the first instance
					if (kafkaApiConsumerTools == null) {
						kafkaApiConsumerTools = new KafkaApiConsumerTools(session.getJcsmpSession(), ProxyConfig.getKafkaProperties());
					}
				} catch (Exception e) {
					// Error here is improbable
					log.error("Error creating KafkaApiConsumerTools: {}", e);
					Send send = requestAndSize.request.getErrorResponse(0, new UnknownServerException(e.getLocalizedMessage())).toSend(requestHeader.toResponseHeader(), version);
					dataToSend(send, apiKey);
					break;
				}

				JoinGroupRequest joinGroupRequest = (JoinGroupRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse joinGroupResponse = kafkaApiConsumerTools.createJoinGroupResponse(joinGroupRequest, requestHeader);
				Send send = joinGroupResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

				break;
            }
            case HEARTBEAT: {
				// ApiKey=12
				// GROUP channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);
				if (kafkaApiConsumerTools == null) {
					// TODO: Test if this step works as desired
					// If a heartbeat request is received and consumerTools is null, then the client will receive a notice
					// that the Kafka cluster is rebalancing and should then rejoin the consumer group
					AbstractResponse response = KafkaApiConsumerTools.createHeartbeatResponseRebalancing();
					Send send = response.toSend(requestHeader.toResponseHeader(), version);
					dataToSend(send, apiKey);
					break;
				}
				HeartbeatRequest heartbeatRequest = (HeartbeatRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse heartbeatResponse = kafkaApiConsumerTools.createHeartbeatResponse(heartbeatRequest, requestHeader);
				Send send = heartbeatResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

				break;
            }
            case LEAVE_GROUP: {
				// ApiKey=13
				// GROUP channel
				// This request terminates Solace consumer flow
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);
				
				LeaveGroupRequest leaveGroupRequest = (LeaveGroupRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse leaveGroupResponse = kafkaApiConsumerTools.createLeaveGroupResponse(leaveGroupRequest, requestHeader);
				Send send = leaveGroupResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

                break;
            }
            case SYNC_GROUP: {
				// ApiKey=14
				// Group channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

				SyncGroupRequest syncGroupRequest = (SyncGroupRequest) requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse syncGroupResponse = kafkaApiConsumerTools.createSyncGroupResponse(syncGroupRequest, requestHeader);	
				Send send = syncGroupResponse.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

				// log.debug("[Channel {}] END   <- {} APIKey={}, v={}", this.channelId, apiKey.name(), apiKey.ordinal(), version);
				break;
            }
            case SASL_HANDSHAKE: {
				// ApiKey=17
				// All Channels
				if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);
				
                if (requestHeader.apiVersion() >= 1) {
                    // SASL Authenticate will be wrapped in a kafka request
                    // Otherwise it will not be formatted as a kafka request
                    enableKafkaSaslAuthenticateHeaders = true;
                }
                SaslHandshakeResponse saslHandshakeResponse = new SaslHandshakeResponse(
                        new SaslHandshakeResponseData().setErrorCode(Errors.NONE.code()).setMechanisms(saslMechanisms));
                Send send = saslHandshakeResponse.toSend(requestHeader.toResponseHeader(), version);
                dataToSend(send, apiKey);

                break;
            }
			case API_VERSIONS: {
				// ApiKey=18
				// All Channels
				if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);
		
				short apiVersionResponseVersionToSend = version;
				Errors apiVersionResponseErrorCode = Errors.NONE;
				if (version > SupportedApiVersions.getApiMaxVersion(ApiKeys.API_VERSIONS.id)) {
					log.warn("ApiVersions APIKey=18, version={} is not supported, downgrading to version=0", version);
					apiVersionResponseVersionToSend = SupportedApiVersions.getApiMinVersion(ApiKeys.API_VERSIONS.id);	// Always v=0, unless v=0 is retired
					apiVersionResponseErrorCode = Errors.UNSUPPORTED_VERSION;
				}

				ApiVersionsResponseData data = new ApiVersionsResponseData().setErrorCode(apiVersionResponseErrorCode.code())
						.setThrottleTimeMs(0).setApiKeys(SupportedApiVersions.getApiVersionCollection());
				ApiVersionsResponse apiVersionResponse = new ApiVersionsResponse(data);
				Send send = apiVersionResponse.toSend(requestHeader.toResponseHeader(), apiVersionResponseVersionToSend);
				dataToSend(send, apiKey);

				break;
			}
            case INIT_PRODUCER_ID: {
				// ApiKey=22
				// Produce Data Channel
                if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

                InitProducerIdRequest request = (InitProducerIdRequest)requestAndSize.request; // Safe cast after parseRequest
                InitProducerIdRequestData requestData = request.data();
				final long producerId = requestData.producerId() < 1L ? ((long)new Random().nextInt(1_000_000_000) + 1_000_000_001) : requestData.producerId();
                InitProducerIdResponseData responseData = new InitProducerIdResponseData()
						.setThrottleTimeMs(0)
                		.setErrorCode(Errors.NONE.code())
                		.setProducerId(producerId)
                		.setProducerEpoch((short)1);
                InitProducerIdResponse response = new InitProducerIdResponse(responseData);
                Send send = response.toSend(requestHeader.toResponseHeader(), version);
				idempotentProducer = true;
				firstRequestWithProducerId = true;
				recordBatchSequenceNumber = -1L;
                dataToSend(send, apiKey);

            	break;
            }
			case OFFSET_FOR_LEADER_EPOCH: {
				// ApiKey=23
				// Fetch Data Channel
				if (inFlightRequestCount > 0) return delayRequest(requestAndSize, requestHeader);

				// Get Consumer tools instance if not assigned to connection
				if (kafkaApiConsumerTools == null) {
					try {
						kafkaApiConsumerTools = KafkaApiConsumerTools.getConsumerToolsInstance(requestAndSize.request, requestHeader);
					} catch (Exception exc) {
						Send send = requestAndSize.request.getErrorResponse(0, exc).toSend(requestHeader.toResponseHeader(), version);
						dataToSend(send, apiKey);
						break;
					}
				}

				OffsetsForLeaderEpochRequest request = (OffsetsForLeaderEpochRequest)requestAndSize.request; // Safe cast after parseRequest
				AbstractResponse response = kafkaApiConsumerTools.createFetchChannelResponseWithLock(request, requestHeader);
				Send send = response.toSend(requestHeader.toResponseHeader(), version);
				dataToSend(send, apiKey);

            	break;
			}
            case SASL_AUTHENTICATE: {
				// ApiKey=36
				// All Channels
                SaslAuthenticateRequest saslAuthenticateRequest = (SaslAuthenticateRequest) requestAndSize.request; // Safe cast after parseRequest
                ProxyChannel.AuthorizationResult authResult = new ProxyChannel.AuthorizationResult(this, requestHeader);
                inFlightRequestCount++;
                try {
					// Stop flow gracefully if it is running as the next step will replace it (should not happen)
                    session = proxySasl.authenticate(authResult, saslAuthenticateRequest.data().authBytes());
                } catch (Exception e) {
                    inFlightRequestCount--; // Decrement since the async operation could not be started
                    log.info("Sasl authentication failed: " + e);
                    authorizationResult(requestHeader, false);
                }

                return false; // we are either waiting for authentication or could not even try to connect
            }
            default: {
                log.error("Unhanded request type: " + apiKey.toString());
                break;
            }
        }
		return true;
	}

	// Logic taken from readFrom() in kafka.common.network.NetworkReceive.java
	// only call this from Reactor thread
    // We exit after a message to make sure that we do not keep looping if there is 
    // lots of data to read, BUT we do not exit if bytes buffered in the transport layer
    // due to use of SSL since otherwise we may not wake up again on a read event
    // We also exit if we are told to wait (e.g. authentication request), but later we will be 
    // forced back into this routine even without a read event when we are ready to proceed further.
	void readFromChannel() {
        boolean gotMessage = false;
		try {
			while (transportLayer.hasBytesBuffered() ||
                   (!gotMessage && transportLayer.selectionKey().isReadable())) {
				if (!transportLayer.ready()) {
					transportLayer.handshake();
					if (!transportLayer.ready())
						return;
				}
				if (size.hasRemaining()) {
					int bytesRead = transportLayer.read(size);
					if (bytesRead < 0) {
						close("Channel closed by far end");
						return;
					}
					if (!size.hasRemaining()) {
						// We have the full size of the message
						size.rewind();
						int receiveSize = size.getInt();
						if (receiveSize < 0) {
							close("Invalid receive (size = " + receiveSize + ")");
							return;
						}
						requestedBufferSize = receiveSize; // may be 0 for some payloads (SASL)
						if (receiveSize == 0) {
							buffer = EMPTY_BUFFER;
						}
					} else
						return;
				}
				if (buffer == null && requestedBufferSize != -1) { // we know the size we want but haven't been able to
																   // allocate it yet
					byte[] bytes = new byte[requestedBufferSize];
					buffer = ByteBuffer.wrap(bytes);
				}
				if (buffer != null) {
					int bytesRead = transportLayer.read(buffer);
					if (bytesRead < 0) {
						close("Channel closed by far end");
						return;
					}
					// see if we have the entire message read
					if (!buffer.hasRemaining()) {
						size.clear();
						buffer.rewind();
                        gotMessage = true;
						try {
							final boolean keepReading = parseRequest(buffer);
							buffer = null;
							if (!keepReading)
								return; // do not want to read any more messages (e.g. could be blocked on
										// authentication)
						} catch (Exception e) {
							close("Request parse did not work: " + e.toString());
							buffer = null;
							return;
						}
					}
				} else {
					return;
                }
			}
		} catch (Exception e) {
			close("Channel read error: " + e);
			return;
		}
	}

	// only call this from Reactor thread
	// Writes as much buffered data as possible
	void writeToChannel() {
		try {
			do {
				if (!transportLayer.ready()) {
					transportLayer.handshake();
					if (!transportLayer.ready())
						return;
				}
				Send send = sendQueue.peek();
				if (send != null) {
					send.writeTo(transportLayer);
					if (!send.completed())
						break;
					sendQueue.remove();
				} else {
					transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
					break;
				}
			} while (true);
		} catch (Exception e) {
			close("Channel write error: " + e);
		}
	}

	// Only call this from Reactor thread
	void close(String reason) {
        // Avoid logging if we have already closed (otherwise may get many close logs to due unable to send PRODUCE response)
        if (proxySasl != null) {
            log.info("Cleaning up channel (remote " + transportLayer.socketChannel().socket().getRemoteSocketAddress()
                    + ") due to " + reason);
        }
		KafkaApiConsumerTools.stopConsumerFlowIfRunning(kafkaApiConsumerTools);		// Stop consumer gracefully if still running
		kafkaApiConsumerTools = null;
		listenPort.removeChannel(this);
		if (session != null) {
			session.removeChannel(this);
			session = null;
		}
		proxySasl = null;
		if (transportLayer != null) {
			try {
				transportLayer.selectionKey().cancel();
				transportLayer.close();
			} catch (IOException e) {
				log.error("Exception during channel close: " + e);
			}
		}
		sendQueue.clear();
	}

	// only call this from Reactor thread
	void handleWorkEntry(ProxyReactor.WorkEntry workEntry) {
		// Almost all of the work is ProduceAckState so check for that first
		if (workEntry instanceof ProduceAckState) {
            inFlightRequestCount--;
            log.trace("[Channel {}] Handled ProduceAckState. In-flight: {}", this.channelId, inFlightRequestCount);
			produceResponseProcesssing.handleProduceAckState((ProduceAckState) workEntry);
		} else if (workEntry instanceof AuthorizationResult) {
            inFlightRequestCount--;
            log.debug("[Channel {}] Handled AuthorizationResult. In-flight: {}", this.channelId, inFlightRequestCount);
			AuthorizationResult authResult = (AuthorizationResult) workEntry;
			final boolean worked = authResult.getWorked();
			authorizationResult(authResult.getRequestHeader(), worked);
            if (!worked) return; // if did not work then we are done as channel will be closed
		} else if (workEntry instanceof FetchResponseResult) {
            inFlightRequestCount--;
            FetchResponseResult fetchResult = (FetchResponseResult) workEntry;
            log.trace("[Channel {}] Handled FetchResponseResult (CorrId: {}). In-flight: {}", this.channelId, fetchResult.getRequestHeader().correlationId(), inFlightRequestCount);
            try {
                if (fetchResult.getResponse() != null) { // This could be a success or an error response
                    Send send = fetchResult.getResponse().toSend(
                        fetchResult.getRequestHeader().toResponseHeader(),
                        fetchResult.getRequestHeader().apiVersion()
                    );
                    dataToSend(send, fetchResult.getApiKey());
                } else {
                    // This case means the async task failed to even produce an error response.
                    log.error("[Channel {}] Async FETCH task (CorrId: {}) failed critically, no response to send. Closing channel. Exception: {}",
                              this.channelId, fetchResult.getRequestHeader().correlationId(), fetchResult.getException() != null ? fetchResult.getException().getMessage() : "Unknown");
                    close("Async FETCH processing failed critically");
                    return;
                }
            } catch (IOException e) {
                log.error("[Channel {}] IOException sending FETCH response/error (CorrId: {}): {}", this.channelId, fetchResult.getRequestHeader().correlationId(), e.getMessage(), e);
                close("Could not send FETCH response: " + e);
                return;
            }
		} else if (workEntry instanceof OffsetCommitResponseResult) {
            inFlightRequestCount--;
            OffsetCommitResponseResult commitResult = (OffsetCommitResponseResult) workEntry;
            log.trace("[Channel {}] Handled OffsetCommitResponseResult (CorrId: {}). In-flight: {}", this.channelId, commitResult.getRequestHeader().correlationId(), inFlightRequestCount);
            try {
                if (commitResult.getResponse() != null) {
                    Send send = commitResult.getResponse().toSend(
                        commitResult.getRequestHeader().toResponseHeader(),
                        commitResult.getRequestHeader().apiVersion()
                    );
                    dataToSend(send, commitResult.getApiKey());
                } else {
                     log.error("[Channel {}] Async OFFSET_COMMIT task (CorrId: {}) failed critically, no response to send. Closing channel. Exception: {}",
                              this.channelId, commitResult.getRequestHeader().correlationId(), commitResult.getException() != null ? commitResult.getException().getMessage() : "Unknown");
                    // Attempt to send a generic error if possible, otherwise close
                    // For OffsetCommit, getErrorResponse might not be directly available on the original request if it was already transformed.
                    // Consider a generic error response or closing.
                    close("Async OFFSET_COMMIT processing failed critically");
                    return;
                }
            } catch (IOException e) {
                log.error("[Channel {}] IOException sending OFFSET_COMMIT response/error (CorrId: {}): {}", this.channelId, commitResult.getRequestHeader().correlationId(), e.getMessage(), e);
                close("Could not send OFFSET_COMMIT response: " + e);
                return;
            }
		} else if (workEntry instanceof Close) {
			log.debug("[Channel {}] Handling Close work entry.", this.channelId);
			final Close closeReq = (Close) workEntry;
			close(closeReq.getReason());
            return;
		} else {
			log.error("Unknown work entry type");
            return;
		}
        
        // If there are no more in-flight requests and we had blocked reading from the socket, then we 
        // are ready to read again. We need to immediately read from the channel as we may have buffered
        // bytes in the SSL transport layer even if the socket has nothing in it for reading. Otherwise,
        // the channel may not wake up from select for a read event even though bytes are buffered above the 
        // socket for reading.
        if (inFlightRequestCount == 0 && savedRequestAndSize != null) {
            final RequestAndSize requestAndSize = savedRequestAndSize;
            final RequestHeader requestHeader = savedRequestHeader;
            savedRequestAndSize = null;
            savedRequestHeader = null;
            log.debug("[Channel {}] All in-flight requests completed. Processing saved request: APIKey={}, CorrId={}", this.channelId, requestHeader.apiKey(), requestHeader.correlationId());
            try {
                transportLayer.addInterestOps(SelectionKey.OP_READ);
                if (!handleRequest(requestAndSize, requestHeader)) {
                    // If handleRequest returned false (e.g., it offloaded another task or is SASL_AUTHENTICATE without Kafka headers),
                    // then we don't necessarily readFromChannel() immediately unless OP_READ is still set by handleRequest.
                    // The current logic for offloading returns false and OP_READ would have been removed by delayRequest or not added.
                } else {
                    // If handleRequest returned true, it means it processed synchronously and we can try to read more.
                    if (transportLayer.selectionKey().isValid() && (transportLayer.selectionKey().interestOps() & SelectionKey.OP_READ) != 0) {
                        readFromChannel();
                    }
                }
            } catch (Exception e) {
			    log.error("[Channel {}] Error processing saved request or during read re-enable: {}", this.channelId, e.getMessage(), e);
                close("Error during read re-enable: " + e);
            }
        } else if (inFlightRequestCount == 0 && savedRequestAndSize == null) {
            // No in-flight or saved requests. Ensure reads are enabled if not already.
            if (transportLayer.selectionKey().isValid() && (transportLayer.selectionKey().interestOps() & SelectionKey.OP_READ) == 0) {
                log.trace("[Channel {}] No in-flight or saved requests. Ensuring OP_READ is enabled.", this.channelId);
                transportLayer.addInterestOps(SelectionKey.OP_READ);
            }
        }
	}
}
