plugins {
    id("java-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
