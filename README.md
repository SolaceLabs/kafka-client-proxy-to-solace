# Kafka Proxy for Solace

A high-performance proxy that allows Kafka clients to publish and subscribe to a Solace Event Broker without any changes to the Kafka client application.

## Description

This project enables Kafka client applications to seamlessly produce and consume messages from Solace via the proxy. The proxy speaks the native Kafka wireline protocol to Kafka client applications and the Solace SMF protocol to the Event Brokers.

**Key Features:**
- **Protocol Translation**: Transparent conversion between Kafka wireline protocol and Solace SMF
- **Producer Support**: Kafka topics can be published unmodified or converted to hierarchical Solace topics
- **Consumer Support**: Full consumer group management with mapping to Solace queues and topic subscriptions
- **Security**: Comprehensive SSL/TLS and mTLS support for both Kafka clients and Solace connections
- **Kubernetes Ready**: Production-ready deployment configurations for AWS EKS

For producers, Kafka topics can be published to the Solace Event Mesh unmodified, or converted to hierarchical Solace topics by splitting on specified characters.

For consumers, the proxy manages consumer groups and topic subscriptions, mapping them to Solace queues and topic subscriptions with configurable queue naming strategies.

## Getting Started

### Dependencies

- **Java 17+** - Required runtime
- **kafka-clients** - Kafka protocol implementation
- **sol-jcsmp** - Solace messaging API
- **slf4j-api** - Logging API
- **log4j2** - Logging implementation

### Building

#### Using Maven

Use Maven to build and package the application:

```bash
# Clone the repository
git clone <repository-url>
cd kafka-client-proxy-to-solace

# Build the project and its dependencies
mvn clean package

# The built JAR will be available at:
target/kafka-wireline-proxy-*.jar
```

#### Using Gradle

Alternatively, use Gradle to build and package the application:

```bash
# Clone the repository
git clone <repository-url>
cd kafka-client-proxy-to-solace

# Build the project and its dependencies
./gradlew clean build

# The built JAR will be available at:
build/libs/kafka-wireline-proxy-*.jar

# Run directly with Gradle (development mode)
./gradlew run-proxy --args="getting-started/sample-configs/proxy-example.properties"
```

### Running as JAR

#### Using Maven-built JAR

```bash
# Run the proxy with a properties file
java -jar target/kafka-wireline-proxy-*.jar /path/to/proxy.properties

# Example with JVM tuning options
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -jar target/kafka-wireline-proxy-*.jar \
     /path/to/proxy.properties

# With custom logging configuration
java -Dlog4j.configurationFile=log4j2.xml \
     -jar target/kafka-wireline-proxy-*.jar \
     /path/to/proxy.properties
```

#### Using Gradle-built JAR

```bash
# Run the proxy with a properties file
java -jar build/libs/kafka-wireline-proxy-*.jar /path/to/proxy.properties

# Run directly with Gradle (development mode)
./gradlew run-proxy --args="getting-started/sample-configs/proxy-example.properties"

# Example with JVM tuning options
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -jar build/libs/kafka-wireline-proxy-*.jar \
     /path/to/proxy.properties

# With custom logging configuration
java -Dlog4j.configurationFile=log4j2.xml \
     -jar build/libs/kafka-wireline-proxy-*.jar \
     /path/to/proxy.properties
```

#### Building Demo Clients

The project includes separate demo client applications in their own sub-projects:

```bash
# Build all projects (proxy and demo clients)
mvn clean package

# This creates:
# - Main proxy JAR
ls target/kafka-wireline-proxy-*.jar
# - Demo producer JAR  
ls demo-producer/target/kafka-demo-producer-*.jar
# - Demo consumer JAR
ls demo-consumer/target/kafka-demo-consumer-*.jar
```

#### Running Demo Clients with Gradle

```bash
# Build all projects including demo clients
./gradlew clean build

# Run demo producer (from separate JAR)
java -jar demo-producer/target/kafka-demo-producer-*.jar \
     --config getting-started/sample-configs/demo-producer.properties \
     --topic PRODUCER_TOPIC:test-topic \
     --num-records 10

# Run demo consumer (from separate JAR)
java -jar demo-consumer/target/kafka-demo-consumer-*.jar \
     -c getting-started/sample-configs/demo-consumer.properties \
     -g test-group \
     -t test-topic
```

> **Note**: The version number in the demo client JAR files reflects the Kafka client library version used for compilation (e.g., `kafka-demo-producer-3.9.1.jar` was compiled with Kafka client version 3.9.1).

### Docker Container

#### Using Pre-built Image

A pre-built Docker image is available from the Solace Labs container registry:

```bash
# Pull the latest image
docker pull ghcr.io/solacelabs/kafka-wireline-proxy:latest

# Run container with pre-built image
docker run -d \
  --name kafka-proxy \
  -p 9092:9092 \
  -p 9094:9094 \
  -p 8080:8080 \
  -v /path/to/proxy.properties:/app/proxy.properties \
  -v /path/to/certs:/app/certs \
  ghcr.io/solacelabs/kafka-wireline-proxy:latest
```

#### Building Custom Image

```bash
# Build Docker image locally
docker build -t kafka-proxy:latest .

# Run container with custom image
docker run -d \
  -p 9092:9092 \
  -p 9094:9094 \
  -v /path/to/proxy.properties:/app/proxy.properties \
  -v /path/to/certs:/app/certs \
  kafka-proxy:latest
```

## Kubernetes Deployment

The Kafka Proxy is designed for production deployment on Kubernetes with full support for:

- **StatefulSet Deployment**: Stable network identities and persistent storage
- **Load Balancer Integration**: AWS Network Load Balancer support with health checks
- **Pod Anti-Affinity**: Distributed scheduling across nodes for high availability
- **Security Groups**: Fine-grained network access control
- **SSL/TLS Termination**: End-to-end encryption support

### AWS EKS Deployment

For complete AWS EKS deployment instructions, see: **[AWS EKS Deployment Guide](k8s/aws-eks-deploy/aws-eks-instructions.md)**

The deployment includes:
- Instance-specific and bootstrap load balancers
- Security group configurations for SSL-only external access
- Automated certificate management
- Health check endpoints
- Horizontal scaling support

```bash
# Quick deployment overview
cd k8s/aws-eks-deploy
./create-aws-security-groups.sh
kubectl apply -f instance-lb.yaml
kubectl apply -f bootstrap-lb.yaml
kubectl apply -f proxy-config-map.yaml
kubectl apply -f proxy-sts.yaml
```

## Configuration

The Kafka Proxy takes one command line argument: a properties file to configure all aspects of the proxy operation.

### Kafka Client Listener Configuration

#### Basic Listener Settings

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `listeners` | Comma-separated list of `[protocol://]host:[port]` tuples for the proxy to listen on for Kafka client connections. Supported protocols: `PLAINTEXT`, `SASL_SSL`, `SSL`. Example: `PLAINTEXT://0.0.0.0:9092,SASL_SSL://0.0.0.0:9094` | | ✅ |
| `advertised.listeners` | Comma-separated list of `host:port` tuples to advertise to clients. Useful when proxy runs in containers or behind NAT. Must match the number of entries in `listeners`. Supports environment variable resolution: `${env:KAFKA_ADVERTISED_LISTENERS}` | Same as `listeners` | |

#### SSL/TLS Configuration for Kafka Clients

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `ssl.keystore.location` | Path to the keystore file containing the server's SSL certificate (PKCS12 or JKS format) | | ✅ (for SSL) |
| `ssl.keystore.password` | Password for the keystore file. Supports environment variable resolution: `${env:KAFKA_KEYSTORE_PASSWORD}` | | ✅ (for SSL) |
| `ssl.keystore.type` | Format of the keystore file. Valid values: `JKS`, `PKCS12` | `JKS` | |
| `ssl.enabled.protocols` | Comma-separated list of TLS protocols to enable. Example: `TLSv1.2` or `TLSv1.2,TLSv1.3` | `TLSv1.2` | |
| `ssl.cipher.suites` | Comma-separated list of SSL cipher suites to enable | JVM defaults | |
| `ssl.protocol` | SSL protocol to use. Valid values: `TLS`, `TLSv1.1`, `TLSv1.2`, `TLSv1.3` | `TLS` | |

**Recommended SSL Configuration:**
```properties
ssl.enabled.protocols=TLSv1.2,TLSv1.3
```

#### mTLS (Mutual TLS) Configuration

These properties enable client certificate verification for enhanced security:

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `ssl.client.auth` | Client authentication mode. Values: `required` (mandatory mTLS), `requested` (optional mTLS), `none` (no client auth) | `none` | |
| `ssl.truststore.location` | Path to truststore containing trusted client certificates. Required when `ssl.client.auth` is `required` | | ✅ (for mTLS) |
| `ssl.truststore.password` | Password for the truststore file | | ✅ (for mTLS) |
| `ssl.truststore.type` | Format of the truststore file. Valid values: `JKS`, `PKCS12` | `JKS` | |

### Solace Event Broker Connection Settings

All Solace connection properties use the `solace.` prefix to prevent conflicts with Kafka properties.

#### Basic Solace Connection

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `solace.host` | Solace broker hostname/IP with port for SMF connections. Examples: `tcps://broker.solace.cloud:55443`, `tcp://localhost:55555` | | ✅ |
| `solace.vpn_name` | Message VPN name on the Solace broker | | ✅ |
| `solace.username` | Username for Solace authentication (can be overridden by Kafka SASL) | | |
| `solace.password` | Password for Solace authentication (can be overridden by Kafka SASL) | | |
| `solace.connect_retries` | Number of connection retry attempts | `3` | |
| `solace.reconnect_retries` | Number of reconnection attempts | `-1` (unlimited) | |

#### Solace SSL/TLS Configuration

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `solace.ssl.enabled.protocols` | TLS protocols for Solace connections. Example: `TLSv1.2,TLSv1.3` | `TLSv1.2` | |
| `solace.ssl.truststore.location` | Path to truststore for Solace broker certificates. Required for `tcps://` connections with self-signed certificates | | (conditional) |
| `solace.ssl.truststore.password` | Password for the Solace truststore | | (conditional) |
| `solace.ssl.truststore.type` | Truststore format. Valid values: `JKS`, `PKCS12` | `JKS` | |
| `solace.ssl.validate_certificate` | Whether to validate Solace broker certificates | `true` | |
| `solace.ssl.validate_certificate_date` | Whether to validate certificate dates | `true` | |

#### Solace mTLS Configuration

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `solace.ssl.keystore.location` | Path to keystore for client certificates when connecting to Solace broker | | (for mTLS) |
| `solace.ssl.keystore.password` | Password for the Solace client keystore | | (for mTLS) |
| `solace.ssl.keystore.type` | Client keystore format. Valid values: `JKS`, `PKCS12` | `JKS` | |
| `solace.ssl.private_key_alias` | Alias for the private key in the keystore | | (for mTLS) |
| `solace.ssl.private_key_password` | Password for the private key | | (for mTLS) |

### Proxy Operational Configuration

#### Core Proxy Settings

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `proxy.separators` | Characters to replace with `/` in Kafka topic names to create hierarchical Solace topics. Example: `._` converts `my_kafka.topic` → `my/kafka/topic` | `""` (no conversion) | |
| `message.max.bytes` | Maximum size of a single message that Kafka clients can produce (bytes) | `1048576` (1MB) | |
| `proxy.request.handler.threads` | Worker threads for blocking Kafka consumer requests. Recommended: `[Total expected consumers] × 1.5-2` | `32` | |
| `proxy.max.uncommitted.messages` | Maximum uncommitted messages per Kafka consumer before flow control. Higher values improve performance but risk redelivery | `1000` | |

#### Consumer Scaling Configuration

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `proxy.partitions.per.topic` | Virtual partitions advertised per Kafka topic. Recommended: `[Max consumers per topic] × 2`. See detailed notes below | `100` | |
| `proxy.queuename.qualifier` | Prefix for Solace queue names. Example: With qualifier `KAFKA-PROXY`, topic `ORDERS`, group `GROUP1` → queue `KAFKA-PROXY/ORDERS/GROUP1` | `""` | |
| `proxy.queuename.is.topicname` | Use Kafka topic name as Solace queue name, ignoring group ID and qualifier. Values: `true`, `false` | `false` | |
| `proxy.fetch.compression.type` | Compression for fetch responses. Values: `none`, `gzip`, `snappy`, `lz4`, `zstd` | `none` | |

#### Kafka Consumer Defaults

These properties set defaults when Kafka clients don't specify values:

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `fetch.max.wait.ms` | Maximum wait time for fetch requests when insufficient data available (milliseconds) | `500` | |
| `fetch.min.bytes` | Minimum data amount for fetch requests (bytes) | `1` | |
| `fetch.max.bytes` | Maximum data amount per fetch request (bytes) | `1048576` (1MB) | |

#### Health Check Configuration

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `proxy.healthcheckserver.create` | Enable HTTP health check server. Values: `true`, `false` | `false` | |
| `proxy.healthcheckserver.port` | Port for health check endpoints (`/health`, `/ready`) | `8080` | (if enabled) |

### Advanced Configuration

#### Partition Configuration Details

The `proxy.partitions.per.topic` setting is critical for consumer scalability:

- **Purpose**: Virtual partitions enable parallel consumer processing
- **Not tied to Solace queue partitions**: Purely for Kafka consumer coordination
- **Higher values**: No performance penalty, enables more consumers
- **Calculation**: `[Maximum expected consumers per topic] × 2`

**Example Calculation:**
- Topic A: 2 consumer groups × 20 consumers each = 40 max consumers
- Topic B: 1 consumer group × 30 consumers = 30 max consumers  
- Setting: `40 × 2 = 80` partitions per topic

#### Environment Variable Resolution

Properties support environment variable substitution:

```properties
# Basic environment variable
advertised.listeners=${env:KAFKA_ADVERTISED_LISTENERS}

# With default value
ssl.keystore.password=${env:KAFKA_KEYSTORE_PASSWORD:defaultpass}

# Kubernetes-specific tokens (resolved automatically)
advertised.listeners=PLAINTEXT://${K8S_INTERNAL_HOSTNAME}:9092,SASL_SSL://${K8S_EXTERNAL_HOSTNAME}:9094
```

### Example Configuration Files

#### Basic Configuration

```properties
# Kafka listener
listeners=PLAINTEXT://0.0.0.0:9092,SASL_SSL://0.0.0.0:9094

# SSL configuration
ssl.keystore.location=/app/keystore.pkcs12
ssl.keystore.password=${env:KAFKA_KEYSTORE_PASSWORD}
ssl.keystore.type=PKCS12
ssl.enabled.protocols=TLSv1.2

# Solace connection
solace.host=tcps://broker.solace.cloud:55443
solace.vpn_name=production-vpn

# Proxy settings
proxy.separators=._
proxy.partitions.per.topic=50
proxy.queuename.qualifier=KAFKA-PROXY
message.max.bytes=5242880

# Health checks
proxy.healthcheckserver.create=true
proxy.healthcheckserver.port=8080
```

#### Production Kubernetes Configuration

```properties
# Dynamic listeners for Kubernetes
listeners=PLAINTEXT://0.0.0.0:9092,SASL_SSL://0.0.0.0:9094
advertised.listeners=${env:KAFKA_ADVERTISED_LISTENERS}

# SSL with mounted certificates
ssl.keystore.location=/app/keystore
ssl.keystore.password=${env:KAFKA_KEYSTORE_PASSWORD}
ssl.keystore.type=PKCS12
ssl.enabled.protocols=TLSv1.2
ssl.client.auth=none

# Solace production broker
solace.host=tcps://production.solace.cloud:55443
solace.vpn_name=prod-vpn
solace.ssl.validate_certificate=true

# Production scaling
proxy.request.handler.threads=64
proxy.partitions.per.topic=100
proxy.max.uncommitted.messages=2000
message.max.bytes=10485760

# Monitoring
proxy.healthcheckserver.create=true
proxy.healthcheckserver.port=8080
```

## Testing

For testing the proxy with sample Kafka clients, see: **[Sample Kafka Client Demo](getting-started/SampleKafkaClient.md)**

The demo includes separate Java producer and consumer applications with their own JAR files, providing configuration examples for both plaintext and SSL connections. Each demo client uses command-line arguments for configuration rather than embedded classes.

## Authentication & Security

### Kafka Client Authentication

- **SASL_PLAINTEXT**: Username/password passed through to Solace broker
- **SASL_SSL**: Username/password over TLS connection
- **mTLS**: Client certificate verification for enhanced security

### Solace Authentication

- **Basic Auth**: Username/password from Kafka client SASL
- **Client Certificates**: mTLS for certificate-based authentication
- **OAuth**: Token-based authentication (when supported by Solace broker)

## Limitations

- **Authentication**: Only `SASL_PLAINTEXT` and `SASL_SSL` are supported
- **Transactions**: Kafka transactions are not supported
- **Compression**: Producer-side compression is not supported (consumer fetch compression is supported)
- **Exactly-Once Semantics**: Not supported; at-least-once delivery semantics
- **Admin Operations**: Kafka admin API operations are not supported

## Monitoring & Observability

### Health Endpoints

When `proxy.healthcheckserver.create=true`, the following endpoint is available:

```bash
# Health check endpoint - returns 200 OK when proxy is healthy
curl http://proxy-host:8080/health

# Response: HTTP 200 OK with "OK" body when healthy
# Response: HTTP 503 Service Unavailable when unhealthy
```

**Kubernetes Health Check Configuration:**
```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
  
readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

### Logging

The proxy uses SLF4J with Apache Log4j2 for logging. Configure log levels in `log4j2.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
        <File name="File" fileName="logs/proxy.log">
            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </File>
    </Appenders>
    
    <Loggers>
        <Logger name="com.solace.kafka.kafkaproxy" level="INFO"/>
        <Logger name="com.solace.kafka.kafkaproxy.ProxyReactor" level="DEBUG"/>
        <Logger name="com.solacesystems.jcsmp" level="INFO"/>
        <Logger name="org.apache.kafka" level="WARN"/>
        
        <Root level="INFO">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="File"/>
        </Root>
    </Loggers>
</Configuration>
```

### Metrics

Key metrics to monitor:
- Connection counts (Kafka clients and Solace)
- Message throughput (messages/second, bytes/second)
- Consumer lag and commit rates
- Error rates and connection failures
- Thread pool utilization

## Troubleshooting

### Common Issues

1. **SSL Handshake Failures**: Verify certificate paths and passwords
2. **Consumer Group Rebalancing**: Check `proxy.partitions.per.topic` setting
3. **Connection Timeouts**: Verify network connectivity and security groups
4. **Memory Issues**: Tune `proxy.max.uncommitted.messages` and JVM heap size

### Debug Configuration

```properties
# Enable debug logging (set in log4j2.xml)
# Or via system properties:
# -Dlog4j2.logger.com.solace.kafka.kafkaproxy.level=DEBUG

# Increase health check verbosity
# -Dlog4j2.logger.com.solace.kafka.kafkaproxy.HealthCheckServer.level=DEBUG
```

## License

This project is licensed under the Apache License Version 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## Support

For issues and questions:
- **GitHub Issues**: Use for bug reports and feature requests
- **Solace Community**: https://solace.community/
- **Documentation**: https://docs.solace.com/

## Usage

This section describes how Kafka client applications connect to the proxy and how messages flow between Kafka clients and Solace brokers.

### Kafka Client Connection

Kafka clients connect to the proxy exactly as they would connect to a native Kafka broker, using the standard Kafka client libraries and configuration.

#### Producer Connection Example

```java
Properties props = new Properties();
props.put("bootstrap.servers", "proxy-host:9092");  // or proxy-host:9094 for SSL
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

// For SSL connections
props.put("security.protocol", "SASL_SSL");
props.put("sasl.mechanism", "PLAIN");
props.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required " +
    "username=\"solace-username\" password=\"solace-password\";");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.send(new ProducerRecord<>("my-topic", "key", "Hello Solace!"));
```

#### Consumer Connection Example

```java
Properties props = new Properties();
props.put("bootstrap.servers", "proxy-host:9092");
props.put("group.id", "my-consumer-group");
props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
props.put("auto.offset.reset", "earliest");

// For SSL connections (same as producer)
props.put("security.protocol", "SASL_SSL");
props.put("sasl.mechanism", "PLAIN");
props.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required " +
    "username=\"solace-username\" password=\"solace-password\";");

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Collections.singletonList("my-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        System.out.println("Received: " + record.value());
    }
}
```

### Publishing to Solace Topics

When Kafka producers publish messages, the proxy translates Kafka topics to Solace topics with optional hierarchical conversion.

#### Topic Name Conversion

The `proxy.separators` property controls how Kafka topic names are converted to hierarchical Solace topics:

**Without Separators (Default)**
```properties
# Configuration
proxy.separators=

# Kafka topic → Solace topic
orders → orders
user.events → user.events
my_topic_name → my_topic_name
```

**With Separators**
```properties
# Configuration
proxy.separators=._

# Kafka topic → Solace topic
orders → orders
user.events → user/events
my_topic_name → my/topic/name
inventory.updates.retail → inventory/updates/retail
```

#### Publishing Examples

```java
// Kafka producer code
producer.send(new ProducerRecord<>("inventory.updates.retail", "product123", orderData));

// With proxy.separators=._ this becomes:
// - Solace topic: inventory/updates/retail
// - Message published to hierarchical topic structure
// - Solace consumers can subscribe to:
//   - inventory/updates/retail (exact match)
//   - inventory/updates/> (wildcard - all retail updates)  
//   - inventory/> (wildcard - all inventory topics)
```

#### Message Headers and Properties

Kafka message headers are preserved and passed through to Solace as user properties:

```java
// Kafka producer with headers
ProducerRecord<String, String> record = new ProducerRecord<>("orders", "order123", orderJson);
record.headers().add("source", "web-api".getBytes());
record.headers().add("priority", "high".getBytes());
producer.send(record);

// Solace message receives these as user properties:
// - source: "web-api"
// - priority: "high"
```

### Consuming from Solace Queues

Kafka consumers connect to consumer groups, which the proxy maps to Solace queues. The queue naming strategy determines how messages are routed.

#### Queue Naming Strategy

Queue names are formulated using this pattern:
```
[qualifier]/[topic]/[consumer-group]
```

Where:
- **qualifier**: Value of `proxy.queuename.qualifier` property (optional prefix)
- **topic**: Kafka topic name (with separator conversion if configured)
- **consumer-group**: Kafka consumer group ID

#### Queue Name Examples

**Basic Queue Naming**
```properties
# Configuration
proxy.queuename.qualifier=KAFKA-PROXY
proxy.separators=._

# Consumer connection:
# - Topic: user.profile.updates
# - Consumer Group: profile-service
# 
# Resulting Solace queue: KAFKA-PROXY/user/profile/updates/profile-service
```

**Without Qualifier**
```properties
# Configuration  
proxy.queuename.qualifier=
proxy.separators=.

# Consumer connection:
# - Topic: inventory.alerts
# - Consumer Group: warehouse-app
#
# Resulting Solace queue: inventory/alerts/warehouse-app
```

**Topic-Only Queue Naming**
```properties
# Configuration
proxy.queuename.is.topicname=true
proxy.separators=._

# Consumer connection:
# - Topic: system.notifications  
# - Consumer Group: email-service (ignored)
#
# Resulting Solace queue: system/notifications
# Note: All consumer groups for this topic share the same queue
```

#### Consumer Group Behavior

**Multiple Consumers in Same Group**
```java
// Consumer 1
props.put("group.id", "order-processors");
consumer1.subscribe(Collections.singletonList("orders"));

// Consumer 2  
props.put("group.id", "order-processors");
consumer2.subscribe(Collections.singletonList("orders"));

// Both consumers share the same Solace queue: KAFKA-PROXY/orders/order-processors  
// Messages are load-balanced between them (competing consumers)
```

**Different Consumer Groups**
```java
// Group A
props.put("group.id", "audit-service");
consumerA.subscribe(Collections.singletonList("orders"));

// Group B
props.put("group.id", "analytics-service"); 
consumerB.subscribe(Collections.singletonList("orders"));

// Creates separate Solace queues:
// - KAFKA-PROXY/orders/audit-service
// - KAFKA-PROXY/orders/analytics-service
// Both groups receive all messages (broadcast pattern)
```

### Message Flow Patterns

#### Publish-Subscribe Pattern

```java
// Publisher
producer.send(new ProducerRecord<>("notifications.email", emailData));

// Multiple subscribers in different groups
// Group 1: Email delivery service  
consumer1.subscribe(Collections.singletonList("notifications.email"));

// Group 2: Email analytics service
consumer2.subscribe(Collections.singletonList("notifications.email"));

// Result: Both groups receive all email notifications
```

#### Load Balancing Pattern

```java
// Multiple consumers in same group for load balancing
props.put("group.id", "order-processing-workers");

// Worker 1
consumer1.subscribe(Collections.singletonList("orders"));

// Worker 2  
consumer2.subscribe(Collections.singletonList("orders"));

// Worker 3
consumer3.subscribe(Collections.singletonList("orders"));

// Result: Orders are distributed across the 3 workers
```

#### Hierarchical Topic Publishing

```properties
# Configuration enables hierarchical topics
proxy.separators=.
```

```java
// Kafka publishers
producer.send(new ProducerRecord<>("events.user.login", loginEvent));
producer.send(new ProducerRecord<>("events.user.logout", logoutEvent)); 
producer.send(new ProducerRecord<>("events.system.startup", startupEvent));

// Solace topics created:
// - events/user/login
// - events/user/logout  
// - events/system/startup

// Solace consumers can subscribe with wildcards:
// - events/user/>     (all user events)
// - events/>          (all events)
// - events/*/login    (login events from any category)
```

### Authentication Flow

The proxy transparently forwards Kafka SASL credentials to the Solace broker:

1. **Kafka Client** authenticates with proxy using SASL_PLAIN or SASL_SSL
2. **Proxy** extracts username/password from Kafka SASL
3. **Proxy** connects to Solace broker using those credentials
4. **Solace Broker** authenticates and authorizes the user

```java
// Kafka client configuration
props.put("sasl.jaas.config", 
    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
    "username=\"my-solace-user\" password=\"my-solace-password\";");

// These credentials are passed through to Solace broker
// User permissions on Solace determine access to topics/queues
```

### Consumer Scaling Considerations

The `proxy.partitions.per.topic` setting affects consumer scaling:

```properties
# Allow up to 20 consumers per topic across all consumer groups
proxy.partitions.per.topic=20
```

- Each Kafka topic appears to have the specified number of partitions
- Enables parallel consumption by multiple consumers
- Higher values support more concurrent consumers
- No performance penalty for higher values

### Error Handling

#### Connection Failures
- **Kafka Client ↔ Proxy**: Standard Kafka client retry mechanisms apply
- **Proxy ↔ Solace**: Automatic reconnection with configurable retries

#### Message Delivery
- **At-least-once delivery**: Messages may be delivered multiple times
- **No transactions**: Kafka transaction semantics not supported
- **Consumer commits**: Mapped to Solace message acknowledgments

## Security Features

### Request Size Protection

The proxy includes built-in protection against memory exhaustion attacks:

```properties
# Maximum request size limit (prevents OutOfMemoryError)
proxy.max.request.size.bytes=104857600  # 100MB default

# Automatic rejection of oversized requests
# Logs security events for monitoring suspicious activity
```

### SSL/TLS Connection Validation

- **Plaintext Detection**: Automatically detects and rejects plaintext traffic on SSL ports
- **Protocol Validation**: Validates TLS handshake to prevent memory vulnerabilities  
- **Connection Monitoring**: Logs suspicious connection attempts for security analysis
- **Immediate Connection Termination**: Closes invalid connections to prevent resource exhaustion

### Security Event Logging

The proxy logs security-relevant events for monitoring:

```log
# Examples of security event logs:
[SECURITY][Channel 1] OVERSIZED_REQUEST from /192.168.1.100:45123 - size=2147483647, limit=104857600
[SECURITY][Channel 2] PLAINTEXT_ON_SSL_PORT from /10.0.1.50:33445 - detected non-TLS traffic on port 9094
```

### Production JVM Security Configuration

```bash
# Recommended production JVM settings for security and stability
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+DisableExplicitGC \
     -Djava.security.egd=file:/dev/./urandom \
     -jar kafka-wireline-proxy-*.jar proxy.properties
```
