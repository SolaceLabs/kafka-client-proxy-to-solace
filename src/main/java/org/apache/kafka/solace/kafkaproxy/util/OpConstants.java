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
package org.apache.kafka.solace.kafkaproxy.util;

public class OpConstants {

    public static final int         LEADER_ID_TOPIC_PARTITION = 0;

    public static final long        TIMESTAMP_START_LATEST = -1L;

    public static final int         UNKNOWN_LEADER_ID = -1;

    public static final long        LOG_START_OFFSET = 0L,
                                    UNKNOWN_OFFSET = -1L;

    public static final int         UNKNOWN_LEADER_EPOCH = -1,
                                    LEADER_EPOCH = 1;

    public static final int         UNKNOWN_COMMITTED_LEADER_EPOCH = -1;

    public static final int         UNKNOWN_PARTITION_LEADER_EPOCH = -1;

    public static final long        DEFAULT_MAX_UNCOMMITTED_MESSAGES_PER_FLOW = 2_500L;

}
