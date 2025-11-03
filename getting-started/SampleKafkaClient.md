# Sample Kafka Client Demo

This guide demonstrates how to build and execute the embedded demo producer and consumer clients to test the Kafka Proxy for Solace.

## Prerequisites

- **Java 17+** - Required for building and running the demo clients
- **Maven** - For building the project
- **Running Proxy** - Kafka Proxy must be running and accessible
- **Solace Broker Access** - Valid credentials for your Solace Message VPN

## Building the Demo Clients

The demo clients are in separate sub-projects with their own directories. Build the entire project to compile both the proxy and demo clients:

```bash
# From the project root directory
# Build Proxy
mvn clean package

# Sample Kafka producer
mvn clean package -f demo-producer/pom.xml

# Sample Kafka consumer
mvn clean package -f demo-consumer/pom.xml

# This creates separate JAR files:
# - Main proxy JAR
ls target/kafka-wireline-proxy-*.jar
# - Demo producer JAR  
ls demo-producer/target/kafka-demo-producer-*.jar
# - Demo consumer JAR
ls demo-consumer/target/kafka-demo-consumer-*.jar
```

> **Note**: The version number in the JAR file of ***Kafka Demo Clients*** name reflects the version of the Kafka client library that was used to compile it. For example, `kafka-demo-producer-3.9.1.jar` indicates it was compiled with Kafka client version 3.9.1.
>> You can change the version of `kafka-clients` module in the `pom.xml` file to create different versions of the clients for testing.

## Configuration Files

The sample configuration files are located in the `getting-started/sample-configs/` directory:

- **`demo-producer.properties`** - Producer configuration with SASL authentication
- **`demo-consumer.properties`** - Consumer configuration with SASL authentication

### Update Configuration

Edit the configuration files to match your environment:

```bash
# Copy sample files to working directory
cp getting-started/sample-configs/demo-producer.properties .
cp getting-started/sample-configs/demo-consumer.properties .

# Edit producer config
vi demo-producer.properties
```

Update the Solace broker credentials:
```properties
# Proxy connection endpoint
bootstrap.servers=localhost:9092

# Solace broker credentials
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
  username="YOUR-SOLACE-USERNAME" \
  password="YOUR-SOLACE-PASSWORD";
```

For SSL connections, update `bootstrap.servers` and `security.protocol`:
```properties
bootstrap.servers=localhost:9094
security.protocol=SASL_SSL
```

## Running the Demo

### Start the Kafka Proxy

First, ensure the Kafka Proxy is running:

```bash
# From the project root directory
java -jar target/kafka-wireline-proxy-*.jar \
     getting-started/sample-configs/proxy-example.properties
```

The proxy should display:
```log
15:50:28.636 [main] INFO  HealthCheckServer - Health check server started on port 8080
15:50:28.638 [main] INFO  ProxyMain - Health check server started on port 8080
15:50:28.652 [main] INFO  ProxyReactor - Listening for incoming connections on PLAINTEXT localhost/127.0.0.1:9092
15:50:28.653 [main] INFO  ProxyReactor - Listening for incoming connections on SASL_SSL localhost/127.0.0.1:9094
15:50:28.791 [main] INFO  ProxyReactor - Initializing request handler thread pool with 32 threads.
```

> **Note:** HealthCheckServer logs will be included if property `proxy.healthcheckserver.create=true`.

### Start Demo Consumer

Open a new terminal and start the demo consumer:

```bash
# Run the demo consumer from its own JAR
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g test-group \
     -t test-topic \
     -l consumer-client-01 \
     -m 100
```

**Command Arguments:**
- `-c demo-consumer.properties` - Consumer configuration file
- `-g test-group` - Consumer group ID
- `-t test-topic` - Kafka topic to subscribe to
- `-l consumer-client-01` - Client ID for the consumer (optional)
- `-p 500` - Poll timeout in milliseconds (optional, default: 500)
- `-m 100` - Maximum records per poll (optional, default: 500)

**Expected Output:**
```
Demo Consumer starting with client ID: consumer-client-01
Subscribed to topic: test-topic, Consumer group: test-group
<-- key1       - The old house on the hill seemed to whisper secr [000001] P[00] N[1] C[cons-01]
<-- key2       - A gentle breeze rustled through the tall grass c [000002] P[01] N[1] C[cons-01]
<-- key3       - Ancient forest stood as silent guardian watching  [000003] P[02] N[1] C[cons-01]
```

### Start Demo Producer

Open another terminal and start the demo producer:

```bash
# Run the demo producer from its own JAR
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     -c demo-producer.properties \
     -t PRODUCER_TOPIC:test-topic \
     -i getting-started/test-data/publish-data-kv-10-fixed.txt \
     -n 10 \
     -d 1000 \
     -l producer-client-01
```

**Command Arguments:**
- `-c demo-producer.properties` - Producer configuration file
- `-t PRODUCER_TOPIC:test-topic` - Kafka topic to publish to (note the PRODUCER_TOPIC prefix)
- `-i getting-started/test-data/publish-data-kv-10-fixed.txt` - Test data file containing key-value pairs
- `-n 10` - Number of messages to send  
- `-d 1000` - Delay in milliseconds between messages (optional, default: 0)
- `-l producer-client-01` - Client ID for the producer (optional)

**Expected Output:**
```log
INFO  - Starting to send messages to topic 'PRODUCER_TOPIC:test-topic'. Press Ctrl+C to exit.
INFO  - Using client ID: producer-client-01
--> key1       - The old house on the hill seemed to whisper secr [000001] N[1] C[prod-01 ]
--> key2       - A gentle breeze rustled through the tall grass c [000002] N[1] C[prod-01 ]
--> key3       - Ancient forest stood as silent guardian watching  [000003] N[1] C[prod-01 ]
INFO  - Target number of records (10) has been sent.
```

### Verify Message Delivery

Check the consumer terminal - you should see the messages appear:

```bash
Received message: Hello from Demo Producer - Message 1
Received message: Hello from Demo Producer - Message 2
...
Received message: Hello from Demo Producer - Message 10
```

> **Note:** `Control-C` to close out of the consumer; Producer will terminate when the number of messages specified has been sent.

## SSL/TLS Testing

### Update Configuration for SSL

Modify the configuration files for SSL testing:

```properties
# demo-producer.properties and demo-consumer.properties
bootstrap.servers=localhost:9094
security.protocol=SASL_SSL

# Add SSL truststore settings if using self-signed certificates
ssl.truststore.location=/path/to/truststore/trusted.jks
ssl.truststore.password=trusted
ssl.truststore.type=JKS
ssl.endpoint.identification.algorithm=
```

### Run SSL Demo

```bash
# Consumer with SSL
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g ssl-test-group \
     -t ssl-test-topic

# Producer with SSL
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:ssl-test-topic \
     --num-records 5
```

### Test Multiple Consumers

Start multiple consumers in the same group:

```bash
# Terminal 1 - Consumer 1
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g test-group \
     -t test-topic

# Terminal 2 - Consumer 2
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g test-group \
     -t test-topic
```

Messages will be distributed between the consumers in the same group.

### Test Different Consumer Groups

```bash
# Consumer Group A
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g group-a \
     -t test-topic

# Consumer Group B
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c demo-consumer.properties \
     -g group-b \
     -t test-topic
```

Both consumers will receive all messages since they're in different groups.

## Advanced Testing

### Topic with Hierarchical Naming

Test topic name conversion (if `proxy.separators` is configured):

```bash
# This topic name will be converted: my_test.topic -> my/test/topic
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:my_test.topic \
     --num-records 5
```

### High-Volume Testing

Generate more messages for throughput testing:

```bash
# Send 1000 messages
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:perf-test-topic \
     --num-records 1000
```

### Testing with Input Files

Use custom test data from files:

```bash
# Producer with custom input file
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:file-test-topic \
     --input-file /path/to/test-data/messages.txt \
     --num-records 50
```

### Testing with Delays

Add delays between messages for controlled testing:

```bash
# Producer with 100ms delay between messages
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:delayed-topic \
     --num-records 10 \
     -d 100
```

### Batch Testing

Run multiple producers simultaneously:

```bash
# Terminal 1
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:batch-topic \
     --num-records 50 &

# Terminal 2
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:batch-topic \
     --num-records 50 &
```

## Demo Client Options

### DemoProducer Usage

```bash
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     -c <config-file> \
     -t <topic-name> \
     -i <input-file> \
     -n <message-count> \
     [-d <delay-millis>] \
     [-l <client-id>]
```

**Parameters:**
- `-c <config-file>` - Producer properties file (required)
- `-t <topic-name>` - Target Kafka topic (required, use PRODUCER_TOPIC: prefix)
- `-i <input-file>` - Input file containing key-value pairs (required)
- `-n <message-count>` - Number of messages to send (required)
- `-d <delay-millis>` - Delay between messages in milliseconds (optional, default: 0)
- `-l <client-id>` - Kafka client ID for the producer (optional)

### DemoConsumer Usage

```bash
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c <config-file> \
     -g <group-id> \
     -t <topic-name> \
     [-p <poll-time-ms>] \
     [-l <client-id>] \
     [-m <max-poll-records>]
```

**Parameters:**
- `-c <config-file>` - Consumer properties file (required)
- `-g <group-id>` - Consumer group identifier (required)
- `-t <topic-name>` - Kafka topic to subscribe to (required)
- `-p <poll-time-ms>` - Poll timeout in milliseconds (optional, default: 500)
- `-l <client-id>` - Kafka client ID for the consumer (optional)
- `-m <max-poll-records>` - Maximum records returned per poll (optional, default: 500)

## Understanding Output Format

### Producer Output Format
```
--> <key>      - <value>                                      [<count>] N[<node>] C[<client>]
```

- `-->` - Indicates outgoing message from producer
- `<key>` - Message key (padded to 10 characters)
- `<value>` - Message value (truncated to 50 characters)
- `[<count>]` - Message sequence number (6 digits)
- `N[<node>]` - Kafka broker node ID
- `C[<client>]` - Client ID (truncated to 8 characters)

### Consumer Output Format
```
<-- <key>      - <value>                                      [<offset>] P[<part>] N[<node>] C[<client>]
```

- `<--` - Indicates incoming message to consumer
- `<key>` - Message key (padded to 10 characters)
- `<value>` - Message value (truncated to 50 characters)  
- `[<offset>]` - Message offset in partition
- `P[<part>]` - Partition number (2 digits)
- `N[<node>]` - Kafka broker node ID
- `C[<client>]` - Consumer client ID (truncated to 8 characters)