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

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.commons.cli.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Time;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.FileInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public class KeyValueProducer {

    private static final Logger logger = LoggerFactory.getLogger(KeyValueProducer.class);

    public static void main(String[] args) {
        // Define command line options
        Options options = new Options();
        options.addRequiredOption("c", "config", true, "Path to the configuration file");
        options.addRequiredOption("t", "topic", true, "Name of the Kafka topic");
        options.addRequiredOption("i", "input-file", true, "Path to the input file");
        options.addOption("h", "help", false, "Print this help message");
        options.addOption("d", "delay", true, "Delay between messages in milliseconds");
        options.addOption("n", "num-records", true, "Total number of records to produce");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            logger.error("Error parsing command line arguments: {}", e.getMessage());
            System.err.println(e.getMessage());
            formatter.printHelp("java KeyValueProducer", options);
            System.exit(1);
            return; // Added return here to ensure we don't proceed with null cmd
        }

        // Print help if requested
        if (cmd.hasOption("h")) {
            formatter.printHelp("java KeyValueProducer", options);
            System.exit(0);
            return;
        }

        // Add a shutdown hook to interrupt the main thread for graceful shutdown
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread("kafka-producer-shutdown-hook") {
            @Override
            public void run() {
                logger.info("Shutdown hook initiated. Interrupting main producer thread...");
                mainThread.interrupt();
            }
        });

        String configFilePath = cmd.getOptionValue("c");
        String topicName = cmd.getOptionValue("t");
        String inputFilePath = cmd.getOptionValue("i");
        int delay = 0;
        long numRecords = -1;

        if (cmd.hasOption("d")) {
            try {
                delay = Integer.parseInt(cmd.getOptionValue("d"));
                if (delay < 0 || delay > 1000) {
                    System.err.println("Message Delay must be between 0 and 1000 milliseconds, default is 100");
                    System.exit(1);
                    return;
                }
            } catch (NumberFormatException e) {
                logger.error("Invalid delay value: {}", cmd.getOptionValue("p"));
                System.err.println("Invalid delay value: " + cmd.getOptionValue("p"));
                System.exit(1);
                return;
            }
        }

        if (cmd.hasOption("n")) {
            try {
                numRecords = Long.parseLong(cmd.getOptionValue("n"));
                if (numRecords <= 0) {
                    System.err.println("Number of records must be a positive number.");
                    System.exit(1);
                    return;
                }
            } catch (NumberFormatException e) {
                logger.error("Invalid number of records value: {}", cmd.getOptionValue("n"));
                System.err.println("Invalid number of records value: " + cmd.getOptionValue("n"));
                System.exit(1);
                return;
            }
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

        // Override properties from command line
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        long totalMessagesSent = 0;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
             BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {

            List<String> lines = new ArrayList<>();

            // Read all lines from the input file
            String line = reader.readLine();
            while (line != null && lines.size() <= 1_000) {
                lines.add(line);
                line = reader.readLine();
            }
            if (lines.isEmpty()) {
                lines.add("DEFAULT-KEY,DEFAULT-VALUE-" + UUID.randomUUID().toString() );
                System.out.println("Using default key and value");
            }
            if (lines.size() == 1_000) {
                System.out.println("Stopped reading input file at 1,000 lines");
            }

            logger.info("Starting to send messages to topic '{}'. Press Ctrl+C to exit.", topicName);
            int lineIndex = 0;

            // Main loop for sending messages
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Producer thread interrupted, exiting send loop.");
                    break;
                }
                if (numRecords != -1 && totalMessagesSent >= numRecords) {
                    logger.info("Target number of records ({}) has been sent.", numRecords);
                    break;
                }

                line = lines.get(lineIndex);
                String k = null;
                String v = null;
                if (line.contains(",")) {
                    String[] parts = line.split(",", 2);
                    k = parts[0].trim();
                    v = parts[1].trim();
                } else {
                    v = line;
                }
                final String key = k, value = v;
                final ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, value);
                final long sentCount = totalMessagesSent;
                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        // Log less verbosely for successful sends, or use TRACE/DEBUG
                        logger.info("SENT {} : {} - {}",
                                key, String.format("%.50s", value), String.format("%06d", sentCount));
                    } else {
                        logger.error("Error sending record (key={}, value={})", key, value, exception);
                        // If the send error is due to an interruption, the main loop's interrupt check will handle it.
                    }
                });
                totalMessagesSent++;
                
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        logger.info("Producer delay sleep interrupted. Will exit send loop.", e);
                        Thread.currentThread().interrupt(); // Re-assert interrupt status
                        break; // Exit the while loop
                    }
                }

                lineIndex = (lineIndex + 1) % lines.size(); // Cycle through the lines
            } // End of while loop

            logger.debug("Flushing remaining messages...");
            producer.flush();
            logger.debug("All messages flushed.");
            producer.close(Duration.ofMillis(5000L));

        } catch (org.apache.kafka.common.errors.InterruptException e) { // Kafka's specific runtime interrupt exception
            logger.warn("Kafka operation (e.g., flush or close) was interrupted.", e);
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } catch (IOException e) {
            logger.error("Error reading input file: {}", inputFilePath, e);
            System.err.println("Error reading input file: " + inputFilePath + " - " + e.getMessage());
        } catch (Exception e) {
            logger.error("Exception occurred: ", e);
            System.err.println("Exception occurred: " + e.getMessage());
        } finally {
            logger.info("KeyValueProducer finished. Total messages attempted: {}", totalMessagesSent);
        }
    }
}