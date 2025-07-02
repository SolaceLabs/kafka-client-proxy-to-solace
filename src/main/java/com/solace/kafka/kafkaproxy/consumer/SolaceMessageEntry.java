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

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.XMLMessage;

import lombok.Getter;
import lombok.Setter;

public class SolaceMessageEntry {

    /*
     * Number of milliseconds after a message is ack'd/committed that it is eligible
     * to be deleted from memory
     * Global setting for the proxy instance
     */
    @Setter
    private static long deleteAfterMs = 2000L;

    /** 
     * Virtual Kafka Offset internally assigned to a Solace message
     */
    @Getter
    private final long offset;

    /**
     * Solace BytesXMLMessage to be stored at virtual offset
     */
    @Getter
    private final BytesXMLMessage message;

    private volatile boolean acknowledged = false;

    private volatile long commitTimestamp = 0L;

    /**
     * SolaceMessageEntry Constructor
     * @param offset - The new offset
     * @param message - New BytesXMLMessage
     */
    protected SolaceMessageEntry(final long offset, final BytesXMLMessage message)
    {
        this.offset = offset;
        this.message = message;
    }

    public static SolaceMessageEntry createMessageEntry(final long offset, final BytesXMLMessage message)
    {
        return new SolaceMessageEntry(offset, message);
    }

    /**
     * Returns true if the AckMessageId of the message in the parameter matches the
     * AckMessageId of the message embedded in this entry
     * @param messageToMatch - Solace XMLMessage type
     * @return true if the AckMessageId of the two messages match
     */
    public boolean matchesMessageId(final XMLMessage messageToMatch) {
        return ( this.message.getAckMessageId() == messageToMatch.getAckMessageId() );
    }

    /**
     * Acknowlege Solace message associated with this entry. Performs Message Ack if
     * not acknowledged, returns true if successful or the message was already acknowledged.
     * @return true if message is acknowledged, otherwise false
     * @throws IllegalStateException
     */
    public synchronized boolean ackEntry() throws IllegalStateException
    {
        if (acknowledged) {
            return true;
        }
        message.ackMessage();
        acknowledged = true;
        commitTimestamp = System.currentTimeMillis();
        return true;
    }

    /**
     * Reports if a message can be deleted from memory.
     * Message is ack'd + committed and X > deleteAfterMs milliseconds have passed
     * @param systemTime - System time in milliseconds
     * @return true if message is eligible for deletion, otherwise false
     */
    protected synchronized boolean eligibleForDeletion(final long systemTime) {
        return acknowledged && (commitTimestamp + deleteAfterMs) < systemTime;
    }
}
