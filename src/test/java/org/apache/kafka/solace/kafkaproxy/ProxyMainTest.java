package org.apache.kafka.solace.kafkaproxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.solace.kafkaproxy.util.ProxyUtils;

class ProxyMainTest {

    @BeforeEach
    void setUp() {
        // Initialization code to run before each test, if needed
    }

    @Test
    @DisplayName("A sample test to ensure tests are running")
    void sampleTest() {
        assertTrue(true, "This is a placeholder test and should pass.");
    }

    @Test
    void testUuidGenerationFromNameBased() {
        String uuidAsString = ProxyUtils.generateMDAsUuidV4AsBase64String("SAMPLE_TOPIC_STRING");

        System.out.println("Generated UUID as String: " + uuidAsString);

        Uuid uuid = Uuid.fromString(uuidAsString);

        System.out.println("Generated UUID, Decoded: " + Base64.getDecoder().decode(uuid.toString()));

        System.out.println("Generated UUID as String: " + uuidAsString);

        System.out.println("Generated UUID: " + uuid.toString());

    }
}