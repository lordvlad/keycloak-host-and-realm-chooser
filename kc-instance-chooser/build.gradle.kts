plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

val keycloakVersion: String by properties
val jUnitJupiterVersion: String by properties

dependencies {
    implementation(enforcedPlatform("org.keycloak.bom:keycloak-spi-bom:${keycloakVersion}"))
    implementation(enforcedPlatform("org.keycloak.bom:keycloak-adapter-bom:${keycloakVersion}"))
    implementation(enforcedPlatform("org.keycloak:keycloak-parent:${keycloakVersion}"))

    implementation("org.keycloak:keycloak-core")
    implementation("org.keycloak:keycloak-server-spi")
    implementation("org.keycloak:keycloak-server-spi-private")
    implementation("org.keycloak:keycloak-services")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(jUnitJupiterVersion)
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}


val memory = "400m"
val commonOpts = arrayOf(
    "docker",
    "run",
    "-i",
    "-d",
    "-m", "${memory}",
    "-e", "JAVA_OPTS=-Xms64m -Xmx${memory} -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.err.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom -XX:+UseParallelGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90",
    "-e", "KC_DOMAIN_HOSTS=foo.com,http://localhost:8080,master1;bar.com,http://localhost:8081,master1;baz.com,http://localhost:8080,idp-client,idp",
    "-e", "KEYCLOAK_ADMIN=keycloak",
    "-e", "KEYCLOAK_ADMIN_PASSWORD=keycloak",
    "-e", "DEBUG_PORT='*:8787'",
    "-v", "${buildDir}/libs:/opt/keycloak/providers",
)

tasks.register<Exec>("start0") {
    dependsOn("assemble", "stop")
    commandLine(
        *commonOpts,
        "--name", "kc0",
        "-v", "${projectDir}/src/test/resources/kc0:/opt/keycloak/data/import",
        "-p", "8080:8080",
        "-p", "8787:8787",
        "quay.io/keycloak/keycloak", "start-dev", "--import-realm", "--debug")
}

tasks.register<Exec>("start1") {
    dependsOn("assemble", "stop")
    commandLine(
        *commonOpts,
        "--name", "kc1",
        "-v", "${projectDir}/src/test/resources/kc1:/opt/keycloak/data/import",
        "-p", "8081:8080",
        "-p", "8788:8787",
        "quay.io/keycloak/keycloak", "start-dev", "--import-realm", "--debug")
}

tasks.register<Exec>("stop") {
    commandLine("docker", "rm", "-f", "kc0", "kc1")
    isIgnoreExitValue = true
}


tasks.register("start") {
    dependsOn("start0", "start1")
}