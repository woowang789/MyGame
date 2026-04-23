plugins {
    application
    java
}

group = "mygame"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // WebSocket 서버
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // JSON 직렬화/역직렬화
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // 로깅
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // 테스트 (향후 Phase에서 사용)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("mygame.Main")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.test {
    useJUnitPlatform()
}
