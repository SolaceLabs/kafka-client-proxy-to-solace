/*
 * Copyright 2024 Solace Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.solace.kafka.kafkaproxy.demo;

import org.apache.commons.cli.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

public class KeyValueConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KeyValueConsumer.class);

    public static void main(String[] args) {
        Options options = new Options();
        options.addRequiredOption("c", "config", true, "Path to the Kafka consumer configuration file");
        options.addRequiredOption("t", "topic", true, "Name of the Kafka topic to consume from");
        options.addOption("g", "group-id", true, "Consumer group ID (defaults to a random UUID)");
        options.addOption("p", "poll-time", true, "The polling time in milliseconds (default: 500)");
        options.addOption("h", "help", false, "Print this help message");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            logger.error("Error parsing command line arguments: {}", e.getMessage());
            System.err.println(e.getMessage());
            formatter.printHelp("java KeyValueConsumer", options);
            System.exit(1);
            return;
        }

        if (cmd.hasOption("h")) {
            formatter.printHelp("java KeyValueConsumer", options);
            System.exit(0);
            return;
        }

        String configFilePath = cmd.getOptionValue("c");
        String topicName = cmd.getOptionValue("t");
        String groupId = cmd.getOptionValue("g", "consumer-group-" + UUID.randomUUID().toString());
        long pollTimeMs = Long.parseLong(cmd.getOptionValue("p", "500"));

        if (pollTimeMs < 100) {
            logger.warn("Min poll time = 100ms, setting to 100ms");
            pollTimeMs = 100;
        }
        if (pollTimeMs > 5_000) {
            logger.warn("Max poll time = 5s, setting to 5s");
            pollTimeMs = 5_000;
        }

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(configFilePath)) {
            properties.load(input);
            logger.info("Successfully loaded configuration from: {}", configFilePath);
        } catch (IOException e) {
            logger.error("Error loading configuration file: {}", configFilePath, e);
            System.err.println("Error loading configuration file: " + configFilePath + " - " + e.getMessage());
            System.exit(1);
            return;
        }

        // Essential consumer properties
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"); // earliest or latest
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");

        final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        final Thread mainThread = Thread.currentThread(); // Get a reference to the main thread
        int receivedMessages = 0;

        // Add a shutdown hook to close the consumer gracefully
        Runtime.getRuntime().addShutdownHook(new Thread("kafka-consumer-shutdown-hook") {
            @Override
            public void run() {
                logger.info("Shutdown hook initiated. Waking up consumer to interrupt poll()...");
                consumer.wakeup(); // This will cause the poll() in the main thread to throw a WakeupException
                try {
                    // Wait for the main thread to finish its cleanup, especially consumer.close()
                    mainThread.join(Duration.ofSeconds(10).toMillis()); // Wait up to 10 seconds
                } catch (InterruptedException e) {
                    logger.warn("Shutdown hook interrupted while waiting for main consumer thread to complete.", e);
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                }
                logger.info("Shutdown hook finished.");
            }
        });

        try {
            consumer.subscribe(Collections.singletonList(topicName));
            logger.info("Subscribed to topic: {}. Consumer group: {}", topicName, groupId);
            System.out.println("Listening for messages on topic '" + topicName + "'. Press Ctrl+C to exit.");

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollTimeMs)); // Poll for specified milliseconds
                if (records.isEmpty() && !Thread.currentThread().isInterrupted()) {
                    // Optional: log if no records received for a while, or just continue polling
                }
                for (ConsumerRecord<String, String> record : records) {
                    // System.out.printf("KEY: %s - VALUE: %s (Partition: %d, Offset: %d)%n",
                    System.out.printf("%s - %.60s (P[%02d] - %06d)%n",
                            record.key(), record.value(), record.partition(), record.offset());
                    logger.debug("Consumed record: key={}, value={}, partition={}, offset={}",
                            record.key(), record.value(), record.partition(), record.offset());
                    receivedMessages++;
                }
            }
        } catch (WakeupException e) {
            logger.info("Consumer poll() interrupted by wakeup(). Proceeding to close consumer.");
            // This is an expected exception when consumer.wakeup() is called.
        } catch (Exception e) {
            logger.error("Exception occurred while consuming messages: ", e);
            System.err.println("Exception occurred: " + e.getMessage());
        } finally {
            logger.info("Closing Kafka consumer in finally block...");
            // consumer.unsubscribe(); // Not strictly necessary as close() will handle leaving the group.
            try {
                // Use close with a timeout to allow for graceful shutdown,
                // including sending the LeaveGroup request.
                consumer.close(Duration.ofSeconds(5)); // e.g., 5 seconds timeout
                logger.info("Kafka consumer closed successfully from finally block.");
                logger.info("Received {} messages.", receivedMessages);
                System.out.println("Received " + receivedMessages + " messages.");
            } catch (Exception e) {
                logger.error("Error during consumer.close() in finally block.", e);
            }
        }
        logger.info("KeyValueConsumer main method finished.");
    }
}