package com.solace.kafka.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import static org.apache.kafka.common.utils.Utils.getHost;
import static org.apache.kafka.common.utils.Utils.getPort;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBroker;
import org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBrokerCollection;
import org.apache.kafka.common.security.auth.SecurityProtocol;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    public static final String HEALTHCHECKSERVER_CREATE = PROXY_PROPERTY_PREFIX + "healthcheckserver.create";
    public static final String HEALTHCHECKSERVER_CREATE_DOC = "If true, the health check server will be created and started. If false, the health check server will not be created.";

    public static final String HEALTHCHECKSERVER_PORT = PROXY_PROPERTY_PREFIX + "healthcheckserver.port";
    public static final String HEALTHCHECKSERVER_PORT_DOC = "Port on which the health check server will listen. Default is 8080.";
    public static final int DEFAULT_HEALTHCHECKSERVER_PORT = 8080;

    private static final String PROPERTY_VALUE_PATTERN = "^\\$\\{(env:){0,1}(?<variableName>[A-Za-z0-9_]+)(:(?<defaultValue>.*)){0,1}\\}$";
    private static final Pattern PATTERN = Pattern.compile(PROPERTY_VALUE_PATTERN);
    private static final String VARIABLE_NAME = "variableName", DEFAULT_VALUE = "defaultValue";

    // public static final String K8S_EXTERNAL_LB_HOSTNAMES = PROXY_PROPERTY_PREFIX + "k8s.external.lb.hostnames";
    // public static final String K8S_EXTERNAL_LB_HOSTNAMES_DOC = "A comma-separated list of external load balancer hostnames for the Kafka proxy. Used to advertise the proxy to external clients.";

    public static final String K8S_INTERNAL_HOSTNAME_TOKEN = "${K8S_INTERNAL_HOSTNAME}";
    public static final String K8S_EXTERNAL_HOSTNAME_TOKEN = "${K8S_EXTERNAL_HOSTNAME}";
    public static final String K8S_POD_NAME_ENV = "POD_NAME";
    public static final String K8S_EXTERNAL_LB_HOSTNAMES_ENV = "EXTERNAL_LB_HOSTNAMES_LIST";

    public static final String HA_TUPLE_NODE_COUNT = PROXY_PROPERTY_PREFIX + "ha.tuple.node.count";
    public static final String HA_TUPLE_NODE_COUNT_DOC = "The number of nodes in the HA tuple.";
    public static final int DEFAULT_HA_TUPLE_NODE_COUNT = 0;        // 0 means not an HA tuple deployment

    public static final String HA_CLUSTER_NAME = PROXY_PROPERTY_PREFIX + "ha.cluster.name";
    public static final String DEFAULT_HA_CLUSTER_NAME = "KafkaProxyHACluster";
    public static final String HA_CLUSTER_NAME_DOC = "The name of the HA cluster advertised to clients; Default is " + DEFAULT_HA_CLUSTER_NAME;

    @Getter
    private static boolean haTuple;
    @Getter
    private static List<String> k8sExternalHostNamesAsList = new ArrayList<>();
    @Getter
    private static int k8sPodOrdinal = 0;
    @Getter
    private static String k8sStatefulSetPodBaseName = "";   // The pod name excluding the ordinal, but including the last '-'
    @Getter
    private static int haTupleNodeCount = 0;
    @Getter
    private static Map<Integer, MetadataResponseBrokerCollection> portToMetadataResponse = new HashMap<>();

    private static Properties kafkaProperties;

    private static Properties proxyProperties;

    private static ProxyConfig proxyConfig;

    public static ProxyConfig getInstance() {
        return proxyConfig;
    }

    /**
     * Returns the k8s pod ordinal if running in k8s and using k8s tokens in advertised listeners.
     * Otherwise, returns 0.
     * @return
     */
    public static int getNodeId() {
        return k8sPodOrdinal;
    }

    protected static List<String> getAdvertisedListenersConfig() throws ConfigException {
        // String advertisedListeners = proxyConfig.getString(ADVERTISED_LISTENERS_CONFIG);
        List<String> advertisedListenersList = proxyConfig.getList(ADVERTISED_LISTENERS_CONFIG);
        if (advertisedListenersList == null || advertisedListenersList.isEmpty()) {
            return Collections.emptyList();
        }
        boolean hasK8sInternalTokens = advertisedListenersList.stream()
                .anyMatch(listener -> listener.contains(K8S_INTERNAL_HOSTNAME_TOKEN));
        boolean hasK8sExternalTokens = advertisedListenersList.stream()
                .anyMatch(listener -> listener.contains(K8S_EXTERNAL_HOSTNAME_TOKEN));
        if (!hasK8sInternalTokens && !hasK8sExternalTokens) {
            haTuple = false;
            k8sPodOrdinal = 0;
            k8sStatefulSetPodBaseName = "";
            k8sExternalHostNamesAsList.clear();
            return proxyConfig.getList(ADVERTISED_LISTENERS_CONFIG);
        }

        // If we are here, the expectation is that we are running in Kubernetes
        // If we are running in Kubernetes, we need to resolve the K8S_INTERNAL_HOSTNAME_TOKEN and K8S_EXTERNAL_HOSTNAME_TOKEN
        // $EXTERNAL_LB_HOSTNAMES environment variable is expected to be set if resolving ${K8S_EXTERNAL_HOSTNAME} 
        // or ${K8S_INTERNAL_HOSTNAME} in advertised listeners respectively.

        // Get the K8S pod name and internal hostname
        String k8sInternalHostname = "NOT_FOUND";
        String k8sPodName = System.getenv(K8S_POD_NAME_ENV);
        if (hasK8sInternalTokens) {
            try {
                k8sInternalHostname = InetAddress.getLocalHost().getCanonicalHostName();
                if (k8sInternalHostname == null || k8sInternalHostname.isEmpty()) {
                    throw new ConfigException("Cannot resolve Kubernetes internal hostname");
                }
            } catch (UnknownHostException e) {
                throw new ConfigException("Cannot resolve Kubernetes internal hostname", e);
            }
        }

        // Get the pod ordinal - this value will be used to resolve external hostname from advertised listeners
        // AND to identify the broker node in the metadata and the group controller
        try {
            // extract pod ordinal from pod name
            int lastDashIndex = k8sPodName.lastIndexOf('-');
            if (lastDashIndex != -1 && lastDashIndex < k8sPodName.length() - 1) {
                String ordinalStr = k8sPodName.substring(lastDashIndex + 1);
                k8sPodOrdinal = Integer.parseInt(ordinalStr);      // k8sPodOrdinal is static
                k8sStatefulSetPodBaseName = k8sPodName.substring(0, lastDashIndex + 1); // includes the last '-'
            }
        } catch (NumberFormatException e) {
            throw new ConfigException("Cannot extract pod ordinal from pod name: " + k8sPodName, e);
        }

        // Determine if we are dealing with an HA tuple deployment (Kubernetes)
        haTupleNodeCount = proxyConfig.getInt(HA_TUPLE_NODE_COUNT);
        haTuple = haTupleNodeCount > 0;
        if (haTuple && (k8sPodOrdinal >= haTupleNodeCount) || (k8sPodOrdinal < 0)) {
            throw new ConfigException("Pod ordinal " + k8sPodOrdinal + " is out of bounds for HA tuple node count: " + haTupleNodeCount +
                                        ". Is property " + HA_TUPLE_NODE_COUNT + " set correctly for the StatefulSet?");
        }

        if (hasK8sExternalTokens) {
            // We need to get the external hostnames (load balancers) for each pod in the stateful set
            String k8sExternalHostnames = System.getenv(K8S_EXTERNAL_LB_HOSTNAMES_ENV);
            if (k8sExternalHostnames == null || k8sExternalHostnames.isEmpty()) {
                throw new ConfigException("Environment variable " + K8S_EXTERNAL_LB_HOSTNAMES_ENV + " is not set. Cannot resolve " + K8S_EXTERNAL_HOSTNAME_TOKEN +
                                            ". Is variable " + K8S_EXTERNAL_LB_HOSTNAMES_ENV + " set in the environment? " +
                                            "And does the list of loadbalancer hostnames match the number of pods in the StatefulSet?");
            }
            String[] externalHostnames = k8sExternalHostnames.split(",");
            if (k8sPodOrdinal < 0 || k8sPodOrdinal >= externalHostnames.length) {
                throw new ConfigException("Pod ordinal " + k8sPodOrdinal + " is out of bounds for external hostnames: " + k8sExternalHostnames + 
                                            ". Is variable " + K8S_EXTERNAL_LB_HOSTNAMES_ENV + " set in the environment? " +
                                            "And does the list of hostnames match the number of pods in the StatefulSet?");
            }
            
            // Gets external hostnames for all pods
            k8sExternalHostNamesAsList.clear();
            for (String hostname : externalHostnames) {
                k8sExternalHostNamesAsList.add(hostname.trim());
            }

            if (haTuple && k8sExternalHostNamesAsList.size() != haTupleNodeCount) {
                throw new ConfigException("Mismatch between HA tuple node count and external hostnames list size: Property " + HA_TUPLE_NODE_COUNT + "=" + haTupleNodeCount +
                                            " but environment variable " + K8S_EXTERNAL_LB_HOSTNAMES_ENV + " has " + k8sExternalHostNamesAsList.size() + " entries: " + k8sExternalHostnames);
            }
        }

        List<String> resolvedListeners = new ArrayList<>();
        for (String listener : advertisedListenersList) {
            final String trimmedListener = listener.trim();
            if (trimmedListener.contains(K8S_INTERNAL_HOSTNAME_TOKEN)) {
                String resolvedListener = trimmedListener.replace(K8S_INTERNAL_HOSTNAME_TOKEN, k8sInternalHostname);
                if (resolvedListener == null || resolvedListener.isEmpty()) {
                    throw new ConfigException("Cannot resolve " + K8S_INTERNAL_HOSTNAME_TOKEN + " in advertised listeners: " + trimmedListener);
                }
                resolvedListeners.add(resolvedListener);
            } else if (trimmedListener.contains(K8S_EXTERNAL_HOSTNAME_TOKEN)) {
                String resolvedListener = trimmedListener.replace(K8S_EXTERNAL_HOSTNAME_TOKEN, k8sExternalHostNamesAsList.get(k8sPodOrdinal));
                if (resolvedListener == null || resolvedListener.isEmpty()) {
                    throw new ConfigException("Cannot resolve " + K8S_EXTERNAL_HOSTNAME_TOKEN + " in advertised listeners: " + trimmedListener);
                }
                resolvedListeners.add(resolvedListener);
            } else {
                resolvedListeners.add(trimmedListener);
            }
        }

        // Configure HA tuple metadata response map if applicable
        // TODO: Maybe move this logic to constructor
        portToMetadataResponse.clear();
        if (haTuple) {
            for (String listener : advertisedListenersList) {
                final String trimmedListener = listener.trim();
                // get port from trimmedListener using string function after last ':'
                int port = 0;
                try {
                    String portAsString = trimmedListener.substring(trimmedListener.lastIndexOf(':') + 1);
                    port = Integer.parseInt(portAsString);
                } catch (Exception e) {
                    throw new ConfigException("Cannot extract port from advertised listener: " + trimmedListener);
                }
                if (portToMetadataResponse.containsKey(port)) {
                    throw new ConfigException("Duplicate port " + port + " found in advertised listeners: " + advertisedListenersList +
                                                ". When using HA tuple, each listener port can only be specified once.");
                }
                MetadataResponseBrokerCollection metadataResponseBrokerCollection = new MetadataResponseBrokerCollection();
                for (int n = 0; n < haTupleNodeCount; n++) {
                    String hostname;
                    if (trimmedListener.contains(K8S_INTERNAL_HOSTNAME_TOKEN)) {
                        hostname = k8sStatefulSetPodBaseName + Integer.toString(n);
                    } else if (trimmedListener.contains(K8S_EXTERNAL_HOSTNAME_TOKEN)) {
                        hostname = k8sExternalHostNamesAsList.get(n);
                    } else {
                        throw new ConfigException("When using HA tuple, advertised listener must contain " + K8S_INTERNAL_HOSTNAME_TOKEN +
                                                    " or " + K8S_EXTERNAL_HOSTNAME_TOKEN + ": '" + trimmedListener + "' entry is invalid.");
                    }
                    MetadataResponseBroker metadataResponseBroker = new MetadataResponseBroker();
                    metadataResponseBroker.setNodeId(n);
                    metadataResponseBroker.setHost(hostname);
                    metadataResponseBroker.setPort(port);
                    metadataResponseBroker.setRack(null);
                    metadataResponseBrokerCollection.add(metadataResponseBroker);
                }
                portToMetadataResponse.put(port, metadataResponseBrokerCollection);
            }
        }
        return resolvedListeners;
    }
    
    public static String resolvePropertyValueFromEnv(final String propertyValue) throws ConfigException{
        if (propertyValue == null || propertyValue.isEmpty()) {
            return null;
        }

        final Matcher matcher = PATTERN.matcher(propertyValue);
        if (matcher.matches()) {
            String variableName = matcher.group(VARIABLE_NAME);
            String defaultValue = matcher.group(DEFAULT_VALUE);
            defaultValue = defaultValue == null ? "" : defaultValue;
            String resolvedValue = System.getenv(variableName);
            return resolvedValue != null ? resolvedValue : defaultValue;
        } else {
            return propertyValue;
        }
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
                                .define(FETCH_COMPRESSION_TYPE, Type.STRING, DEFAULT_FETCH_COMPRESSION_TYPE, new ConfigDef.NonNullValidator(), Importance.LOW, FETCH_COMPRESSION_TYPE_DOC)
                                .define(HEALTHCHECKSERVER_CREATE, Type.BOOLEAN, false, Importance.MEDIUM, HEALTHCHECKSERVER_CREATE_DOC)
                                .define(HEALTHCHECKSERVER_PORT, Type.INT, DEFAULT_HEALTHCHECKSERVER_PORT, Importance.MEDIUM, HEALTHCHECKSERVER_PORT_DOC)
                                .define(HA_TUPLE_NODE_COUNT, Type.INT, DEFAULT_HA_TUPLE_NODE_COUNT, Importance.MEDIUM, HA_TUPLE_NODE_COUNT_DOC)
                                .define(HA_CLUSTER_NAME, Type.STRING, DEFAULT_HA_CLUSTER_NAME, new ConfigDef.NonNullValidator(), Importance.MEDIUM, HA_CLUSTER_NAME_DOC);
                                // .define(K8S_EXTERNAL_LB_HOSTNAMES, Type.STRING, "", new ConfigDef.NonNullValidator(), Importance.MEDIUM, K8S_EXTERNAL_LB_HOSTNAMES_DOC);
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
