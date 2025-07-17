# Kafka Proxy for Solace PubSub+

A high-performance proxy that allows Kafka clients to publish and subscribe to a Solace PubSub+ event broker without any changes to the Kafka client application.

## Description

This project enables Kafka client applications to seamlessly produce and consume messages from the Solace PubSub+ event mesh via the proxy. The proxy speaks the native Kafka wireline protocol to Kafka client applications and the Solace SMF protocol to the Solace PubSub+ Event Broker.

**Key Features:**
- **Protocol Translation**: Transparent conversion between Kafka wireline protocol and Solace SMF
- **Producer Support**: Kafka topics can be published unmodified or converted to hierarchical Solace topics
- **Consumer Support**: Full consumer group management with mapping to Solace queues and topic subscriptions
- **Security**: Comprehensive SSL/TLS and mTLS support for both Kafka clients and Solace connections
- **Kubernetes Ready**: Production-ready deployment configurations for AWS EKS

For producers, Kafka topics can be published to the Solace PubSub+ Event Mesh unmodified, or converted to hierarchical Solace topics by splitting on specified characters.

For consumers, the proxy manages consumer groups and topic subscriptions, mapping them to Solace queues and topic subscriptions with configurable queue naming strategies.

## Getting Started

### Dependencies

- **Java 17+** - Required runtime
- **kafka-clients** - Kafka protocol implementation
- **sol-jcsmp** - Solace messaging API
- **slf4j-api** - Logging framework

### Building

Use Maven to build and package the application:

```bash
# Clone the repository
git clone <repository-url>
cd kafka-client-proxy-to-solace

# Build the project and its dependencies
mvn clean package

# The built JAR will be available at:
target/kafka-wireline-proxy-<version>-jar-with-dependencies.jar
```

### Running as JAR

```bash
# Run the proxy with a properties file
java -jar target/kafka-wireline-proxy-<version>-jar-with-dependencies.jar /path/to/proxy.properties

# Example with JVM tuning options
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -jar target/kafka-wireline-proxy-<version>-jar-with-dependencies.jar \
     /path/to/proxy.properties

# With custom logging configuration
java -Dlogback.configurationFile=logback.xml \
     -jar target/kafka-wireline-proxy-<version>-jar-with-dependencies.jar \
     /path/to/proxy.properties
```

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
| `ssl.enabled.protocols` | Comma-separated list of TLS protocols to enable. Example: `TLSv1.2,TLSv1.3` | `TLSv1.2` | |
| `ssl.cipher.suites` | Comma-separated list of SSL cipher suites to enable | JVM defaults | |
| `ssl.protocol` | SSL protocol to use. Valid values: `TLS`, `TLSv1.1`, `TLSv1.2`, `TLSv1.3` | `TLS` | |

#### mTLS (Mutual TLS) Configuration

These properties enable client certificate verification for enhanced security:

| Property | Description | Default | Required |
| :--- | :--- | :---: | :---: |
| `ssl.client.auth` | Client authentication mode. Values: `required` (mandatory mTLS), `requested` (optional mTLS), `none` (no client auth) | `none` | |
| `ssl.truststore.location` | Path to truststore containing trusted client certificates. Required when `ssl.client.auth` is `required` | | ✅ (for mTLS) |
| `ssl.truststore.password` | Password for the truststore file | | ✅ (for mTLS) |
| `ssl.truststore.type` | Format of the truststore file. Valid values: `JKS`, `PKCS12` | `JKS` | |

### Solace Broker Connection Settings

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

The demo includes embedded Java producer and consumer clients with configuration examples for both plaintext and SSL connections.

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

When `proxy.healthcheckserver.create=true`:

- **`/health`**: Overall proxy health status
- **`/ready`**: Readiness for traffic (Kubernetes readiness probe)

### Logging

The proxy uses SLF4J for logging. Configure log levels in `logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.solace.kafka.kafkaproxy" level="INFO"/>
    <logger name="com.solace.kafka.kafkaproxy.ProxyReactor" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
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
# Enable debug logging
logging.level.com.solace.kafka.kafkaproxy=DEBUG

# Increase health check verbosity
logging.level.com.solace.kafka.kafkaproxy.HealthCheckServer=DEBUG
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



