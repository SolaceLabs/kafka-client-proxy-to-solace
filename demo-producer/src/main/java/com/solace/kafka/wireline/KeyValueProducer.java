package com.solace.kafka.wireline;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.commons.cli.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
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
        options.addOption("p", "throughput", true, "Maximum messages per second");
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

        String configFilePath = cmd.getOptionValue("c");
        String topicName = cmd.getOptionValue("t");
        String inputFilePath = cmd.getOptionValue("i");
        int throughput = -1;
        long numRecords = -1;

        if (cmd.hasOption("p")) {
            try {
                throughput = Integer.parseInt(cmd.getOptionValue("p"));
                if (throughput <= 0) {
                    System.err.println("Throughput must be a positive number.");
                    System.exit(1);
                    return;
                }
            } catch (NumberFormatException e) {
                logger.error("Invalid throughput value: {}", cmd.getOptionValue("p"));
                System.err.println("Invalid throughput value: " + cmd.getOptionValue("p"));
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

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
             BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {

            String line;
            long startTime = System.nanoTime();
            int messagesSentThisSecond = 0;
            long nextSecond = System.nanoTime() + 1_000_000_000;
            long totalMessagesSent = 0;
            List<String> lines = new ArrayList<>();

            // Read all lines from the input file
            while ((line = reader.readLine()) != null) {
                    lines.add(line);
            }

            if (lines.isEmpty()) {
                System.err.println("Input file is empty.");
                logger.error("Input file is empty: {}", inputFilePath);
                System.exit(1);
                return;
            }
            int lineIndex = 0;
            while ((numRecords == -1) || (totalMessagesSent < numRecords)) { // Produce until numRecords or indefinitely
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
                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        // logger.info("Sent record(key={}, value={}) to partition={}, offset={}", key, value, metadata.partition(), metadata.offset());
                        logger.info("SENT {} : {}", key, value);
                    } else {
                        logger.error("Error sending record (key={}, value={})", key, value, exception);
                    }
                });
                messagesSentThisSecond++;
                totalMessagesSent++;

                if (throughput > 0) {
                    long now = System.nanoTime();
                    if (now >= nextSecond) {
                        if (messagesSentThisSecond < throughput) {
                            logger.warn("Sent fewer messages than the configured throughput: {} vs {}", messagesSentThisSecond, throughput);
                        }
                        messagesSentThisSecond = 0;
                        nextSecond = now + 1_000_000_000;
                    }
                    if (messagesSentThisSecond >= throughput) {
                        long sleepTime = nextSecond - now;
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime / 1_000_000);
                        }
                        messagesSentThisSecond = 0;
                        nextSecond = System.nanoTime() + 1_000_000_000;

                    }
                }
                lineIndex = (lineIndex + 1) % lines.size(); // Cycle through the lines
            }
            producer.flush();
            logger.info("Successfully sent {} messages from file: {}", totalMessagesSent, inputFilePath);
        } catch (IOException e) {
            logger.error("Error reading input file: {}", inputFilePath, e);
            System.err.println("Error reading input file: " + inputFilePath + " - " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Exception occurred: ", e);
            System.err.println("Exception occurred: " + e.getMessage());
            System.exit(1);
        }
    }
}