# Running Producer Demo from command line



## Run Proxy


```bash

cd ~/Development/Projects/kafka-wireline/pubsubplus-client-proxy-kafka-producer


java -XX:+UseG1GC -XX:MaxHeapFreeRatio=40 -XX:G1HeapWastePercent=10 -jar target/kafka-wireline-proxy-1.2.jar src/test/resources/configs/demo-proxy.properties



java \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  -XX:+UseG1GC -XX:MaxHeapFreeRatio=40 -XX:G1HeapWastePercent=10 \
  -jar target/kafka-wireline-proxy-1.2.jar test-data/configs/demo-proxy.properties


```

## Run Producer


```bash
java -cp demo-producer/target/kafka-demo-producer-3.9.1.jar com.solace.kafka.wireline.kafkaproxy.demo.LargeFileGenerator getting-started/test-data/publish-long-record.txt
```


```bash

cd ~/Development/Projects/kafka-facade/demo/producer

### 50,000 messages to my/test/topic -- 20 Keys -- No Delay

java -jar kafka-demo-producer-3.7.1.jar --config demo-producer.properties --topic PRODUCER_TOPIC:BOOKING_CHANGES --input-file publish-data-kv-20-fixed.txt --num-records 5000 -d 5

```

## Run Consumers
```bash

## Consumer 3.3

cd ~/Development/Projects/kafka-facade/demo/consumer3.3

java -jar kafka-demo-consumer-3.3.1.jar -c demo-consumer.properties -g AIR-CANADA -t BOOKING_CHANGES



## Consumer 3.9

cd ~/Development/Projects/kafka-facade/demo/consumer3.9

java -jar kafka-demo-consumer-3.9.1.jar -c demo-consumer.properties -g AIR-FRANCE -t BOOKING_CHANGES

java -jar kafka-demo-consumer-3.9.1.jar -c demo-consumer.properties -g AIR-CANADA -t BOOKING_CHANGES



## Consumer 2.5

cd ~/Development/Projects/kafka-facade/demo/consumer2.5

java -jar kafka-demo-consumer-2.5.1.jar -c demo-consumer.properties -g AIR-FRANCE -t BOOKING_CHANGES

```





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
