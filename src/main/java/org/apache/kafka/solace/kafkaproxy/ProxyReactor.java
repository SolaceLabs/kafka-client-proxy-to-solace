package org.apache.kafka.solace.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.CancelledKeyException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;

import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.security.ssl.SslFactory;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.message.*;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.network.PlaintextTransportLayer;
import org.apache.kafka.common.network.SslTransportLayer;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.network.ChannelMetadataRegistry;
import org.apache.kafka.common.network.CipherInformation;
import org.apache.kafka.common.network.ClientInformation;
import org.apache.kafka.common.network.ConnectionMode;      // Kafka >= 2.5 (recommended)

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ProxyReactor extends Thread {
    
    // Defines a configuration entry for a listen port
    static class ListenEntry {
        final SecurityProtocol  securityProtocol;  // e.g. PLAINTEXT, SSL, etc.
        final InetSocketAddress address;
        final String entrytName;
    
            
        ListenEntry(SecurityProtocol securityProtocol, InetSocketAddress address) {
            this.securityProtocol = securityProtocol;
            this.address = address;
            this.entrytName = securityProtocol + " " + address;
        }
        
        public SecurityProtocol getSecurityProtocol() { return securityProtocol; }
        
        public InetSocketAddress getAddress() { return address; }
        
        public String getEntryName() { return entrytName; }
        
        // Does not include entryName since it is just string representation of the other fields
        public String toString() {
            return "ListenEntry{" + 
                   "securityProtocol=" + securityProtocol + 
                   ", address=" + address +
                   "}";
         }
    }
    
    // Tracks state kept for each listen port
    public class ListenPort {
        private final ListenEntry listenEntry;     // configuration for the listen port
        private final InetSocketAddress advertisedListenEntry; // optional IP/port advertised to clients
        private final ServerSocketChannel serverSocketChannel;
        private final Set<ProxyChannel> channels;  // channels accepted on this listen port
        private final MetadataResponseData.MetadataResponseBrokerCollection brokers = new MetadataResponseData.MetadataResponseBrokerCollection();
        private final SslFactory sslFactory;
        
        // taken from org.apache.kafka.common.network.ChannelBuilders.channelBuilderConfigs()
        @SuppressWarnings("unchecked")
        private Map<String, Object> channelBuilderConfigs(final AbstractConfig config, final ListenerName listenerName) {
            Map<String, Object> parsedConfigs;
            if (listenerName == null) {
                parsedConfigs = (Map<String, Object>) config.values(); // need to suppress warning as config.values() is <String, ?>
            } else {
                parsedConfigs = config.valuesWithPrefixOverride(listenerName.configPrefix());
            }
            config.originals().entrySet().stream()
                .filter(e -> !parsedConfigs.containsKey(e.getKey())) // exclude already parsed configs
                // exclude already parsed listener prefix configs
                .filter(e -> !(listenerName != null && e.getKey().startsWith(listenerName.configPrefix()) &&
                    parsedConfigs.containsKey(e.getKey().substring(listenerName.configPrefix().length()))))
                // exclude keys like `{mechanism}.some.prop` if "listener.name." prefix is present and key `some.prop` exists in parsed configs.
                .filter(e -> !(listenerName != null && parsedConfigs.containsKey(e.getKey().substring(e.getKey().indexOf('.') + 1))))
                .forEach(e -> parsedConfigs.put(e.getKey(), e.getValue()));
            return parsedConfigs;
        }

        public ListenPort(ListenEntry listenEntry, InetSocketAddress advertisedListenEntry, 
                          ProxyConfig config) throws IOException {
            this.listenEntry = listenEntry;
            this.advertisedListenEntry = advertisedListenEntry; // will be null if not set
            channels = new HashSet<ProxyChannel>();
            final InetSocketAddress address = listenEntry.getAddress();
            final InetSocketAddress advertisedAddress = (advertisedListenEntry == null) ? address : advertisedListenEntry;
            brokers.add(new MetadataResponseData.MetadataResponseBroker()
                .setNodeId(0)
                .setHost(advertisedAddress.getHostString())
                .setPort(advertisedAddress.getPort())
                .setRack(null));
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(address);
            serverSocketChannel.register(ProxyReactor.this.selector, SelectionKey.OP_ACCEPT, this);
            log.info("Listening for incoming connections on " + getName() +
                    ((this.advertisedListenEntry == null) ? "" : ", advertising " +  this.advertisedListenEntry));
            if (listenEntry.getSecurityProtocol() == SecurityProtocol.PLAINTEXT) {
                sslFactory = null;
            } else {
                // sslFactory = new SslFactory(Mode.SERVER);            // Kafka < 2.5
                sslFactory = new SslFactory(ConnectionMode.SERVER);     // Kafka >= 2.5
                sslFactory.configure(channelBuilderConfigs(config, null /* listenerName */));
            }
        }
        
        public String getName() { return listenEntry.getEntryName(); }
        
        public String toString() {
           return "ListenPort{" + 
                  "listenEntry=" + listenEntry.toString() +
                  ((advertisedListenEntry == null) ? "" : ", advertisedListenEntry=" + advertisedListenEntry) +
                  ", channels=" + channels.toString() +
                  ", brokers=" + brokers.toString() +
                  ((sslFactory == null) ? "" : ", sslFactory=" + sslFactory.toString()) +
                  "}";
        }

        void addChannel(ProxyChannel channel) {
            synchronized (channels) {
                channels.add(channel);
            }
        }

        void removeChannel(ProxyChannel channel) {
            synchronized (channels) {
                channels.remove(channel);
            }
        }
        
        TransportLayer createTransportLayer(SocketChannel socketChannel, SelectionKey key) throws IOException {
            if (listenEntry.securityProtocol == SecurityProtocol.PLAINTEXT) {
                return new PlaintextTransportLayer(key);
            } else {
                final String channelId = socketChannel.socket().getRemoteSocketAddress().toString();
                return SslTransportLayer.create(channelId, key,
                                                sslFactory.createSslEngine(socketChannel.socket().getInetAddress().getHostAddress(),
                                                                           socketChannel.socket().getPort()),
                                                new ProxyChannelMetadataRegistry());
            }
        }
        
        public SecurityProtocol getSecurityProtocol() { return listenEntry.securityProtocol; }
                
        public MetadataResponseData.MetadataResponseBrokerCollection brokers() { return brokers; }

        public String clusterId() { return ProxyReactor.this.clusterId; }

        private void close() {
            log.info("Shutting down listen port: " + listenEntry.getSecurityProtocol() + " " + listenEntry.getAddress());
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
            }
            // We take a copy of the set contents since channels de-register on the set so we are not
            // iterating over the same set as we close channels (and remove them from the set).
            ProxyChannel[] channelsToStop;
            synchronized (channels)
            {
                channelsToStop = (ProxyChannel[]) channels.toArray(new ProxyChannel[channels.size()]);
                channels.clear();
            }
            for (int i = 0; i < channelsToStop.length; i++)
            {
                channelsToStop[i].close("Shutting down listen port");
            }
        }
        
        // Called from another thread (from Solace Java client API)
        void addToWorkQueue(WorkEntry workEntry) {
            ProxyReactor.this.addToWorkQueue(workEntry);
        }
    }
        
    abstract static class WorkEntry {
        private final ProxyChannel proxyChannel;
        
        public WorkEntry(ProxyChannel proxyChannel) {
            this.proxyChannel = proxyChannel;
        }
        
        public ProxyChannel getProxyChannel() { return proxyChannel; }
        
        public void addToWorkQueue() {
            proxyChannel.getListenPort().addToWorkQueue(this);
        }
    }

    /**
     * Code taken from:
     * https://github.com/apache/kafka/blob/trunk/clients/src/test/java/org/apache/kafka/common/network/DefaultChannelMetadataRegistry.java
     * --> DefaultChannelDataRegistry is not public
     */
    static class ProxyChannelMetadataRegistry implements ChannelMetadataRegistry{

        private CipherInformation cipherInformation;
        private ClientInformation clientInformation;

        ProxyChannelMetadataRegistry() { }

        @Override
        public void registerCipherInformation(final CipherInformation cipherInformation) {
            this.cipherInformation = cipherInformation;
        }

        @Override
        public CipherInformation cipherInformation() {
            return this.cipherInformation;
        }

        @Override
        public void registerClientInformation(final ClientInformation clientInformation) {
            this.clientInformation = clientInformation;
        }

        @Override
        public ClientInformation clientInformation() {
            return this.clientInformation;
        }

        @Override
        public void close() {
            this.cipherInformation = null;
            this.clientInformation = null;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ProxyReactor.class);
    private Selector selector;
    private boolean reactorRunning;
    private String clusterId;
    private final BlockingQueue<WorkEntry> workQueue;
    private ListenPort listenPorts[];
    private static ExecutorService requestHandlerExecutor;
    
    public ProxyReactor(ProxyConfig config, String clusterId) throws Exception {
        this.setName("Kafka_Proxy_Reactor");
        this.clusterId = clusterId;
        selector = SelectorProvider.provider().openSelector();
        workQueue = new LinkedBlockingQueue<WorkEntry>();
        final List<ProxyReactor.ListenEntry> listenerConfig = ProxyConfig.parseAndValidateListenAddresses(config.getList(ProxyConfig.LISTENERS_CONFIG));
        final List<InetSocketAddress> advertisedListenerConfig = ProxyConfig.parseAndValidateAdvertisedListenAddresses(config.getList(ProxyConfig.ADVERTISED_LISTENERS_CONFIG));
        final int numListenPorts = listenerConfig.size();
        if ((advertisedListenerConfig != null) && 
            (advertisedListenerConfig.size() != numListenPorts)) {
            throw new ConfigException("Entry count in " + ProxyConfig.ADVERTISED_LISTENERS_CONFIG +
                                      " does not match entry count in " + ProxyConfig.LISTENERS_CONFIG);
        }
        listenPorts = new ListenPort[numListenPorts];
        final Iterator<ProxyReactor.ListenEntry> iter = listenerConfig.iterator();
        int index = 0;
        while (iter.hasNext()) {
        	ProxyReactor.ListenEntry nextListenEntry = iter.next();
        	try {
        	    listenPorts[index] = new ListenPort(
                    nextListenEntry, 
                    (advertisedListenerConfig == null) ? null : advertisedListenerConfig.get(index),
                    config);
                index++;
        	} catch (Exception e) {
                throw new ConfigException("Could not create listener " + nextListenEntry.getEntryName() + " : " + e.toString());
        	}
        }
        // Initialize the shared executor service if not already done
        if (requestHandlerExecutor == null) {
            int numThreads = config.getInt(ProxyConfig.REQUEST_HANDLER_THREADS_CONFIG);
            log.info("Initializing request handler thread pool with {} threads.", numThreads);
            ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("kafka-proxy-request-handler-%d")
                .setDaemon(true) // Optional: make threads daemons
                .build();
            requestHandlerExecutor = Executors.newFixedThreadPool(numThreads, namedThreadFactory);
        }
        reactorRunning = false;
    }
    
    // Called from another thread (from Solace Java client API)
    void addToWorkQueue(WorkEntry workEntry) {
        if (reactorRunning) { // do not add if no longer running
            try {
                workQueue.put(workEntry);
                selector.wakeup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                close("Reactor interrupted while adding to work queue.");
            }            
        }
    }

    private void acceptConnection(SelectionKey key) {
        final ProxyReactor.ListenPort listenPort = (ListenPort) key.attachment();
    	try {
	        SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
	        socketChannel.configureBlocking(false);
	    	log.info("New incoming connection request on " + listenPort.getName() + " from " + socketChannel.socket().getRemoteSocketAddress());
	        SelectionKey channelKey = socketChannel.register(selector, SelectionKey.OP_READ);
	        channelKey.attach(new ProxyChannel(socketChannel, 
	                                           listenPort.createTransportLayer(socketChannel, channelKey),
	                                           listenPort));
        } catch (Exception e) {
            log.error("Could not accept connection  on " + listenPort.getName() + ": " + e);
        }
    }
    
    private void closeAllListenPorts() {
        for (int index = 0; index < listenPorts.length; index++) {
            if (listenPorts[index] != null) listenPorts[index].close();
        }
        try {
            selector.close();
        } catch (Exception IOException) {
            
        }
    }
    
    public static ExecutorService getRequestHandlerExecutor() {
        return requestHandlerExecutor;
    }

    public void close(String reason) {
        log.info("Stopping reactor thread");
        reactorRunning = false;
        if (selector.isOpen()) {
            selector.wakeup(); // Interrupt the select() call
        }

        // Shutdown executor service
        if (requestHandlerExecutor != null && !requestHandlerExecutor.isShutdown()) {
            log.info("Shutting down request handler executor service...");
            requestHandlerExecutor.shutdown();
            try {
                if (!requestHandlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    requestHandlerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                requestHandlerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
    	reactorRunning = true;
    	while (reactorRunning) {
    		try {
    		    selector.select();
                Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();
                    try {
                        if (key.isAcceptable()) {
                            acceptConnection(key);
                        } else {
	                        if (key.isReadable()) {
	                            ((ProxyChannel) key.attachment()).readFromChannel();
	                        }
	                        if (key.isWritable()) {
	                            ((ProxyChannel) key.attachment()).writeToChannel();
	                        }
                        }
                    } catch (CancelledKeyException e) {
                    	// ignore
                    }
                }
                while (true) {
                    final WorkEntry workEntry = workQueue.poll();
                    if (workEntry == null) break;
                    workEntry.getProxyChannel().handleWorkEntry(workEntry);
                }
    		} catch (IOException e) {
                log.error("Error during select: " + e);
    		    reactorRunning = false;
    	    }
    	}
        
        closeAllListenPorts();
        ProxyPubSubPlusClient.close(); // Ensure this is safe to call multiple times or only once
        workQueue.clear();
    }
}