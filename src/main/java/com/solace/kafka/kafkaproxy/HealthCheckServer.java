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
package com.solace.kafka.kafkaproxy;

import com.sun.net.httpserver.HttpServer;

import lombok.extern.slf4j.Slf4j;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

@Slf4j
public class HealthCheckServer {
    private HttpServer server;
    private volatile boolean isHealthy = false;
    
    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Health check endpoint
        server.createContext("/health", new HealthHandler());
        server.createContext("/ready", new ReadinessHandler());
        
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        
        log.info("Health check server started on port {}", port);
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Health check server stopped");
        }
    }
    
    public void setHealthy(boolean healthy) {
        this.isHealthy = healthy;
    }
    
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = isHealthy ? "OK" : "UNHEALTHY";
            int statusCode = isHealthy ? 200 : 503;
            log.trace("Health check response: {}", response);
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            } catch (IOException e) {
                log.error("Error writing response", e);
            } finally {
                exchange.close();
            }
        }
    }
    
    private class ReadinessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Always ready if the server is running
            String response = "READY";
            log.trace("Readiness check response: {}", response);
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            } catch (IOException e) {
                log.error("Error writing response", e);
            } finally {
                exchange.close();
            }
        }
    }
}