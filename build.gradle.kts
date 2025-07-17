/*
 * Kafka Wireline Proxy for Solace Platform
 * This project defines a deployable proxy that enables Kafka producers 
 * and consumers to publish to Solace topics and subscribe to Solace queues.
 */
plugins {
    java
    application
    `maven-publish`
    // Use the shadow plugin to create a fat JAR, similar to maven-assembly-plugin
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

// Define versions to exactly match Maven pom.xml
val kafkaVersion = "3.9.1"
val solaceJcsmpVersion = "10.27.2"
val slf4jVersion = "2.0.16"
val log4j2Version = "2.23.0"
val guavaVersion = "33.4.7-jre"
val lombokVersion = "1.18.36"
val junitJupiterVersion = "5.10.2"

dependencies {
    // Kafka dependencies
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    
    // Solace dependencies
    implementation("com.solacesystems:sol-jcsmp:$solaceJcsmpVersion")
    
    // Google Guava for ThreadFactoryBuilder
    implementation("com.google.guava:guava:$guavaVersion")

    // Logging dependencies - exactly matching Maven
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j2Version")

    // Lombok - Fix dependency scope
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // For tests
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // JUnit 5 test dependencies - exactly matching Maven
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.4.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion") {
        exclude(group = "org.apiguardian", module = "apiguardian-api")
    }
}

// Project metadata matching Maven pom.xml
group = "com.solace.kafkawireline"
version = "0.1.0"
description = "Kafka Wireline Proxy for Solace Platform"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

application {
    // Correct main class confirmed from ProxyMain.java
    mainClass.set("com.solace.kafka.kafkaproxy.ProxyMain")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Configure shadowJar to match Maven assembly plugin behavior
tasks.shadowJar {
    archiveBaseName.set("kafka-wireline-proxy")
    archiveVersion.set("0.1.0")
    archiveClassifier.set("jar-with-dependencies")
    
    manifest {
        attributes(
            "Main-Class" to "com.solace.kafka.kafkaproxy.ProxyMain",
            "Implementation-Title" to "Kafka Wireline Proxy",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Solace Corporation"
        )
    }
    
    // Handle duplicate files
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // Merge service files for proper dependency injection
    mergeServiceFiles()
    
    // Exclude signature files to avoid security issues
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

// Make shadowJar the default build output (matching Maven assembly behavior)
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Disable regular JAR creation (Maven assembly plugin disables default-jar)
tasks.jar {
    enabled = false
}

// Create tasks for running the application
tasks.register<JavaExec>("run-proxy") {
    group = "application"
    description = "Run the Kafka Wireline Proxy"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.solace.kafka.kafkaproxy.ProxyMain")
    
    // Allow passing arguments: ./gradlew run-proxy --args="config.properties"
    if (project.hasProperty("args")) {
        args = project.property("args").toString().split(" ")
    }
}

// Create demo client tasks (if they exist in the project)
tasks.register<JavaExec>("run-demo-producer") {
    group = "demo"
    description = "Run the demo producer client"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.solace.kafka.kafkaproxy.demo.DemoProducer")
    
    if (project.hasProperty("args")) {
        args = project.property("args").toString().split(" ")
    }
}

tasks.register<JavaExec>("run-demo-consumer") {
    group = "demo"
    description = "Run the demo consumer client"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.solace.kafka.kafkaproxy.demo.DemoConsumer")
    
    if (project.hasProperty("args")) {
        args = project.property("args").toString().split(" ")
    }
}

// Publishing configuration matching Maven metadata
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("Kafka Wireline Proxy")
                description.set("Kafka Wireline Proxy for Solace Platform. This project defines a deployable proxy that enables Kafka producers and consumers to publish to Solace topics and subscribe to Solace queues.")
                
                developers {
                    developer {
                        name.set("Jonathan Bosloy")
                        organization.set("Solace")
                        organizationUrl.set("https://solace.com")
                    }
                    developer {
                        name.set("Andrew MacKenzie")
                        organization.set("Solace")
                        organizationUrl.set("https://solace.com")
                    }
                    developer {
                        name.set("Dennis Brinley")
                        email.set("dennis.brinley@solace.com")
                        organization.set("Solace")
                        organizationUrl.set("https://solace.com")
                    }
                }
            }
        }
    }
}

// Disable distribution tasks (not needed for fat JAR approach)
tasks.distZip {
    enabled = false
}

tasks.distTar {
    enabled = false
}

tasks.startScripts {
    enabled = false
}

// Clean task enhancement
tasks.clean {
    delete("logs")
}

// Add task to show project info
tasks.register("info") {
    group = "help"
    description = "Display project information"
    doLast {
        println("Project: ${project.name}")
        println("Version: ${project.version}")
        println("Group: ${project.group}")
        println("Main Class: com.solace.kafka.kafkaproxy.ProxyMain")
        println("Kafka Version: $kafkaVersion")
        println("Solace JCSMP Version: $solaceJcsmpVersion")
        println("Java Version: ${java.sourceCompatibility}")
    }
}
