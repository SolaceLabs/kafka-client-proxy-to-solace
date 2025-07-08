
# Kafka Proxy for Solace PubSub+

A proxy that allows Kafka clients to publish and subscribe to a Solace PubSub+ event broker without changes to the Kafka client application.

## Description

This project allows a Kafka client application to produce and consume messages from the Solace PubSub+ event mesh via the proxy. The proxy speaks the Kafka wireline protocol to the Kafka client application and the Solace SMF protocol to the Solace PubSub+ Event Broker.

For producers, the Kafka topic can be published to the Solace PubSub+ Event Mesh unmodified, or converted to a hierarchical Solace topic by splitting on a specified list of characters.

For consumers, the proxy manages consumer groups and topic subscriptions, mapping them to Solace queues and topic subscriptions.

## Getting Started

### Dependencies

*   `kafka-clients`
*   `sol-jcsmp`
*   `slf4j-api` and an SLF4J binding of your choice (see http://www.slf4j.org/manual.html)

### Building

Use Maven to build and run the application:

```bash
# Build the project and its dependencies
mvn clean install

# Run the proxy with an example properties file
java -cp target/kafka-wireline-proxy-1.2-jar-with-dependencies.jar com.solace.kafka.kafkaproxy.ProxyMain proxy-example.properties
```






# Configuration

The Kafka Wireline proxy for Solace takes one command line argument: a properties file to configure the proxy.

- Kafka Client Listener
    - SSL/TLS for Kafka Listener
    - mTLS for Kafka Listener
- Solace Broker Connection Settings
    - SSL/TLS for Solace Broker Connection
    - mTLS for Solace Broker Connection
- 

## Kafka Client Listener

| Property | Description | Default |
| :--- | :--- | :---: |
| `listeners` | **REQUIRED** A comma-separated list of `[protocol://]host:[port]` tuples for the proxy to listen on for Kafka client connections. Example: `PLAINTEXT://localhost:9092,SASL_SSL://localhost:9094` |  |
| `advertised.listeners` | An optional comma-separated list of `host:port` tuples to advertise to clients. This is useful when the proxy is running in a container or behind a NAT. The number of entries must match the number of entries in `listeners`. |  |

### Kafka Listener Security (SSL/TLS)

| Property | Description | Default |
| :--- | :--- | :---: |
| `ssl.keystore.location` | The path to the keystore file for the server's SSL certificate. | |
| `ssl.keystore.password` | The password for the keystore file. | |
| `ssl.enabled.protocols` | A comma-separated list of the TLS protocols to enable. Example: `TLSv1.2` | |

> **Note:** The proxy supports other standard SSL configuration properties.

### Kafka Listener mTLS / Client Certificate verification

These properties control mTLS configuration for Kafka client connections.

| Property | Description | Default |
| :--- | :--- | :---: |
| `ssl.client.auth` | Controls whether the proxy requests or requires client authentication (mTLS). Can be `required`, `requested`, or `none`. | `none` |
| `ssl.truststore.location` | The path to the truststore file containing certificates from trusted clients. Required if `ssl.client.auth` is `required`. | |
| `ssl.truststore.password` | The password for the truststore file. | |
| `ssl.truststore.type` | The format of the truststore file. Valid values are `JKS` and `PKCS12`. | `JKS` |

## Solace Broker Connection Settings

These properties are used to configure the proxy's connection to the Solace PubSub+ Event Broker. They are standard Solace JCSMP properties. All Solace connection settings will have `solace` as a qualifier on the property path. This is to prevent conflicts with any Kafka properties, current or future. Includes SSL/TLS configuration for connection to Solace broker.

| Property | Description | Default |
| :--- | :--- | :---: |
| `solace.host` | **REQUIRED** The hostname or IP address of the Solace PubSub+ Event Broker, including the port. Ports specified should be for `SMF` connections. Examples: `tcps://my-broker.messaging.solace.cloud:55443`, `tcp://localhost:55555` |
| `solace.vpn_name` | **REQUIRED** The Message VPN on the Solace broker to which the proxy will connect. |

> **NOTE:** Basic Auth 'username' and 'password' are specified by the Kafka producing application, using SASL

### Solace Client Security (SSL/TLS)

SSL/TLS Settings for connections originating on the Proxy to the Solace broker.
| Property | Description | Default |
| :--- | :--- | :---: |
| `solace.ssl.enabled.protocols` | A comma-separated list of the TLS protocols to enable. Example: `TLSv1.2`, `TLSv1.2,TLSv1.3` | `TLSv1.2` |
| `solace.ssl.truststore.location` | The path to the truststore file containing certificates from Solace broker servers. Required if Solace broker server certificate is not public (self-signed or otherwise unknown). | |
| `solace.ssl.truststore.password` | The password for the truststore file. | |
| `solace.ssl.truststore.type` | The format of the truststore file. Valid values are `JKS` and `PKCS12`. | `JKS` |

> **Note:** The proxy supports other standard SSL configuration properties.

### Solace Client mTLS

These properties control mTLS configuration for Solace broker connections.

| Property | Description | Default |
| :--- | :--- | :---: |
| `solace.ssl.keystore.location` | The file path to the keystore file for the server's SSL certificate. Required for mTLS connections from the proxy to the Solace broker. | |
| `solace.ssl.keystore.password` | The password for the keystore file. | |

## Proxy Operational Configuration

The following configurations affect the operation of the Kafka Wireline Proxy globally. The configuration parameters should be reviewed and set carefully to achieve the desired results. 

| Property | Description | Default |
| :--- | :--- | :---: |
| `proxy.separators` | A string of characters to be treated as separators in a Kafka topic name. The proxy will replace these characters with `/` to create a hierarchical Solace topic when records are produced to a Kafka topic. Example setting: `._` If published Kafka topic name is: `my_kafka_topic` &rarr; published Solace topic: `my/kafka/topic` <br>Impacts **Producers / Published topics** | `""` |
| `message.max.bytes` | The maximum size of a single message that a Kafka client can produce to a topic. Applies to all Kafka producers and topics.<br>Impacts **Producers** | `1048576` |
| `proxy.request.handler.threads` | The number of worker threads for handling blocking Kafka consumer requests, such as `FETCH`. This value should be set to:<br>`[ Total expected consumers ] * [ 1.5 -> 2 ]`<br>Impacts **Consumer Scalability**| `32` |
| `proxy.partitions.per.topic` | The number of virtual partitions to advertise per Kafka topic for ***consumer clients***. This value should be set to `[ Max consumers per Kafka topic ] * 2`.<br>See notes below on recommended settings <br>Impacts **Consumer Scalability**| `100` |
| `proxy.max.uncommitted.messages` | The number of messages that can be received and uncommitted/unacknowledged by one Kafka consumer. Message delivery will resume after the client commits to offset and the flow acknowledges the messages. Higher values can lead to better performance but may cause redelivery of un-acknowledged messages if consumer drops prior to offset commit.<br>Impacts **Consumer Perforamance**| `1000` |
| `proxy.queuename.qualifier` | A qualifier on expected queue name when consumer subscribes to a Kafka topic. <br>**Example:** Consumer subscribes to `TOPIC_A` for `group.id` = `GROUP1` and Qualifier = `KPROXY` then:<br>Queue Name = `KPROXY/TOPIC_A/GROUP1`<br>Impacts **Consumers, Expected Queue Names**| `""` |
| `proxy.queuename.is.topicname` | Simply use the subscribed Kafka Topic name as the Solace Queue name. Ignore consumer `group.id` and  `proxy.queuename.qualifier` setting.<br>Value values: `true` or `false`. <br>Impacts **Consumers, Expected Queue Names**| `false` |
| `proxy.fetch.compression.type` | Type of compression to use when fetching records from Kafka proxy. Valid values are `none`, `gzip`, `snappy`, `lz4`, and `zstd`. Applies to all Kafka topics and consumers using the proxy. <br>Impacts **Consumers, Proxy Performance, and Byte Rate**| `none` |

### Kafka Consumer Client

These properties control how the proxy handles `Fetch` requests from Kafka consumers.

| Property | Description | Default |
| :--- | :--- | :---: |
| `fetch.max.wait.ms` | The maximum time in milliseconds that the proxy will wait before answering a fetch request if there isn't enough data to immediately satisfy `fetch.min.bytes`. Applies if value is not supplied by the consumer.| `500` |
| `fetch.min.bytes` | Default minimum amount of data the proxy should return for a fetch request. The proxy will wait up to `fetch.max.wait.ms` for this amount of data to become available. Applies if value is not supplied by the consumer.| `1` |
| `fetch.max.bytes` | Default maximum amount of data the proxy will return in a single fetch request. This acts as an absolute upper limit. Applies if value is not supplied by the consumer.| `1048576` |

**May need to revisit these - Perhaps the should be Maximums on the proxy side? Maybe just leave them out**





**TODO: Move this to separate section and reference**
> **Special Note on Kafka setting** `proxy.partitions.per.topic`:<br>The number Advertised Partitions per Kafka topic are not tied to the number of partitions on the Solace queue backing the virtual Kafka topic. Partition identifiers are only used to track consumers inside of the proxy. Setting this value higher than the number of partitions on the Solace queue will have no noticable effect on performance or function. Also, setting this value much higher than the actual number of expected consumers will have no noticable impact.
> <br>**Example**: There are 2 kafka topics: `TOPIC_A` and `TOPIC_B`.
> <br>&nbsp;&nbsp;&nbsp;&nbsp;`TOPIC_A` will have 2 consumer groups, each with maximum of 20 members for a total of **40 consumers**.
> <br>&nbsp;&nbsp;&nbsp;&nbsp;`TOPIC_B` will have 1 consumer group with a maximum of **30 consumers**.
> <br>&nbsp;&nbsp;&nbsp;&nbsp;`TOPIC_A` has the maximum consumers at `[ 2 Groups * 20 Consumers per Group ] = 40`
> <br>&nbsp;&nbsp;&nbsp;&nbsp;So using `[ Max consumers per Kafka topic ] * 2` we get: `[ 40 ] * 2 = 80`









## Limitations

*   Only `SASL_PLAINTEXT` or `SASL_SSL` authentication is supported. The provided username and password from the Kafka client is passed through to the Solace PubSub+ Event broker.
*   Kafka Transactions and Compression are not supported.

## License

This project is licensed under the Apache License Version 2.0.








# pubsubplus-client-proxy-kafka-producer

A proxy that allows a Kafka producer to publish to a PubSub+ topic without changes to the Kafka client application.

## Description

This project allows a Kafka producer application to publish topics to the PubSub+ event mesh via the proxy.
The proxy talks the Kafka wireline protocol to the Kafka producer application, and talks the Solace wireline protocol to the
Solace PubSub+ Event Mesh.

The Kafka topic can be published to the Solace PubSub+ Event Mesh unmodified, or converted to a Solace hierarchical topic by
splitting on a specified list of characters.


## Getting Started

### Dependencies

* kafka-clients
* sol-jcsmp
* slf4j-api and the sl4j binding of your choice (see http://www.slf4j.org/manual.html)


### Building

Use either Maven or Gradle to build the application
```
mvn install
java -cp target/kafkaproxy-1.0-SNAPSHOT-jar-with-dependencies.jar org.apache.kafka.solace.kafkaproxy.ProxyMain proxy-example.properties
```
 ~ OR ~
```
./gradlew assemble
cd build/distributions
unzip kafkaproxy-1.0-SNAPSHOT.zip
cd kafkaproxy-1.0-SNAPSHOT
bin/kafkaproxy proxy-example.properties
```

### Executing program

* The proxy take a mandatory properties filename on the command line
* Mandatory property file entries:
   - listeners : Comma-separated list of one or more ports to listen on for the Kafka wireline (protocol://host:port), e.g. PLAINTEXT://TheHost:9092,SSL://TheHost:9093
   - host : the PubSub+ Event broker that the proxy should connect to, e.g. host=192.168.168.100
* Other possible property file entries:
   - vpn_name : The message VPN on the Solace PubSub+ Event broker to connect to
   - advertised.listeners : Comma-separated list of ports to advertise (host:port). If specified, the number of entries 
                            in "advertised.listeners" must match the number of entries in "listeners". This is used when 
                            the external address that clients must connect to is different than the internal address
                            that the proxy is listening on. This can occur, for example, when the proxy is running in a container.
   - Kafka client transport layer security parameters, such as:
       - ssl.keystore.location=server.private
       - ssl.keystore.password=serverpw
       - ssl.enabled.protocols=TLSv1.2




## Help

Depending on the producing rate of the producer application, the producer properties may have to be tuned to provide correct flow control.
An appropriate method for this is to set buffer.memory, e.g. buffer.memory = 2000000
The default for buffer.memory is 33554432 which can lead to the producer buffering a very large number of small records, leading to records timing out.

### Limitations

* Only SASL_PLAINTEXT or SASL_SSL authentication is supported - the provided username and password from the Kafka producer is passed through to the Solace PubSub+ Event broker
* Transactions and compression are not supported

## Authors

Solace Corporation

## License

This project is licensed under the Apache License Version 2.0 - see the LICENSE.md file for details.



