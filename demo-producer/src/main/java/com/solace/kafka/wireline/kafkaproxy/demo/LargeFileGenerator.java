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

package com.solace.kafka.wireline.kafkaproxy.demo;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

public class LargeFileGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MIN_LENGTH = 10240;
    private static final int MAX_LENGTH = 51200;
    private static final int NUM_LINES = 100;
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java LargeFileGenerator <output-file-path>");
            System.err.println("Example: java LargeFileGenerator getting-started/test-data/publish-long-record");
            System.exit(1);
        }

        String filePath = args[0];
        System.out.println("Generating file: " + filePath);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (int i = 0; i < NUM_LINES; i++) {
                int length = MIN_LENGTH + RANDOM.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
                for (int j = 0; j < length; j++) {
                    writer.write(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
                }
                writer.newLine();
                System.out.println("Generated line " + (i + 1) + " of " + NUM_LINES + " with " + length + " characters.");
            }
            System.out.println("\nSuccessfully generated " + NUM_LINES + " lines in " + filePath);
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
