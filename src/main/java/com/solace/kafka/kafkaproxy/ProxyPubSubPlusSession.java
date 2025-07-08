package com.solace.kafka.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.InvalidPropertiesException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessage.MessageUserPropertyConstants;

import com.solacesystems.jcsmp.XMLMessageProducer;
import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.DeliveryMode;


import java.util.Set;
import java.util.HashSet;
import java.util.Properties;
import java.util.Base64;
import java.io.UnsupportedEncodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class ProxyPubSubPlusSession {
    private static final Logger log = LoggerFactory.getLogger(ProxyPubSubPlusSession.class);
    private final Set<ProxyChannel> channels;
    private final JCSMPSession session;
    private final XMLMessageProducer publisher;
    private final String topicSeparatorReplace;
   
    public ProxyPubSubPlusSession(Properties baseServiceProps,
            ProxyChannel channel,
            byte[] username, byte[] password)
            throws UnsupportedEncodingException, InvalidPropertiesException, JCSMPException {
		channels = new HashSet<ProxyChannel>();
		addChannel(channel);
		final JCSMPProperties properties = new JCSMPProperties();
		properties.setProperty(JCSMPProperties.HOST, baseServiceProps.getProperty(JCSMPProperties.HOST));
        if (baseServiceProps.containsKey(JCSMPProperties.VPN_NAME)) {
		    properties.setProperty(JCSMPProperties.VPN_NAME, baseServiceProps.getProperty(JCSMPProperties.VPN_NAME));
        }
		properties.setProperty(JCSMPProperties.USERNAME, new String(username, "UTF-8"));
		properties.setProperty(JCSMPProperties.PASSWORD, new String(password, "UTF-8"));
        properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, 255);
        String topicSeparators = ProxyConfig.getInstance().getString(ProxyConfig.SEPARATOR_CONFIG);
        topicSeparatorReplace = topicSeparators.isEmpty() ? "" : "[" + topicSeparators + "]";
        // if (baseServiceProps.containsKey(ProxyConfig.SEPARATOR_CONFIG)) {
        // 	topicSeparatorReplace = '[' + baseServiceProps.getProperty(ProxyConfig.SEPARATOR_CONFIG) + ']';
        // } else {
        // 	topicSeparatorReplace = "";
        // }
        log.debug("Kafka Topic Separators = {}", topicSeparatorReplace);
		log.info("Creating new session to Solace event broker");
		session =  JCSMPFactory.onlyInstance().createSession(properties);
		
		publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
			public void responseReceivedEx(Object correlationKey) {
				final ProxyChannel.ProduceAckState produceAckState = (ProxyChannel.ProduceAckState) correlationKey;
				produceAckState.addToWorkQueue();
	        }

			public void handleErrorEx(Object correlationKey, JCSMPException e, long timestamp) {
				log.info("Got publish exception: " + e);
				final ProxyChannel.ProduceAckState produceAckState = (ProxyChannel.ProduceAckState) correlationKey;
				// in the rare case of not working, we construct a new produceAckState so that 
				// We can indicate that the publish did not work. This is done since produceAckState
				// is immutable and failures should be very rare
				new ProxyChannel.ProduceAckState(produceAckState, false).addToWorkQueue();
            }
	    });    
    }

    public void addChannel(ProxyChannel channel) {
        synchronized (channels) {
            channels.add(channel);
            log.info("Added channel to session, new count: " + channels.size());
        }
    }

    public JCSMPSession getJcsmpSession() {
        return session;
    }

    public void removeChannel(ProxyChannel channel) {
        synchronized (channels) {
            channels.remove(channel);
            log.info("Removed channel from session, new count: " + channels.size());
            if (channels.isEmpty()) {
                ProxyPubSubPlusClient.getInstance().removeSession(this);
                log.info("No more channels for session, closing session");
                close();
            }
        }
    }
    
    // Used to fail all channels that use this API session when we need to fail
    // and we do not have a reference to the particular proxy channel that has
    // an issue. Should never happen.
    // private void failAllChannels() {
    //     synchronized (channels) {
    //         for (ProxyChannel channel : channels) {
    //             new ProxyChannel.Close(channel, "Session to Solace broker going down").addToWorkQueue();
    //         }
    //     }
    // }
    
    public void connect(ProxyChannel.AuthorizationResult authResult) {
        ProxyPubSubPlusClient.getExecutorService().execute(new Runnable() {
            public void run() {
                try {
                    session.connect();
                    authResult.addToWorkQueue();
                } catch (Exception e) {
                    log.warn("Session connection failed: " + e);
                    new ProxyChannel.AuthorizationResult(authResult, false).addToWorkQueue();
                }
            }
        });
    }
    
    private String solaceConvertTopic(String topic) {
    	if (topicSeparatorReplace.isEmpty()) {
        	return topic;  // don't change anything
    	}
		topic = topic.replaceAll(topicSeparatorReplace, "/");  // replace all _ or . with /
		topic = topic.replaceAll("//", "/_/");   // any empty levels replace with a _
		topic = topic.replaceFirst("^/", "_/");  // no leading empty level
		topic = topic.replaceFirst("/$", "/_");  // no trailing empty level
		return topic;
    }

    public void publish(String topic, byte[] payload, byte[] key, ProxyChannel.ProduceAckState produceAckState) {
		try {
			BytesMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
			msg.setCorrelationKey(produceAckState);
			msg.setDeliveryMode(DeliveryMode.PERSISTENT);
			msg.writeAttachment(payload);
			if (key != null) {
			    SDTMap solaceMsgProperties = JCSMPFactory.onlyInstance().createMap();
			    // solaceMsgProperties.putString("kafka_key", Base64.getEncoder().encodeToString(key));  // old
			    solaceMsgProperties.putString(MessageUserPropertyConstants.QUEUE_PARTITION_KEY, Base64.getEncoder().encodeToString(key));  // old
			    msg.setProperties(solaceMsgProperties);
			}
			final Topic solaceTopic = JCSMPFactory.onlyInstance().createTopic(solaceConvertTopic(topic));
			publisher.send(msg, solaceTopic);
		} catch (JCSMPException e) {
			log.info("Publish did not work: " + e);
		    new ProxyChannel.ProduceAckState(produceAckState, false).addToWorkQueue();
		}
    }
    
    public void close() {
        session.closeSession();
        publisher.close();
    }

}