plugins {
    id("org.springframework.boot")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":backend:lib:egress-starter"))
    implementation("com.github.libre-tube:NewPipeExtractor:3e863d7")
    compileOnly("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    testImplementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
