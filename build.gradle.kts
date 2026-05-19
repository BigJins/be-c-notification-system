plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.livenotification"
version = "1.0.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    // Spring Boot starter set
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")  // X-Admin-Token

    // DB
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // API docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Test (Awaitility 는 spring-boot-starter-test 의 transitive — 별도 줄 불필요)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// Pin Testcontainers to a version with working Docker API negotiation
// (1.20.6 from Spring Boot 3.4 BOM has issues with newer Docker engines)
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.3")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("testcontainers.reuse.enable", "true")
    // On Windows with Docker Desktop 4.60 (min API 1.44), use docker_engine_linux pipe.
    // dockerDesktopLinuxEngine and docker_engine stubs return 400 during TC version negotiation.
    environment("DOCKER_HOST", "npipe:////./pipe/docker_engine_linux")
    systemProperty("DOCKER_HOST", "npipe:////./pipe/docker_engine_linux")
    // docker-java reads "api.version" system property to override the negotiation start version.
    // Docker Desktop 4.60 requires minimum 1.44; without this the shaded docker-java defaults to 1.32.
    systemProperty("api.version", "1.44")
}
