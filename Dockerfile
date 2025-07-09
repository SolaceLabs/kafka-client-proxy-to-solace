# 1. Application Build
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

RUN apt-get update && apt-get install -y maven

# Copy Maven build files and download dependencies
COPY pom.xml .
COPY src ./src

# Maven Build
RUN mvn clean package -DskipTests

# 2. Create the final runtime image
FROM eclipse-temurin:17-jre AS final

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/target/kafka-wireline-proxy*.jar app.jar

# Kafka PLAINTEXT: 9092, SSL: 9094
EXPOSE 9092 9094

ENV JAVA_OPTS=""

# Command to run your application
#ENTRYPOINT ["java", "-jar", "app.jar"]
ENTRYPOINT java $JAVA_OPTS -jar app.jar proxy.properties

# Let's run as non-root user: kproxy
RUN groupadd --system kproxy && useradd --system --gid kproxy kproxy
USER kproxy
