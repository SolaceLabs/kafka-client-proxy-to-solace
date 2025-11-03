package com.solace.kafka.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import java.util.Properties;
import java.util.Base64;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.solace.kafka.kafkaproxy.util.ProxyUtils;

public class ProxyMain {

    // Solace JCSMP Properties and SSL config for Proxy->Broker connection
    private static final String SOLACE_PROPERTY_PREFIX = "solace.";

    private static final Logger log = LoggerFactory.getLogger(ProxyMain.class);
    private final String clusterId;
    private HealthCheckServer healthCheckServer = null;
    
    public ProxyMain() {
        // UUID uuid = UUID.randomUUID();

        byte[] clusterIdBytes = ProxyUtils.generateMDAsUuidV4AsBytes("This my CLUSTER");


        // byte[] src = ByteBuffer.wrap(new byte[16])
        //         .putLong(uuid.getMostSignificantBits())
        //         .putLong(uuid.getLeastSignificantBits())
        //         .array();
        this.clusterId = Base64.getUrlEncoder().encodeToString(clusterIdBytes).substring(0, 22);
        log.debug("Cluster id: " + this.clusterId);
    }
    
    private void startup(String args[]) {
        
        if (args.length <= 0) {
            log.warn("No properties file specified on command line");
            return;
        }
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(args[0])) {
            props.load(input);
        } catch (IOException ex) {
            log.warn("Could not load properties file: " + ex);
            return;
        }

        Properties solaceProperties = new Properties();
        Properties kafkaProperties = new Properties();
        for (Object key : props.keySet()) {
            final String propName = (String) key;
            if (propName.startsWith(SOLACE_PROPERTY_PREFIX)) {
                solaceProperties.put(propName.substring(SOLACE_PROPERTY_PREFIX.length()), ProxyConfig.resolvePropertyValueFromEnv(props.getProperty(propName)));
            } else {
                kafkaProperties.put(propName, ProxyConfig.resolvePropertyValueFromEnv(props.getProperty(propName)));
            }
        }

        ProxyPubSubPlusClient.getInstance().configure(solaceProperties);

        final ProxyConfig proxyConfig = new ProxyConfig(kafkaProperties);
        try {
            if (proxyConfig.getBoolean(ProxyConfig.HEALTHCHECKSERVER_CREATE)) {
                healthCheckServer = new HealthCheckServer();
                int healthCheckPort = proxyConfig.getInt(ProxyConfig.HEALTHCHECKSERVER_PORT);
                healthCheckServer.start(healthCheckPort);
                log.info("Health check server started on port {}", healthCheckPort);
            } else {
                log.info("Health check server creation is disabled.");
            }
        } catch (IOException e) {
            log.error("Failed to start health check server: {}", e.getMessage());
            return;
        }
        
        try {
            final ProxyReactor proxyReactor = new ProxyReactor(proxyConfig, clusterId);
            proxyReactor.start();

            if (healthCheckServer != null) {
                healthCheckServer.setHealthy(true);
            }

            proxyReactor.join();
        } catch (Exception e) {
            log.warn(e.toString());
        } finally {
            if (healthCheckServer != null) {
                healthCheckServer.stop();
            }
        }
        log.info("Proxy no longer running");
    }

     /**
     * @param args the command line arguments
     */
     public static void main(String[] args) {
        ProxyMain m = new ProxyMain();
        m.startup(args);
    }
}
