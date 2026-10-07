// Serviço próprio da SecretárIA: mesmo repositório do servidor, mas build, testes, jar e deploy separados.
// Os plugins já estão no classpath pelo projeto raiz, por isso aqui vão sem versão.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "com.class_erp"
version = "0.0.1"

application {
    mainClass = "secretaria.SecretariaServerKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ponte com a Gemini Live (WebSocket de saída)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("mysql:mysql-connector-java:8.0.33")
    // H2: testes e o modo local (secretaria.db.url=jdbc:h2:...) para desenvolver sem MySQL
    implementation(libs.h2)

    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.logback.classic)
    implementation("com.auth0:java-jwt:4.4.0") // JWT da equipe da clínica

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    // nome que NÃO contém "server-0.0.1.jar": o deploy do servidor principal mata processos por esse nome
    archiveFileName.set("secretaria-0.0.1.jar")
}
