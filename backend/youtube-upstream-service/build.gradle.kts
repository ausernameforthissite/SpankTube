plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:lib:egress-starter"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
