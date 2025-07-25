# Sample Kafka Client Demo

This guide demonstrates how to build and execute the embedded demo producer and consumer clients to test the Kafka Proxy for Solace PubSub+.

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
     -t test-topic
```

**Command Arguments:**
- `-c demo-consumer.properties` - Consumer configuration file
- `-g test-group` - Consumer group ID
- `-t test-topic` - Kafka topic to subscribe to

**Expected Output:**
```
Demo Consumer starting...
Subscribed to topic: test-topic
Consumer group: test-group
Waiting for messages...
```

### Start Demo Producer

Open another terminal and start the demo producer:

```bash
# Run the demo producer from its own JAR
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:test-topic \
     --input-file getting-started/test-data/publish-data-kv-10-fixed.txt \
     --num-records 10 \
     --delay 5
```

**Command Arguments:**
- `--config demo-producer.properties` - Producer configuration file
- `--topic PRODUCER_TOPIC:test-topic` - Kafka topic to publish to (note the PRODUCER_TOPIC prefix)
- `--input-file getting-started/test-data/publish-data-kv-10-fixed.txt` - Test Data File containing records with 10 unique Keys
- `--num-records 10` - Number of messages to send
- `--delay 5` - Delay in milliseconds between requests to publish messages (can be 0)

**Expected Output:**
```log
...
INFO  - Starting to send messages to topic 'PRODUCER_TOPIC:ORDER_CHANGES'. Press Ctrl+C to exit.
...
INFO  - SENT 0000000000 : The old house on the hill seemed to whisper secrets to the passing travelers
INFO  - SENT 1111111111 : A gentle breeze rustled through the tall grass creating a soothing melody
INFO  - SENT 2222222222 : Ancient forest stood as a silent guardian watching over the sleeping valley
...
INFO  - SENT 8888888888 : Train rumbled along the tracks carrying passengers to their unknown destinations
INFO  - SENT 9999999999 : The chef prepared a delicious meal the aroma filling the entire restaurant
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
     --config <config-file> \
     --topic PRODUCER_TOPIC:<topic-name> \
     --num-records <message-count> \
     [--input-file <input-file>] \
     [-d <delay-millis>]
```

**Parameters:**
- `-c | --config      <config-file>` - Producer properties file
- `-t | --topic       PRODUCER_TOPIC:<topic-name>` - Target Kafka topic (PRODUCER_TOPIC prefix required)
- `-n | --num-records <message-count>` - Number of messages to send
- `-i | --input-file  <input-file>` - Optional: Custom input file for message content
- `-d | --delay       <delay-millis>` - Optional: Delay between messages in milliseconds

### DemoConsumer Usage

```bash
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c <config-file> \
     -g <group-id> \
     -t <topic-name>
```

**Parameters:**
- `-c <config-file>` - Consumer properties file
- `-g <group-id>` - Consumer group identifier
- `-t <topic-name>` - Kafka topic to subscribe to

### Demo Client Debugging

Enable debug logging by adding JVM arguments:

```bash
java -Dlog4j.configurationFile=log4j2.xml \
     -Dlog4j2.logger.com.solace.kafka.kafkaproxy.demo.level=DEBUG \
     -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config demo-producer.properties \
     --topic PRODUCER_TOPIC:test-topic \
     --num-records 10
```

## Troubleshooting

### Connection Issues

```bash
# Test proxy health
curl http://localhost:8080/health

# Check proxy logs for connection errors
tail -f proxy.log
```

### Authentication Failures

1. **Verify Solace credentials** in the JAAS configuration
2. **Check Message VPN permissions** on the Solace broker
3. **Validate proxy configuration** for Solace connection

### SSL Certificate Issues

```bash
# Test SSL connection manually
openssl s_client -connect localhost:9094 -servername localhost

# Verify certificate in keystore
keytool -list -v -keystore /path/to/keystore.pkcs12 -storetype PKCS12
```

## Monitoring

### Check Message Flow

1. **Proxy Logs** - Monitor for connection and message processing
2. **Solace Broker** - Check queue statistics and message rates
3. **Demo Client Output** - Verify send/receive confirmations

### Health Endpoints

```bash
# Proxy health status
curl http://localhost:8080/health

# Readiness check
curl http://localhost:8080/ready
```

## Next Steps

1. **Custom Applications** - Use the demo clients as reference for your own Kafka applications
2. **Production Configuration** - Review SSL certificates and security settings
3. **Performance Tuning** - Adjust thread pools and batch sizes based on demo results
4. **Kubernetes Deployment** - Use the AWS EKS deployment guide for production

For production deployment, see the [AWS EKS Deployment Guide](../k8s/aws-eks-deploy/aws-eks-instructions.md).