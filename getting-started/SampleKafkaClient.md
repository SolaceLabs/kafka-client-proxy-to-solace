# Sample Kafka Client Demo

This guide demonstrates how to build and execute the embedded demo producer and consumer clients to test the Kafka Proxy for Solace PubSub+.

## Prerequisites

- **Java 17+** - Required for building and running the demo clients
- **Maven** - For building the project
- **Running Proxy** - Kafka Proxy must be running and accessible
- **Solace Broker Access** - Valid credentials for your Solace Message VPN

## Building the Demo Clients

The demo clients are included in the main project. Build the entire project to compile the demo clients:

```bash
# From the project root directory
mvn clean package

# This creates the JAR with all demo clients included
ls target/kafka-wireline-proxy-*-jar-with-dependencies.jar
```

## Configuration Files

The sample configuration files are located in the `getting-started/` directory:

- **`demo-producer.properties`** - Producer configuration with SASL authentication
- **`demo-consumer.properties`** - Consumer configuration with SASL authentication

### Update Configuration

Edit the configuration files to match your environment:

```bash
# Copy sample files to working directory
cp getting-started/demo-producer.properties .
cp getting-started/demo-consumer.properties .

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
java -jar target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     getting-started/proxy-example.properties
```

The proxy should display:
```
INFO: Kafka Proxy started successfully
INFO: Listening on PLAINTEXT://localhost:9092
INFO: Listening on SASL_SSL://localhost:9094
INFO: Health check server started on port 8080
```

### Start Demo Consumer

Open a new terminal and start the demo consumer:

```bash
# Run the embedded demo consumer
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     test-topic \
     test-group
```

**Command Arguments:**
- `demo-consumer.properties` - Consumer configuration file
- `test-topic` - Kafka topic to subscribe to
- `test-group` - Consumer group ID

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
# Run the embedded demo producer
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     test-topic \
     10
```

**Command Arguments:**
- `demo-producer.properties` - Producer configuration file
- `test-topic` - Kafka topic to publish to
- `10` - Number of messages to send

**Expected Output:**
```
Demo Producer starting...
Sending 10 messages to topic: test-topic
Message 1 sent: Hello from Demo Producer - Message 1
Message 2 sent: Hello from Demo Producer - Message 2
...
Message 10 sent: Hello from Demo Producer - Message 10
Producer completed successfully
```

### Verify Message Delivery

Check the consumer terminal - you should see the messages appear:

```bash
Received message: Hello from Demo Producer - Message 1
Received message: Hello from Demo Producer - Message 2
...
Received message: Hello from Demo Producer - Message 10
```

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
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     ssl-test-topic \
     ssl-test-group

# Producer with SSL
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     ssl-test-topic \
     5
```

## Consumer Groups

### Test Multiple Consumers

Start multiple consumers in the same group:

```bash
# Terminal 1 - Consumer 1
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     test-topic \
     test-group

# Terminal 2 - Consumer 2
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     test-topic \
     test-group
```

Messages will be distributed between the consumers in the same group.

### Test Different Consumer Groups

```bash
# Consumer Group A
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     test-topic \
     group-a

# Consumer Group B
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     demo-consumer.properties \
     test-topic \
     group-b
```

Both consumers will receive all messages since they're in different groups.

## Advanced Testing

### Topic with Hierarchical Naming

Test topic name conversion (if `proxy.separators` is configured):

```bash
# This topic name will be converted: my_test.topic -> my/test/topic
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     my_test.topic \
     5
```

### High-Volume Testing

Generate more messages for throughput testing:

```bash
# Send 1000 messages
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     perf-test-topic \
     1000
```

### Batch Testing

Run multiple producers simultaneously:

```bash
# Terminal 1
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     batch-topic \
     50 &

# Terminal 2
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     batch-topic \
     50 &
```

## Demo Client Options

### DemoProducer Usage

```bash
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     <config-file> \
     <topic-name> \
     <message-count> \
     [key-prefix]
```

**Parameters:**
- `config-file` - Producer properties file
- `topic-name` - Target Kafka topic
- `message-count` - Number of messages to send
- `key-prefix` - Optional message key prefix

### DemoConsumer Usage

```bash
java -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoConsumer \
     <config-file> \
     <topic-name> \
     <group-id> \
     [poll-duration-ms]
```

**Parameters:**
- `config-file` - Consumer properties file
- `topic-name` - Kafka topic to subscribe to
- `group-id` - Consumer group identifier
- `poll-duration-ms` - Optional polling timeout (default: 1000ms)

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

### Demo Client Debugging

Enable debug logging by adding JVM arguments:

```bash
java -Dlogback.configurationFile=logback.xml \
     -Dcom.solace.kafka.kafkaproxy.demo.level=DEBUG \
     -cp target/kafka-wireline-proxy-*-jar-with-dependencies.jar \
     com.solace.kafka.kafkaproxy.demo.DemoProducer \
     demo-producer.properties \
     test-topic \
     10
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