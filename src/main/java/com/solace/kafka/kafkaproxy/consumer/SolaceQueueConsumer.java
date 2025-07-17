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

import com.solace.kafka.kafkaproxy.ProxyConfig;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.InvalidOperationException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.Queue;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.event.Level;

@Slf4j
public class SolaceQueueConsumer {

    private JCSMPSession session;

    private ConcurrentSkipListMap<Long, SolaceMessageEntry> receivedMessages = new ConcurrentSkipListMap<>();

    private String queueName;

    private Queue queue;

    private FlowReceiver flowReceiver;

    private static volatile long maxUncommittedMessagesPerFlow = -1L;

    private volatile Long receivedOffset = -1L;          // # Received messages

    private volatile Long committedOffset = -1L;         // Offset received + ack'd to Solace broker

    private volatile boolean consumerFlowActive = false;

    protected String getQueueName() {
        return queueName;
    }

    protected boolean isFlowActive() {
        return consumerFlowActive;
    }

    protected Long getReceivedOffset() {
        return receivedOffset;
    }

    protected Long getCommittedOffset() {
        return committedOffset + 1L;
    }

    public SolaceQueueConsumer(JCSMPSession session, String queueName)
    {
        this.session = session;
        this.queueName = queueName;
        queue = JCSMPFactory.onlyInstance().createQueue(this.queueName);
        if (maxUncommittedMessagesPerFlow < 0L) {
            // Only assign the first time
            maxUncommittedMessagesPerFlow = ProxyConfig.getInstance().getLong(ProxyConfig.MAX_UNCOMMITTED_MESSAGES_PER_FLOW);
        }
    }

    public class SolaceMessageEntryException extends Exception {

        private Errors error;

        public SolaceMessageEntryException() {
            super();
        }

        public SolaceMessageEntryException(Errors error) {
            super();
            this.error = error;
        }

        public SolaceMessageEntryException(Errors error, String message) {
            super(message);
            this.error = error;
        }

        public SolaceMessageEntryException(Errors error, String message, Throwable cause) {
            super(message, cause);
            this.error = error;
        }

        public Errors getError() {
            return error;
        }

        public void setError(Errors error) {
            this.error = error;
        }
    }

    protected SolaceMessageEntry getMessage(
        long fetchOffset,
        int waitTimeMs
    ) throws SolaceMessageEntryException
    {
        if (!consumerFlowActive) {
            // Probably shutting down
            return null;
        }
        if (fetchOffset <= this.committedOffset) {
            // Invlalid Offset
            throw new SolaceMessageEntryException(Errors.OFFSET_OUT_OF_RANGE, "Offset has been committed and is no longer available");
        }
        if (receivedOffset - committedOffset >= maxUncommittedMessagesPerFlow) {
            // Too many uncommitted messages
            try {
                log.info("Throttling message fetch, uncommitted message count: {}", receivedOffset - committedOffset);
                // We wait here to throttle the next request
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Thread interrupted while waiting for commit. Probably shutting down", e);
            }
            return null; // Do not try to return a message
        }
        try {
            if (fetchOffset <= receivedOffset && fetchOffset > this.committedOffset && this.receivedMessages.containsKey(fetchOffset)) {
                log.trace("Returning cached message at offset: {}", fetchOffset);
                return receivedMessages.get(fetchOffset);
            } else if (fetchOffset == (receivedOffset + 1)) {
                final BytesXMLMessage nextMesage = flowReceiver.receive(waitTimeMs);
                if (nextMesage == null) {
                    log.trace("No message available on queue {}", queueName);
                    return null;
                }
                final SolaceMessageEntry newEntry = SolaceMessageEntry.createMessageEntry(++receivedOffset, nextMesage);
                receivedMessages.put(Long.valueOf(receivedOffset), newEntry);
                log.trace("Retrieved new message at offset: {}", receivedOffset);
                return newEntry;
            } else {
                log.warn("Consumer requested unavailable offset: {}, next available offset: {}", fetchOffset, receivedOffset + 1);
                throw new SolaceMessageEntryException(
                    Errors.OFFSET_OUT_OF_RANGE, 
                    String.format("Requested Offset [%d] is out of range; next available offset is [%d]", fetchOffset, receivedOffset + 1));
            }
        } catch (InvalidOperationException ioExc) {
            // Most likely because consumer flow is stopped
            log.debug("Error getting Solace message: {}", ioExc.getLocalizedMessage());
            return null;
        } catch (NullPointerException npExc) {
            log.error("Error getting Solace message: {}", npExc.getLocalizedMessage());
            throw new SolaceMessageEntryException(Errors.UNKNOWN_SERVER_ERROR, npExc.getLocalizedMessage(), npExc);
        } catch (ClassCastException ccExc) {
            log.error("Error getting Solace message: {}", ccExc.getLocalizedMessage());
            throw new SolaceMessageEntryException(Errors.UNKNOWN_SERVER_ERROR, ccExc.getLocalizedMessage(), ccExc);
        } catch (JCSMPTransportException jcsmpTransportException) {
            if (!consumerFlowActive || this.flowReceiver.isClosed()) {
                // Don't always get the consumerFlow event
                log.debug("Error getting Solace message from broker, consumer flow is INACTIVE", jcsmpTransportException.getLocalizedMessage());
                return null;
            } else {
                throw new SolaceMessageEntryException(Errors.UNKNOWN_SERVER_ERROR, jcsmpTransportException.getLocalizedMessage(), jcsmpTransportException);
            }
        } catch (JCSMPException jcsmpException) {
            log.error("Error getting Solace message from broker: {}", jcsmpException.getLocalizedMessage(), jcsmpException);
            throw new SolaceMessageEntryException(Errors.UNKNOWN_SERVER_ERROR, jcsmpException.getLocalizedMessage(), jcsmpException);
        }
    }

    /**
     * Self-explanatory
     * @param commitTargetOffset
     * @throws IllegalStateException
     * @throws OffsetOutOfRangeException
     * @throws Exception
     */
    protected void commitUpToOffset(Long commitTargetOffset) throws IllegalStateException, OffsetOutOfRangeException
    {
        if (!consumerFlowActive) {
            log.warn("Attempted to commit messages but flow is INACTIVE");
            return;
        }
        if (commitTargetOffset > this.receivedOffset + 1) {
            throw new OffsetOutOfRangeException("Attempted to commit messages not received");
        }
        if (commitTargetOffset <= this.committedOffset) {
            throw new OffsetOutOfRangeException("Attempted to commit messages already committed");
        }
        ConcurrentNavigableMap<Long, SolaceMessageEntry> messagesToCommit = 
                receivedMessages.subMap(
                    committedOffset,
                    false, 
                    commitTargetOffset, 
                    false       // commitTargetOffset = 1 greater than what will actually be committed
        );

        if (messagesToCommit.isEmpty()) {
            log.trace("No messages to commit");
            return;
        }

        try {
            int committedMessagesCount = 0;
            final long lastKey = messagesToCommit.lastKey();
            for (long key = messagesToCommit.firstKey(); key <= lastKey; key++) {
                if (!messagesToCommit.containsKey(key)) {
                    log.warn("Missing Offset to Commit: {}", key);
                    continue;
                }
                log.trace("Committing offset: {} to broker", key);
                messagesToCommit.get(key).ackEntry();
                committedMessagesCount++;
                this.committedOffset = key;
            }
            log.trace("Committed {} messages to broker for queue: {}", committedMessagesCount, queueName);
        } catch (IllegalStateException exc) {
            log.warn("Error acknowledging Solace messages back to broker, most likely consumer flow has been shut down: {}", exc.getLocalizedMessage());
            throw exc;
        } catch (Exception exc) {
            log.error("Caught exception attempting to commit Solace messages back to broker: {}", exc.getLocalizedMessage(), exc);
            throw exc;
        }
    }

    /**
     * This method will delete committed messages
     * @throws Exception
     */
    protected void deleteEligibleMessages() throws Exception
    {
        if (!consumerFlowActive) {
            log.debug("Attempted to delete committed messages but flow is INACTIVE");
            return;
        }
        final ConcurrentNavigableMap<Long, SolaceMessageEntry> messagesToDelete = 
                receivedMessages.headMap(
                    this.committedOffset, 
                    true
                );
        if (messagesToDelete.isEmpty()) {
            log.trace("No committed messages to delete");
            return;
        }
        final Long now = System.currentTimeMillis();
        int deletedMessagesCount = 0, missingMessageCount = 0;
        final long lastKey = messagesToDelete.lastKey();
        for (long key = messagesToDelete.firstKey(); key <= lastKey; key++) {
            if (!messagesToDelete.containsKey(key) ) {
                continue;
            }
            if (messagesToDelete.get(key).eligibleForDeletion(now)) {
                try {
                    messagesToDelete.remove(key);
                    deletedMessagesCount++;
                } catch (Exception exc) {
                    missingMessageCount++;
                    log.trace("Error deleting message from memory: {}", exc.getLocalizedMessage());
                }
            } else {
                break;
            }
        }
        if (deletedMessagesCount > 0 || missingMessageCount > 0) {
            log.atLevel(missingMessageCount > 0 ? Level.WARN : Level.TRACE).log(
                "Deleted {} committed messages from memory; {} missing messages not deleted", 
                deletedMessagesCount,
                missingMessageCount);
        } else {
            log.trace("No committed messages eligible to delete");
        }
    }

    protected void startConsumer() throws Exception
    {
        if (consumerFlowActive) {
            log.info("Attempted to start Consumer flow that is already ACTIVE");
            return;
        }
        
        final ConsumerFlowProperties flowProperties = new ConsumerFlowProperties();
        flowProperties.setEndpoint(queue);
        // TODO - Evaluate consumer flow settings and Endpoint properties
        flowProperties.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        flowProperties.setActiveFlowIndication(true);
        final EndpointProperties endpointProperties = new EndpointProperties();

        try {
            flowReceiver = session.createFlow(
                null, 
                flowProperties, 
                endpointProperties, 
                new KafkaConsumerFlowEventHandler());
            flowReceiver.start();
            consumerFlowActive = true;          // Not technically accurate
        } catch (OperationNotSupportedException opExc) {
            consumerFlowActive = false;
            // TODO - Add Logging
            throw opExc;
        } catch (JCSMPException jcsmpException) {
            consumerFlowActive = false;
            // TODO - Add Logging
            throw jcsmpException;
        }
    }

    protected static SolaceQueueConsumer createAndStartQueueConsumer(
        JCSMPSession session, 
        String queueName) throws Exception
    {
        SolaceQueueConsumer solaceQueueConsumer = new SolaceQueueConsumer(session, queueName);
        solaceQueueConsumer.startConsumer();
        return solaceQueueConsumer;
    }

    protected void stopQueueConsumer()
    {
        try {
            if (!flowReceiver.isClosed()) {
                flowReceiver.close();
            }
        } catch (Exception exc) {
            log.error("Error closing flow receiver", exc);
        } finally {
            consumerFlowActive = false;
        }
    }

    private class KafkaConsumerFlowEventHandler implements FlowEventHandler {

        // TODO - the following cases must be implemented
        @Override
        public void handleEvent(Object source, FlowEventArgs event) {
            log.info("Solace Consumer Flow for Queue [{}] is {}", queueName, event.getEvent().toString());
            switch (event.getEvent()) {
                case FLOW_ACTIVE: {
                    consumerFlowActive = true;
                    break;
                }
                case FLOW_INACTIVE: {
                    consumerFlowActive = false;
                    break;
                }
                case FLOW_UP: {
                    break;
                }
                case FLOW_DOWN: {
                    consumerFlowActive = false;
                    break;
                }
                case FLOW_RECONNECTED: {
                    break;
                }
                case FLOW_RECONNECTING: {
                    break;
                }
                default:
            }
        }
    }

    protected boolean messageIsInMemory(BytesXMLMessage messageToMatch)
    {
        Iterator<Map.Entry<Long, SolaceMessageEntry>> it = receivedMessages.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().matchesMessageId(messageToMatch)) {
                return true;
            }
        }
        return false;
    }
}
