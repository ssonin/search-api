plugins {
  idea
  java
  id("io.quarkus")
}

repositories {
  mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

val assertjVersion: String by project

dependencies {
  implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
  implementation("io.quarkiverse.httpproblem:quarkus-http-problem")
  implementation("io.quarkus:quarkus-arc")
  implementation("io.quarkus:quarkus-flyway")
  implementation("io.quarkus:quarkus-hibernate-validator")
  implementation("io.quarkus:quarkus-jdbc-postgresql")
  implementation("io.quarkus:quarkus-reactive-pg-client")
  implementation("io.quarkus:quarkus-rest")
  implementation("io.quarkus:quarkus-rest-jackson")

  integrationTestImplementation("io.rest-assured:rest-assured")
  integrationTestImplementation("org.testcontainers:testcontainers-postgresql")

  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  testImplementation("io.quarkus:quarkus-junit")
  testImplementation("org.assertj:assertj-core:${assertjVersion}")
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.mockito:mockito-core")
  testImplementation("org.mockito:mockito-junit-jupiter")
}

group = "ssonin"
version = "1.0.0-SNAPSHOT"

idea {
  module {
    testSources.from(sourceSets["integrationTest"].java.srcDirs)
    testResources.from(sourceSets["integrationTest"].resources.srcDirs)
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

tasks.withType<JavaCompile> {
  options.encoding = "UTF-8"
  options.compilerArgs.add("-parameters")
}

tasks.check {
  dependsOn(tasks.named("quarkusIntTest"))
}
