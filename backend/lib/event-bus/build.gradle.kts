plugins {
    id("java-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-amqp")
    api("org.springframework.boot:spring-boot-starter-json")
    api("io.projectreactor:reactor-core")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
