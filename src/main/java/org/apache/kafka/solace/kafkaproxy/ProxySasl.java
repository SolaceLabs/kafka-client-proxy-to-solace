package org.apache.kafka.solace.kafkaproxy;

/*
 * Copyright 2021 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
 
import java.util.Arrays;

import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.errors.InvalidRequestException;


public class ProxySasl {

    private enum SaslState {
        INITIAL_REQUEST,               // May be GSSAPI token, SaslHandshake or ApiVersions for authentication
        HANDSHAKE_OR_VERSIONS_REQUEST, // May be SaslHandshake or ApiVersions
        HANDSHAKE_REQUEST,             // After an ApiVersions request, next request must be SaslHandshake
        AUTHENTICATE,                  // Authentication tokens (SaslHandshake v1 and above indicate SaslAuthenticate headers)
        COMPLETE,                      // Authentication completed successfully
    }
    
    private SaslState saslState;
    private boolean   complete;
    
    public ProxySasl() {
        saslState = SaslState.INITIAL_REQUEST;
        complete = false;
    }
    
    public void setComplete(boolean comp) { 
        complete = comp; 
        if (complete) {
            saslState = SaslState.COMPLETE;
        }
    }
    
    public boolean isComplete() { return complete; }
    
    public boolean authenticating() { return (saslState == SaslState.AUTHENTICATE); }
    
    public void adjustState(ApiKeys apiKey) {
        switch (saslState) {
            case INITIAL_REQUEST:
            case HANDSHAKE_OR_VERSIONS_REQUEST:
               if ((apiKey == ApiKeys.API_VERSIONS) || (apiKey == ApiKeys.SASL_HANDSHAKE)) {
                    if (apiKey == ApiKeys.API_VERSIONS) {
                        saslState = SaslState.HANDSHAKE_REQUEST;
                    } else {
                        saslState = SaslState.AUTHENTICATE;
                    }
                } else {
                    throw new InvalidRequestException("Invalid API key for initial request: " + apiKey);
                }
                break;
            case HANDSHAKE_REQUEST:
                if (apiKey == ApiKeys.SASL_HANDSHAKE) {
                    saslState = SaslState.AUTHENTICATE;
                } else {
                    throw new InvalidRequestException("Invalid API key " + apiKey + ", expecting handshake request");
                }
                break;
            case AUTHENTICATE:
                if (apiKey != ApiKeys.SASL_AUTHENTICATE) {
                    throw new InvalidRequestException("Invalid API key " + apiKey + ", expecting Sasl Authenticate");
                }
                break;
            case COMPLETE:
                break;
            default:
                throw new InvalidRequestException("Invalid Sasl state of: " + saslState);
        }    
    }
    
    // Some code taken from org.apache.kafka.common.security.plain.internals.PlainSaslServer.java
    public ProxyPubSubPlusSession authenticate(
               ProxyChannel.AuthorizationResult authResult, byte[] responseBytes) 
               throws SaslAuthenticationException {
        /*
         * Message format (from https://tools.ietf.org/html/rfc4616):
         *
         * message   = [authzid] UTF8NUL authcid UTF8NUL passwd
         * authcid   = 1*SAFE ; MUST accept up to 255 octets
         * authzid   = 1*SAFE ; MUST accept up to 255 octets
         * passwd    = 1*SAFE ; MUST accept up to 255 octets
         * UTF8NUL   = %x00 ; UTF-8 encoded NUL character
         *
         * SAFE      = UTF1 / UTF2 / UTF3 / UTF4
         *                ;; any UTF-8 encoded Unicode character except NUL
         */

        //bytesToHex(responseBytes, 0, responseBytes.length);
        int tokenCount = 0;
        byte[] username = null;
        byte[] password = null;
        int startingIndex = 0;
        for (int i = 0; i < 4; i++) {
            int tokenLength = getNextToken(responseBytes, startingIndex);
            if (tokenLength >= 0) {
                tokenCount++;
                if (tokenCount == 2) {
                    username = new byte[tokenLength];
                    System.arraycopy(responseBytes, startingIndex, username, 0, tokenLength);
                } else if (tokenCount == 3 ) {
                    password = new byte[tokenLength];
                    System.arraycopy(responseBytes, startingIndex, password, 0, tokenLength);
                }
                startingIndex += tokenLength + 1;
            } else break;
        }
        if (tokenCount != 3) {
           throw new SaslAuthenticationException("Invalid SASL/PLAIN response: expected 3 tokens, got " +
                                                 tokenCount);
        }
        if ((username == null) || (username.length == 0)) {
            throw new SaslAuthenticationException("Authentication failed: username not specified");
        }
        if ((password == null) || (password.length == 0)) {
            throw new SaslAuthenticationException("Authentication failed: password not specified");
        }    
        ProxyPubSubPlusSession session =  
            ProxyPubSubPlusClient.getInstance().connect(
                   authResult, username, password);
        // wipe out username and password
        Arrays.fill(username, (byte)0);
        Arrays.fill(password, (byte)0);
        Arrays.fill(responseBytes, (byte)0);
        return session;
    }
    
    // returns length of next token,  -1 if no token
    private int getNextToken(byte[] bytes, int startingIndex) {
        int responseLength = bytes.length;
        int endingIndex;
        if (startingIndex < responseLength) {
            for (endingIndex = startingIndex; endingIndex < responseLength; endingIndex++) {
                if (bytes[endingIndex] == 0) {
                    return endingIndex - startingIndex;
                }
            }
            return responseLength - startingIndex;
        }
        return -1;
    }

}