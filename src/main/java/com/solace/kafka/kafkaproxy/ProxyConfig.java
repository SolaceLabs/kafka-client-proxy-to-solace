package com.solace.kafka.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import static org.apache.kafka.common.utils.Utils.getHost;
import static org.apache.kafka.common.utils.Utils.getPort;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.security.auth.SecurityProtocol;

public class ProxyConfig  extends AbstractConfig {
	
    public static final String LISTENERS_CONFIG = "listeners";
    public static final String LISTENERS_DOC = "A list of [protocol://]host:[port] tuples to listen on.";
    public static final String ADVERTISED_LISTENERS_CONFIG = "advertised.listeners";
    public static final String ADVERTISED_LISTENERS_DOC = "An optional list of host:[port] tuples to reflect what external clients can connect to.";	
    
    // TODO: Implement respect for this value
    public static final String PRODUCE_MESSAGE_MAX_BYTES = "message.max.bytes";
    public static final String PRODUCE_MESSAGE_MAX_BYTES_DOC = "Maximum allowed record size (bytes) that can be produced to a topic";
    public static final int DEFAULT_PRODUCE_MESSAGE_MAX_BYTES = 1_048_576;

    private static final Pattern SECURITY_PROTOCOL_PATTERN = Pattern.compile("(.*)?://.*");

    private static final String PROXY_PROPERTY_PREFIX = "proxy.";

    public static final String SEPARATOR_CONFIG = PROXY_PROPERTY_PREFIX + "separators";
    public static final String SEPARATOR_DOC = "A list of chars of typical Kafka topic separators that the Proxy will convert to Solace separator '/'";
	
    public static final String REQUEST_HANDLER_THREADS_CONFIG = PROXY_PROPERTY_PREFIX + "request.handler.threads";
    public static final String REQUEST_HANDLER_THREADS_DOC = "Number of threads for handling blocking Kafka requests.";
    // TODO: Eval if this should be dynamic setting suggested
    public static final int DEFAULT_REQUEST_HANDLER_THREADS = 32;  // Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    public static final String PARTITIONS_PER_TOPIC = PROXY_PROPERTY_PREFIX + "partitions.per.topic";
    public static final String PARTITIONS_PER_TOPIC_DOC = "Number of virtual partitions per topic.";
    public static final int DEFAULT_PARTITIONS_PER_TOPIC = 100;

    public static final String QUEUENAME_QUALIFIER = PROXY_PROPERTY_PREFIX + "queuename.qualifier";
    public static final String QUEUENAME_QUALIFIER_DOC = "Qualifier expected on consumer subscribed queues. e.g.: Queue name Qualifier = 'kafka-proxy' --> Expected queue name = 'kafka-proxy/QUEUE_NAME[/Group Name]";

    public static final String QUEUENAME_IS_TOPICNAME = PROXY_PROPERTY_PREFIX + "queuename.is.topicname";
    public static final String QUEUENAME_IS_TOPICNAME_DOC = "true/false -- If true, then expected Solace Queue Name is the Kafka consumer subscribed topic name as given. Do not consider queue qualifier or consumer group ID.";

    public static final String FETCH_COMPRESSION_TYPE = PROXY_PROPERTY_PREFIX + "fetch.compression.type";
    public static final String FETCH_COMPRESSION_TYPE_DOC = "Type of compression to use when fetching records from Kafka proxy. Valid values are `none`, `gzip`, `snappy`, `lz4`, and `zstd`. Applies to all Kafka topics and consumers for the proxy instance.";
    public static final String DEFAULT_FETCH_COMPRESSION_TYPE = "none";

    public static final String MAX_UNCOMMITTED_MESSAGES_PER_FLOW = PROXY_PROPERTY_PREFIX + "max.uncommitted.messages";
    public static final String MAX_UNCOMMITTED_MESSAGES_PER_FLOW_DOC = "Maximum number of uncommitted messages read from a queue before Fetch requests halt.";
    public static final long DEFAULT_MAX_UNCOMMITTED_MESSAGES_PER_FLOW = 1_000L;

    private static Properties kafkaProperties;

    private static Properties proxyProperties;

    private static ProxyConfig proxyConfig;

    public static ProxyConfig getInstance() {
        return proxyConfig;
    }
    
    /**
     * Extracts the security protocol from a "protocol://host:port" address string.
     * @param address address string to parse
     * @return security protocol or null if the given address is incorrect
     */
    public static String getSecurityProtocol(String address) {
        Matcher matcher = SECURITY_PROTOCOL_PATTERN.matcher(address);
        return matcher.matches() ? matcher.group(1) : null;
    }

    public static Properties getKafkaProperties() { return kafkaProperties; }

    public static Properties getProxyProperties() { return proxyProperties; }
    
	// Similar to ClientsUtils::parseAndValidateAddresses but added support for protocol as part of string 
	// to be of style of "listener" configuration item for broker
    public static List<ProxyReactor.ListenEntry> parseAndValidateListenAddresses(List<String> urls) {
        List<ProxyReactor.ListenEntry> addresses = new ArrayList<ProxyReactor.ListenEntry>();
        for (String url : urls) {
            if (url != null && url.length() > 0) {
            	String protocolString = getSecurityProtocol(url);
            	SecurityProtocol securityProtocol = SecurityProtocol.PLAINTEXT;
            	if (protocolString != null) {
            		try {
            			securityProtocol = SecurityProtocol.forName(protocolString);
            		} catch (IllegalArgumentException e) {
                        throw new ConfigException("Invalid security protocol " + LISTENERS_CONFIG + ": " + url);
            		}
            	}
                String host = getHost(url);
                Integer port = getPort(url);
                if (host == null || port == null)
                    throw new ConfigException("Invalid url in " + LISTENERS_CONFIG + ": " + url);
                try {
                    InetSocketAddress address = new InetSocketAddress(host, port);
                    addresses.add(new ProxyReactor.ListenEntry(securityProtocol, address));
                } catch (NumberFormatException e) {
                    throw new ConfigException("Invalid host:port in " + LISTENERS_CONFIG + ": " + url);
                }
            }
        }
        if (addresses.size() < 1)
            throw new ConfigException("No urls given in " + LISTENERS_CONFIG);
        return addresses;
    }
    
	// Similar to ClientsUtils::parseAndValidateAddresses but advertised listeners is optional.
    // Also, does not have logic for resolving host name
    // Returns null if no configuration provided, otherwise returns list of addresses.
    public static List<InetSocketAddress> parseAndValidateAdvertisedListenAddresses(List<String> urls) {
        if (urls.size() == 0) { return null; }
        List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        for (String url : urls) {
            if (url != null && url.length() > 0) {
                String host = getHost(url);
                Integer port = getPort(url);
                if (host == null || port == null)
                    throw new ConfigException("Invalid url in " + ADVERTISED_LISTENERS_CONFIG + ": " + url);
                try {
                    InetSocketAddress address = new InetSocketAddress(host, port);
                    addresses.add(address);
                } catch (NumberFormatException e) {
                    throw new ConfigException("Invalid host:port in " + ADVERTISED_LISTENERS_CONFIG + ": " + url);
                }
            }
        }
        return addresses;
    } 

    /*
     * NOTE: DO NOT CHANGE EITHER CONFIG STRINGS OR THEIR JAVA VARIABLE NAMES AS THESE ARE PART OF THE PUBLIC API AND
     * CHANGE WILL BREAK USER CODE.
     */

    private static final ConfigDef CONFIG;

    static {
        CONFIG = new ConfigDef().define(LISTENERS_CONFIG, Type.LIST, Collections.emptyList(), new ConfigDef.NonNullValidator(), Importance.HIGH, LISTENERS_DOC)
                                .define(ADVERTISED_LISTENERS_CONFIG, Type.LIST, Collections.emptyList(), new ConfigDef.NonNullValidator(), Importance.HIGH, ADVERTISED_LISTENERS_DOC) 
                                .define(PRODUCE_MESSAGE_MAX_BYTES, Type.INT, DEFAULT_PRODUCE_MESSAGE_MAX_BYTES, new ConfigDef.NonNullValidator(), Importance.MEDIUM, PRODUCE_MESSAGE_MAX_BYTES_DOC)
                                .withClientSslSupport()
                                .define(SEPARATOR_CONFIG, Type.STRING, "", new ConfigDef.NonNullValidator(), Importance.HIGH, SEPARATOR_DOC) 
                                .define(REQUEST_HANDLER_THREADS_CONFIG, Type.INT, DEFAULT_REQUEST_HANDLER_THREADS, new ConfigDef.NonNullValidator(), Importance.HIGH, REQUEST_HANDLER_THREADS_DOC)
                                .define(PARTITIONS_PER_TOPIC, Type.INT, DEFAULT_PARTITIONS_PER_TOPIC, new ConfigDef.NonNullValidator(), Importance.HIGH, PARTITIONS_PER_TOPIC_DOC)
                                .define(MAX_UNCOMMITTED_MESSAGES_PER_FLOW, Type.LONG, DEFAULT_MAX_UNCOMMITTED_MESSAGES_PER_FLOW, new ConfigDef.NonNullValidator(), Importance.MEDIUM, MAX_UNCOMMITTED_MESSAGES_PER_FLOW_DOC)
                                .define(QUEUENAME_QUALIFIER, Type.STRING, "", new ConfigDef.NonNullValidator(), Importance.HIGH, QUEUENAME_QUALIFIER_DOC)
                                .define(QUEUENAME_IS_TOPICNAME, Type.BOOLEAN, false, Importance.LOW, QUEUENAME_IS_TOPICNAME_DOC)
                                .define(FETCH_COMPRESSION_TYPE, Type.STRING, DEFAULT_FETCH_COMPRESSION_TYPE, new ConfigDef.NonNullValidator(), Importance.LOW, FETCH_COMPRESSION_TYPE_DOC);
    }
    
    // TODO: Validate properties to ensure that values are valid before starting the proxy

    public ProxyConfig(Properties props) {
        super(CONFIG, props, false /* do not log values */);
        kafkaProperties = new Properties();
        proxyProperties = new Properties();
        props.forEach( ( k, v ) -> {
            final String propName = (String) k;
            if (propName.startsWith(PROXY_PROPERTY_PREFIX)) {
                proxyProperties.put(propName, v);
            } else {
                kafkaProperties.put(k, v);
            }
        });
        proxyConfig = this;
    }

    public ProxyConfig(Map<String, Object> props) {
        super(CONFIG, props, false /* do not log values */);
        kafkaProperties = new Properties();
        proxyProperties = new Properties();
        props.forEach( ( k, v ) -> {
            final String propName = (String) k;
            if (propName.startsWith(PROXY_PROPERTY_PREFIX)) {
                proxyProperties.put(propName, v);
            } else {
                kafkaProperties.put(k, v);
            }
        });
        proxyConfig = this;
    }
}
