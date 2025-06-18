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
package org.apache.kafka.solace.kafkaproxy.util;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public class ProxyUtils {

        public static String generateMDAsUuidV4AsBase64String(String inputString) {
            if (inputString == null || inputString.isEmpty()) { return null; }
            return Base64.getUrlEncoder().encodeToString(generateMDAsUuidV4AsBytes(inputString)).substring(0, 22);
        }

        public static byte[] generateMDAsUuidV4AsBytes(String inputString) {
            try {
                if (inputString == null || inputString.isEmpty()) { return null; }

                MessageDigest md = MessageDigest.getInstance("MD5");

                // Convert the input string to bytes using UTF-8 encoding.
                byte[] inputBytes = inputString.getBytes("UTF-8");

                // Compute the MD5 hash (16 bytes)
                byte[] hashBytes = md.digest(inputBytes);

                // --- Apply UUIDv4 compliance by manipulating specific bits ---
                // Set the UUID version (bits 4-7 of byte 6 to 0100 for version 4)
                // Clear the 4 most significant bits of byte 6, then OR with 0x40 (binary 01000000)
                hashBytes[6] = (byte) ((hashBytes[6] & 0x0F) | 0x40);

                // Set the UUID variant (bits 6-7 of byte 8 to 10 for RFC 4122 variant 1)
                // Clear the 2 most significant bits of byte 8, then OR with 0x80 (binary 10000000)
                hashBytes[8] = (byte) ((hashBytes[8] & 0x3F) | 0x80);

                return hashBytes;

            } catch (NoSuchAlgorithmException e) {
                System.err.println("MD5 algorithm not found: " + e.getMessage());
                e.printStackTrace();
                return null;
            } catch (UnsupportedEncodingException e) {
                System.err.println("UTF-8 encoding not supported: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }

        /**
         * Generates an MD5 hash for the given input string and formats it as a UUIDv4-compliant string.
         * This method explicitly sets the UUID version (4) and variant (RFC 4122 variant 1) bits.
         * The purpose of this function is to create predictable topic ID values using topic name as input.
         * Output is a name-based UUID (string) where UUIDv4 bits have been correctly set.
         *
         * @param inputString The string for which to generate the MD5 hash.
         * @return The MD5 hash as a hexadecimal string formatted like a UUIDv4, or null if an error occurs
         * (e.g., MD5 algorithm not found, or unsupported encoding).
         */
        public static String generateMD5AsUuidV4Format(String inputString) {
        if (inputString == null) {
            return null; // Handle null input gracefully
        }

        byte hashBytes[] = generateMDAsUuidV4AsBytes(inputString);

        // --- Format the 16-byte hash into a UUID-like string (32 hex chars + 4 hyphens) ---
        StringBuilder sb = new StringBuilder();
        // Group 1: 8 chars (bytes 0-3)
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", hashBytes[i] & 0xff));
        }
        sb.append("-");
        // Group 2: 4 chars (bytes 4-5)
        for (int i = 4; i < 6; i++) {
            sb.append(String.format("%02x", hashBytes[i] & 0xff));
        }
        sb.append("-");
        // Group 3: 4 chars (bytes 6-7) - includes version bits
        for (int i = 6; i < 8; i++) {
            sb.append(String.format("%02x", hashBytes[i] & 0xff));
        }
        sb.append("-");
        // Group 4: 4 chars (bytes 8-9) - includes variant bits
        for (int i = 8; i < 10; i++) {
            sb.append(String.format("%02x", hashBytes[i] & 0xff));
        }
        sb.append("-");
        // Group 5: 12 chars (bytes 10-15)
        for (int i = 10; i < 16; i++) {
            sb.append(String.format("%02x", hashBytes[i] & 0xff));
        }
        return sb.toString();
    }

    public static void bytesToHex(byte[] buf, int start, int length) {
        for (int i = start; i < start + length; i++) {
            String st = String.format("%02X ", buf[i]);
            System.out.print(st);
        }
        System.out.print('\n');
    }

    public static void byteBufferToHex(ByteBuffer buf) {
		final byte[] bytes = new byte[buf.remaining()];
		buf.duplicate().get(bytes);
		for (byte b : bytes) {
			String st = String.format("%02X ", b);
			System.out.print(st);
		}
		System.out.print('\n');
	}

    public static String byteBufferToHexString(ByteBuffer buf) {
		final byte[] bytes = new byte[buf.remaining()];
		buf.duplicate().get(bytes);
        StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02X ", b));
		}
		return sb.toString();
	}

    public static final String PRODUCER_TOPIC_PREFIX = "PRODUCER_TOPIC:";

    public static boolean isProducerTopic(String topicName) {
        return topicName.startsWith(PRODUCER_TOPIC_PREFIX);
    }

    public static String getProducerTopicNameToPublish(String topicName) {
        if (isProducerTopic(topicName)) {
            return topicName.substring(PRODUCER_TOPIC_PREFIX.length());
        }
        return topicName;
    }

    /** 
     * Convert Long timestamp in millis to String:
     * yyyy/MM/dd - HH:mm:ss.SSS Z
     */
    public static String convertTimestampToGMT(long timestampMillis) {
        final Instant instant = Instant.ofEpochMilli(timestampMillis);
        final OffsetDateTime gmtDateTime = instant.atOffset(ZoneOffset.UTC);
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd - HH:mm:ss.SSS Z");
        return gmtDateTime.format(formatter);
    }
}
