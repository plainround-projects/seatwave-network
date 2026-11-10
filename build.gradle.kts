import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.inject.Inject

plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.result4j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

/**
 * Makes throwaway TEST certificates with the JDK's keytool: one test certificate authority (CA)
 * and three server certificates it signs (valid, expired and issued for the wrong name).
 *
 * Everything lands in build/tls, which git ignores, so no key or keystore is ever committed. The
 * keystore password is random per run and written to a file next to the keystores, so no password
 * appears in code, configuration or a process listing.
 */
abstract class TestCertificates : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val keytool: Property<String>

    // The date is an input, so the certificates are made again on a new day and never expire while
    // a reader is still using a build folder from last month.
    @get:Input
    abstract val day: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val passwordFile = directory.resolve("store.password")
        val passwordBytes = ByteArray(18)
        SecureRandom().nextBytes(passwordBytes)
        passwordFile.writeText(Base64.getEncoder().encodeToString(passwordBytes), Charsets.UTF_8)
        Files.setPosixFilePermissions(passwordFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        val password = passwordFile.absolutePath
        val caStore = directory.resolve("seatwave-test-ca.p12").absolutePath
        // EC P-256 keys: the curve every current TLS client supports, and a fast handshake.
        val ecKey = listOf("-keyalg", "EC", "-groupname", "secp256r1")
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storetype", "PKCS12", "-storepass:file", password,
                "-alias", "ca", "-dname", "CN=SeatWave TEST CA - never trust outside this lab", "-validity", "30",
                "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign") + ecKey,
        )
        keytool(
            listOf("-exportcert", "-rfc", "-keystore", caStore, "-storepass:file", password, "-alias", "ca",
                "-file", directory.resolve("seatwave-test-ca.pem").absolutePath),
        )
        // Three server certificates: the good one, one that expired two days ago, and one whose only
        // name is seatwave.test, so a client that connects to 127.0.0.1 must refuse it.
        val servers = mapOf(
            "valid" to listOf("-validity", "30", "-ext", "san=dns:localhost,ip:127.0.0.1"),
            "expired" to listOf("-startdate", "-3d", "-validity", "1", "-ext", "san=dns:localhost,ip:127.0.0.1"),
            "wrong-name" to listOf("-validity", "30", "-ext", "san=dns:seatwave.test"),
        )
        servers.forEach { (name, options) ->
            keytool(
                listOf("-genkeypair", "-keystore", caStore, "-storepass:file", password, "-alias", name,
                    "-dname", "CN=SeatWave TEST server ($name)", "-signer", "ca", "-signerkeypass:file", password) +
                    ecKey + options,
            )
            // Each server gets a keystore with only its own key and chain; the CA's key stays behind.
            keytool(
                listOf("-importkeystore", "-srckeystore", caStore, "-srcstorepass:file", password, "-srcalias", name,
                    "-destkeystore", directory.resolve("seatwave-$name.p12").absolutePath, "-deststoretype", "PKCS12",
                    "-deststorepass:file", password, "-destalias", "seatwave"),
            )
        }
    }

    private fun keytool(arguments: List<String>) {
        // keytool's chatter is kept and shown only if it fails, so a run's output stays readable.
        val chatter = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(listOf(keytool.get()) + arguments)
            standardOutput = chatter
            errorOutput = chatter
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException("keytool ${arguments.first()} failed: " + chatter.toString(Charsets.UTF_8))
        }
    }
}

val testCertificates = tasks.register<TestCertificates>("testCertificates") {
    group = "verification"
    description = "Makes the throwaway TEST certificate authority and server certificates in build/tls."
    keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }
    day = LocalDate.now(ZoneOffset.UTC).toString()
    outputDirectory = layout.buildDirectory.dir("tls")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(testCertificates)
    // The TLS tests read the generated test certificates from here.
    systemProperty("seatwave.tls.directory", layout.buildDirectory.dir("tls").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
// The measuring runs live here, outside the test task, so ./gradlew check stays fast.
val entryPoints = mapOf(
    "serve" to "io.plainround.seatwave.network.server.ServeCli",
    "timing" to "io.plainround.seatwave.network.timing.TimingCli",
    "tlsFailures" to "io.plainround.seatwave.network.tls.TlsFailuresCli",
    "report" to "io.plainround.seatwave.network.report.ReportCli",
    "pageModel" to "io.plainround.seatwave.network.model.PageModelCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        dependsOn(testCertificates)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
    }
}
