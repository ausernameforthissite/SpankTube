plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:lib:event-bus"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("org.postgresql:postgresql")
}
