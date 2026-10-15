// Serviço próprio da Madames Formosas (catálogo de produtos): mesmo repositório do servidor, mas build, testes, jar e deploy separados.
// Os plugins já estão no classpath pelo projeto raiz, por isso aqui vão sem versão.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "com.class_erp"
version = "0.0.1"

application {
    mainClass = "madames.MadamesServerKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("mysql:mysql-connector-java:8.0.33")
    // H2: testes e o modo local (madames.db.url=jdbc:h2:...) para desenvolver sem MySQL
    implementation(libs.h2)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    // nome que NÃO contém "server-0.0.1.jar": o deploy do servidor principal mata processos por esse nome
    archiveFileName.set("madames-0.0.1.jar")
    // junta os META-INF/services de todas as bibliotecas: sem isso só um driver JDBC (H2 ou MySQL) fica registrado no jar
    mergeServiceFiles()
}
