# Running Producer Demo from command line

## Set path to use Kafka console commands
*If not already set...*
```bash
export PATH=$PATH:$HOME/Development/kafka/bin
```

## Build projects

```bash
## From project root
mvn clean install

## Build Demo producer
cd demo-producer
mvn clean package
```

## Execute Demo proxy

```bash
## From project root
java -jar target/kafka-wireline-proxy-1.1-SNAPSHOT-jar-with-dependencies.jar src/test/resources/configs/demo-proxy.properties

## Run with DEBUG logging
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar target/kafka-wireline-proxy-1.1-SNAPSHOT-jar-with-dependencies.jar src/test/resources/configs/demo-proxy.properties

## Run with TRACE logging
java -Dorg.slf4j.simpleLogger.defaultLogLevel=trace -jar target/kafka-wireline-proxy-1.2-SNAPSHOT-jar-with-dependencies.jar src/test/resources/configs/demo-proxy.properties

```

## Execute Demo producer with random Keys/Values and receive using Solace Try-Me

Requires Queue `MY_TEST_QUEUE` with subscription to published topic

```bash
## From project root

java -jar demo-producer/target/kafka-keyvalue-producer-1.0.0-one-jar.jar \
  --config demo-producer/src/test/resources/configs/demo-producer.properties \
  --topic KAFKA_WIRELINE_TOPIC \
  --input-file src/test/resources/data/publish-data-kv-random.txt \
  --num-records 10

stm receive -q MY_TEST_QUEUE --output-mode FULL
```


## Execute producer using Kafka console producer and receive using Solace Try-Me

Requires Queue `MY_TEST_QUEUE` with subscription to published topic

```bash
kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --producer.config demo-producer/src/test/resources/configs/demo-producer.properties \
  --topic KAFKA_WIRELINE_TOPIC

stm receive -q MY_TEST_QUEUE --output-mode FULL
```
