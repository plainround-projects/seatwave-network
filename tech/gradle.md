# Gradle 9.9.1 (build tool) and the Kotlin DSL

## What it is

Source code is text. A computer cannot run text until other programs have turned it into something runnable. A **build tool** is the program that does all those steps in the right order, from one command: it fetches the libraries the code needs, compiles the Java source files into **class files** (the form the Java Virtual Machine, the JVM, can run), runs the tests, checks the code style and measures the test coverage. **Gradle** is a build tool for Java. You tell it what you want (for example `check`) and it works out which smaller steps are needed.

A Gradle build is described in files written in a language. This repository uses the **Kotlin DSL**: the build file is a Kotlin script. "DSL" means domain-specific language, a small language made for one job. A file that ends in `.kts` is a Kotlin script, a Kotlin program that is run directly instead of being compiled first.

A **terminal** is a window where you type commands. The word after the program name, such as `check` in `./gradlew check`, is the name of a **task**. A task is one unit of work Gradle knows how to do.

## Why this repository uses it

The hands-on project of Module 5 must build and test with one command and the same result on every machine. Gradle gives that: `./gradlew check` makes the test certificates, checks the formatting of the code, compiles it with every warning turned into an error, runs 62 tests and fails if the line coverage is below 80 percent. The build file also gives each of the five programs (the server, the Java timing client, the TLS failures, the report and the page model) its own task, so a learner types one short command instead of assembling a long Java command line. It keeps the measuring runs out of `check`, so the gate takes seconds. And one custom task, `testCertificates`, creates throwaway TLS certificates with the JDK's `keytool`, so no key is ever committed (`keytool-test-certificates.md`).

## Where it appears

| Path | What it does with Gradle |
| --- | --- |
| `settings.gradle.kts` | Names the project and says where libraries may be downloaded from. |
| `build.gradle.kts` | The build itself: plugins, Java version, libraries, compiler flags, formatter, coverage, the certificate task, the test task, the five program tasks. |
| `gradle/libs.versions.toml` | The version catalog: one place for every version (`toml-version-catalog.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | Pinned versions of every library, including the ones libraries bring along (`dependency-locking.md`). |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The wrapper that downloads and starts the right Gradle (`gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`). |
| `build/` and `.gradle/` | Folders Gradle creates while it works. Git ignores them (`git.md`). |

## How it works underneath

1. You run `./gradlew check`. The wrapper starts a **Gradle process**, a Java program that stays alive in the background for a while (the **daemon**) so the next command starts quickly.
2. Gradle reads `settings.gradle.kts` first. That file decides which projects are part of the build. Here there is one project.
3. Gradle then reads `build.gradle.kts`. Running the file does not build anything. It only **configures**: it creates tasks and tells each one what it depends on.
4. Gradle looks at the task you asked for and at everything it depends on, and builds a **task graph**: a list of tasks in a legal order, each after the tasks it needs.
5. It runs the tasks. For each task it checks whether the inputs (source files, settings) and outputs (class files, reports) are the same as last time. If yes, the task is marked `UP-TO-DATE` and skipped. This is why a second `./gradlew testCertificates` took 327 ms on this machine: it said `Task :testCertificates UP-TO-DATE`.
6. Everything it makes goes into the `build/` folder. Downloaded libraries are kept in a cache folder in your home directory (`~/.gradle`), shared by all projects.

A **plugin** is a package of tasks and settings that you switch on in one line. The `java` plugin adds the tasks that compile and test Java. A **dependency** is a library your code needs. A **configuration** is a named list of dependencies for one purpose, such as "what the tests need at run time". A **repository** is a server that stores libraries; Maven Central is the big public one.

## Walkthrough of this repository's use

### `settings.gradle.kts`

```kotlin
// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "seatwave-network"
```

- Line 1 is a comment: `//` starts a comment in Kotlin. It says that one place decides where libraries come from.
- `dependencyResolutionManagement { ... }` is a block. A block is a name followed by braces; everything inside configures that one topic. This block configures how Gradle **resolves** dependencies, which means finding the exact file for each library.
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` makes the build fail if `build.gradle.kts` ever adds a repository of its own. All repositories must be declared here. This stops a project from quietly pulling libraries from an unreviewed server.
- `repositories { mavenCentral() }` lists the only allowed repository: Maven Central.
- `rootProject.name = "seatwave-network"` names the project. The name appears in reports, for example "Rule violated for bundle seatwave-network".

### `build.gradle.kts`, the imports

```kotlin
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.inject.Inject
```

A Kotlin script may start with `import` lines. An import makes a class from another package usable by its short name. These eight are used by the certificate task (`keytool-test-certificates.md`): `ByteArrayOutputStream` collects the text `keytool` prints, `Files` and `PosixFilePermissions` make the password file readable by its owner only, `SecureRandom` makes the random password, `LocalDate` and `ZoneOffset` give today's date in UTC, `Base64` turns random bytes into printable text, and `javax.inject.Inject` marks the one value that Gradle must supply to the task class.

### The plugins block

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}
```

- `plugins { ... }` lists the plugins to switch on.
- `java` adds the Java tasks: `compileJava`, `test`, `jar` and the `check` task.
- `jacoco` adds the coverage tool (`jacoco.md`).
- `alias(libs.plugins.spotless)` switches on the Spotless formatter plugin. `alias` means "use the plugin described in the version catalog". `libs.plugins.spotless` points at the `spotless` line in the `[plugins]` section of `gradle/libs.versions.toml`, which holds the plugin id and its version (`toml-version-catalog.md`).

### Project coordinates

```kotlin
group = "io.plainround"
version = "1.1.0"
```

`group` and `version` are the project's own identity in the Maven style: a group, a name (from `settings.gradle.kts`) and a version. They would matter if the project were published as a library. Here they only label the build.

### The Java toolchain

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A **toolchain** is the exact JDK (Java Development Kit) that builds the code. `languageVersion = JavaLanguageVersion.of(25)` says "use Java 25". Gradle looks for an installed JDK 25 on the machine and uses it for compiling and for running tests, even if the JDK that started Gradle is different. This build does not configure an automatic download of a JDK, so a JDK 25 must be installed (the README names Eclipse Temurin 25.0.2). The same toolchain also finds the `keytool` program that makes the test certificates (line 144 of the build file). The `java { toolchain { ... } }` nesting is a block inside a block: `java` is the extension that the `java` plugin added, and `toolchain` is one of its parts.

### Dependencies and configurations

```kotlin
dependencies {
    implementation(libs.result4j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

- `implementation(libs.result4j)` puts the result4j library on the classpath of the main code, both for compiling and for running. A **classpath** is the list of jar files and folders the JVM searches for classes. `libs.result4j` is a catalog entry: it expands to `com.github.sviperll:result4j:1.2.0`. The settings readers use it so that a bad settings file is a value, not an exception.
- This is the only library of the main code. The servers, the TLS, the HTTP client and the delay link are all the JDK's own classes, so there is no second `implementation` line.
- A name like `implementation`, `testImplementation` or `testRuntimeOnly` is a configuration. The `test` prefix means "only for the test code". `Implementation` means "needed to compile and run". `RuntimeOnly` means "needed only when running, not when compiling".
- `testImplementation(platform(libs.junit.bom))` brings in a **BOM**, a bill of materials. A BOM is a small published file that lists the matching versions of many libraries of one family. `platform(...)` tells Gradle "use this list to decide versions". The JUnit family has several libraries (jupiter api, engine, params, platform launcher). The BOM says all of them are 6.1.3, so the catalog does not need a version for each.
- `testImplementation(libs.junit.jupiter)` adds the JUnit Jupiter bundle: the annotations and the engine that finds and runs the tests. It has no version in the catalog; the BOM above supplies 6.1.3. `./gradlew dependencies` prints `org.junit.jupiter:junit-jupiter -> 6.1.3`, where the arrow means "the version was chosen by something else" (`junit.md`).
- `testImplementation(libs.assertj.core)` adds AssertJ, the readable assertions (`assertj.md`).
- `testRuntimeOnly(libs.junit.platform.launcher)` adds the JUnit Platform launcher, the piece Gradle calls to start the tests. Gradle 9 expects the project to declare it, and it is needed only when tests run, so it is `testRuntimeOnly`. Its version also comes from the BOM.

There is no Awaitility and no Instancio here: no test imports them (I searched the test sources). The tests wait for network events with sockets and timeouts, and build their inputs by hand, so `awaitility.md` and `instancio.md` do not exist for this repository.

### Dependency locking

```kotlin
// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}
```

Libraries bring other libraries (their **transitive dependencies**). `lockAllConfigurations()` makes Gradle write the exact version of every library of every configuration into `gradle.lockfile`, and read that file on every later build. If the resolved versions would differ from the lock, the build fails. The whole story is in `dependency-locking.md`.

### The Java compiler flags

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

- `tasks.withType<JavaCompile>().configureEach { ... }` means: for every task of the type `JavaCompile` (there is one for main code and one for test code), run this block. `<JavaCompile>` is a Kotlin type argument, the type to select. `configureEach` applies the block to each one lazily, only when needed.
- `options.encoding = "UTF-8"` makes the compiler read source files as UTF-8 text, so the result does not depend on the machine's default.
- `options.release = 25` makes the compiler accept only Java 25 language features and use only Java 25 library classes.
- The comment on line 42 gives the reason for the next line: "Every warning is an error: a warning that is allowed to stay is a defect nobody owns."
- `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))` passes two flags to `javac`, the Java compiler. `-Xlint:all` turns on every optional warning (raw types, unchecked casts, an auto-closeable resource that is never used, missing serial ids and more). `-Werror` turns each warning into an error, so the build stops. The flag also applies to the test code.
- I ran an experiment in a scratch copy: a new class with the raw types `java.util.List` and `java.util.ArrayList` made `compileJava` fail. The output had `warning: [rawtypes] found raw type: List`, then `error: warnings found and -Werror specified`, `1 error` and `2 warnings`.

### Spotless and the formatter

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

Spotless is a Gradle plugin that checks and rewrites source files to one layout (`spotless-google-java-format.md`).
- `spotless { java { ... } }` configures Spotless for Java files.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` chooses the formatter. `libs.versions.googleJavaFormat` is the version entry `googleJavaFormat = "2.0.0"` from the catalog, and `.get()` turns it into the text `2.0.0`.
- `removeUnusedImports()` deletes `import` lines the file never uses.
- `endWithNewline()` makes every file end with one line break.
- Spotless adds the tasks `spotlessCheck` (fails if a file would change) and `spotlessApply` (changes the files). The `java` plugin's `check` task depends on `spotlessCheck`, which is how formatting becomes part of the gate.

### JaCoCo, the coverage tool

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`jacoco { toolVersion = ... }` fixes the JaCoCo version to the catalog entry `jacoco = "0.8.16"`. Without it, Gradle would pick a default version. `jacoco.md` explains what the tool measures.

### The certificate task

The next 90 lines of the build file are one Gradle task of its own: a Kotlin class `TestCertificates` (lines 58 to 139) and the lines that register and configure it (lines 141 to 148). They run `keytool`, the JDK's program for keys and certificates, to make a test certificate authority and three server certificates in `build/tls`. `keytool-test-certificates.md` quotes every line of that class and explains the certificate vocabulary first. Here is the Gradle side in short.

```kotlin
val testCertificates = tasks.register<TestCertificates>("testCertificates") {
    group = "verification"
    description = "Makes the throwaway TEST certificate authority and server certificates in build/tls."
    keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }
    day = LocalDate.now(ZoneOffset.UTC).toString()
    outputDirectory = layout.buildDirectory.dir("tls")
}
```

- `tasks.register<TestCertificates>("testCertificates") { ... }` creates a task of the class `TestCertificates` and names it `testCertificates`. That name is what you type: `./gradlew testCertificates`.
- `group = "verification"` and `description = ...` file the task in the `Verification tasks` list that `./gradlew tasks --group verification` prints, with the text `testCertificates - Makes the throwaway TEST certificate authority and server certificates in build/tls.`
- `keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }.map { ... }` finds the Java 25 toolchain, and `.map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }` turns it into the full path of the `keytool` program inside that JDK. So the certificates are made by the same JDK that compiles the code. `.map` keeps the value lazy: Gradle finds the JDK only when the task needs it.
- `day = LocalDate.now(ZoneOffset.UTC).toString()` is today's date in UTC, such as `2026-11-10`. The task never reads it. It is declared as an **input** (`@get:Input` in the class), and Gradle decides whether a task is up to date by comparing its inputs with the last run. A new day is a new input, so the certificates are made again and a reader never uses certificates that are weeks old.
- `outputDirectory = layout.buildDirectory.dir("tls")` is the **output**: the folder `build/tls`. If it is missing or changed, the task runs again.

### The test task

```kotlin
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
```

- `tasks.test { ... }` configures the existing task named `test`.
- `useJUnitPlatform()` tells it to run tests through the JUnit Platform. Without it Gradle would look for older JUnit 4 tests and find none.
- `dependsOn(testCertificates)` makes `test` wait for the certificate task. This is how a test never starts before `build/tls` exists.
- `systemProperty("seatwave.tls.directory", layout.buildDirectory.dir("tls").get().asFile.absolutePath)` is how the tests find the certificates. A **system property** is a named text value that Gradle gives to the test JVM, like a variable that every class can read. The comment above it says: "The TLS tests read the generated test certificates from here." The test helper `TestTls.directory()` reads it with `System.getProperty("seatwave.tls.directory")` (`tests-tls-and-timing-walkthrough.md`). The value is an absolute path, such as the `build/tls` folder of the checkout, so it does not matter which folder the test JVM starts in.
- `testLogging { events("passed", "failed", "skipped") }` prints one line per test result while the task runs, for example `CurlLineTest > turnsCumulativeReadingsIntoFivePhases() PASSED`. Without it, only failures show.
- `finalizedBy(tasks.jacocoTestReport)` means: after `test` finishes, always run `jacocoTestReport`, even if tests failed. That creates the coverage report in `build/reports/jacoco/test/html`.

There is no `maxParallelForks` and no `forkEvery`, so Gradle runs all test classes in one test JVM, one test after another. The timing tests measure real elapsed time, and two tests running side by side would disturb each other.

### The XML coverage report

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

`tasks.jacocoTestReport { ... }` configures the task that writes the coverage report. `reports { xml.required = true }` switches on the **XML report**. By default the task writes only the HTML report, which people read in a browser. XML is a text format made of tags such as `<counter type="LINE" missed="158" covered="1034"/>`; a program can read it, so the exact numbers can be copied into the README without counting by eye. The file lands in `build/reports/jacoco/test/jacocoTestReport.xml`.

### The coverage rule wired into `check`

```kotlin
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
```

- `tasks.jacocoTestCoverageVerification` is the task that checks coverage against a rule. `dependsOn(tasks.test)` makes sure the tests have run, so there is coverage data to read.
- `violationRules { rule { limit { ... } } }` is the rule. A **limit** names a counter, here `counter = "LINE"`, and a bound. `minimum = "0.80".toBigDecimal()` means the covered share of lines must be at least 0.80. `.toBigDecimal()` turns the text into an exact decimal number, which the plugin requires. The comment explains that the untested remainder is the command-line entry points.
- `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` adds the coverage check to `check`. By default `check` does not run it, so this line is what makes the floor a real gate. I proved it in a scratch copy: with the minimum raised to 0.95 the build failed with `Rule violated for bundle seatwave-network: lines covered ratio is 0.86, but expected minimum is 0.95`.
- `check` is the standard "is this project healthy" task. It runs the tests, `spotlessCheck` and the coverage verification. No `JavaExec` task of the next section is a dependency of it.

### The five program tasks

```kotlin
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
```

- The two comment lines at the top say what the block is for: "One command per example: each entry point gets its own task, so a reader never assembles a classpath." and "The measuring runs live here, outside the test task, so ./gradlew check stays fast."
- `val entryPoints = mapOf(...)` creates a read-only map in Kotlin: each pair is a task name and the full name of a class that has a `main` method (an **entry point**, the place where a Java program starts). `"a" to "b"` builds one pair. The five pairs are `serve` (`ServeCli`, the HTTP, HTTPS and distant servers), `timing` (`TimingCli`, the Java timing client), `tlsFailures` (`TlsFailuresCli`), `report` (`ReportCli`, the per-phase table) and `pageModel` (`PageModelCli`, Lab 3).
- `entryPoints.forEach { (taskName, entryPoint) -> ... }` runs the block once per pair. The round brackets `(taskName, entryPoint)` take the pair apart into its two parts.
- `tasks.register<JavaExec>(taskName) { ... }` creates a new task of the type `JavaExec`, which runs a Java program. `register` creates it lazily.
- `group = "application"` files the task in the `application` group in the `./gradlew tasks` list. `description = "Runs $entryPoint."` is the text shown next to it; `$entryPoint` inserts the variable into the text.
- `dependsOn(testCertificates)` makes every program task run the certificate task first. That is why the README says "every run task and the tests call it first": the servers and the clients need the keystores and the CA file in `build/tls`.
- `classpath = sourceSets.main.get().runtimeClasspath` gives the program the compiled main classes plus the libraries it needs (result4j). A **source set** is a named group of source folders; `main` is `src/main/java`. Because `JavaExec` depends on the classpath, Gradle compiles the code first.
- `mainClass = entryPoint` names the class to start.
- The comment "Paths in --args are read from the project folder, wherever Gradle was started." and `workingDir = rootDir`: a **working directory** is the folder a program treats as "here": a relative path such as `config/timing.properties` is looked for from it. `rootDir` is the folder that holds `settings.gradle.kts`, the project folder. In this one-project build it matches the default, so the line makes the behavior explicit.

**Why the measuring runs are their own tasks and not part of `check`.** A timing run takes real time and its numbers depend on the machine, so a test could fail on a busy laptop without any bug. A table of medians is not a pass-or-fail check anyway: a person reads it. So the tests check that the machinery is right (the parser, the invariant, the server, the link, the model) and finish in seconds, and the five tasks make the real measurements when asked. The cost: the `main` methods are not covered by tests, which is why the coverage floor is 0.80 and not higher (`jacoco.md`).

### The commands you type

- `./gradlew check` runs the full gate. `./` means "the file `gradlew` in this folder".
- `./gradlew spotlessApply` rewrites source files into the formatter's layout. Run it before `check` if `spotlessCheck` complains.
- `-q` (`--quiet`) hides Gradle's own messages, so only the program's output shows.
- `--args='...'` passes the text after it to the program's `main` method as arguments. Gradle splits the text at spaces into separate arguments. `./gradlew -q timing --args='config/timing.properties'` therefore gives `TimingCli` one argument: the settings file.
- `--offline` makes Gradle use only what is in its cache and touch no network. I used it for every command in these notes.
- When a program calls `System.exit` with a non-zero value, Gradle reports `Execution failed for task ':report'` (the name of the task) and exits with 1 itself.

## Try it yourself

All commands below ran in a scratch copy of this repository on 10 November 2026 (Temurin 25.0.2, Gradle 9.9.1).

1. List the tasks that run the programs:

```
./gradlew --offline tasks --group application
```

   The list starts with `Application tasks` and has these five lines, in alphabetical order: `pageModel - Runs io.plainround.seatwave.network.model.PageModelCli.`, `report - Runs io.plainround.seatwave.network.report.ReportCli.`, `serve - Runs io.plainround.seatwave.network.server.ServeCli.`, `timing - Runs io.plainround.seatwave.network.timing.TimingCli.` and `tlsFailures - Runs io.plainround.seatwave.network.tls.TlsFailuresCli.`

2. Run the whole gate:

```
./gradlew --offline clean check
```

   It prints one `PASSED` line per test (62 in all), then `> Task :jacocoTestCoverageVerification`, `> Task :spotlessJavaCheck` and ends with `BUILD SUCCESSFUL in 5s` and `9 actionable tasks: 9 executed`.

3. Make only the certificates, twice:

```
./gradlew --offline testCertificates
./gradlew --offline testCertificates
```

   The first prints `> Task :testCertificates`, `BUILD SUCCESSFUL in 2s` and `1 actionable task: 1 executed`. The second prints `> Task :testCertificates UP-TO-DATE` and `1 actionable task: 1 up-to-date`. (Do not open the files in `build/tls`: they hold private keys.)

4. See the version Gradle chose for each library:

```
./gradlew --offline -q dependencies --configuration testRuntimeClasspath | head -12
```

   The tree starts with `+--- com.github.sviperll:result4j:1.2.0` and `+--- org.junit:junit-bom:6.1.3`, followed by the members of the BOM marked `(c)`, and shows `org.junit.jupiter:junit-jupiter -> 6.1.3`.

5. Break the coverage floor on purpose, in a scratch copy only. Change `"0.80"` to `"0.95"` in `build.gradle.kts` and run `./gradlew --offline check -q`. The output contains `Rule violated for bundle seatwave-network: lines covered ratio is 0.86, but expected minimum is 0.95` and `Execution failed for task ':jacocoTestCoverageVerification'`, and `BUILD FAILED`.

## Mistakes people make

- **Running `gradle` instead of `./gradlew`.** The installed Gradle may be a different version. The wrapper always starts 9.9.1 (`gradle-wrapper.md`).
- **Editing `gradle.lockfile` by hand.** The file says manual edits can break the build. Regenerate it (`dependency-locking.md`).
- **Adding a repository in `build.gradle.kts`.** `FAIL_ON_PROJECT_REPOS` in the settings file stops it on purpose.
- **Ignoring a compiler warning.** `-Werror` makes that impossible here.
- **Thinking `test` is enough.** `./gradlew test` skips the formatter check and the coverage floor. Only `check` runs all three.
- **Running a test class from an editor without Gradle.** The TLS tests need the system property `seatwave.tls.directory`; only the Gradle `test` task sets it, and only after `testCertificates` has made the files.
- **Looking for the XML report in the wrong place.** It is `build/reports/jacoco/test/jacocoTestReport.xml`, written only because of the `xml.required = true` line.
- **Committing anything from `build/tls`.** The folder is ignored on purpose (`git.md`).
- **Forgetting that the build file runs.** It is a program. Typing mistakes show up as Kotlin errors, not as friendly messages.

## Check yourself

1. *What is a task?* One named unit of work, such as `compileJava`, `test` or `check`. Gradle runs a task only after the tasks it depends on.
2. *What does `testImplementation(platform(libs.junit.bom))` do?* It loads a list of matching versions for the JUnit family, so the individual JUnit libraries need no version of their own.
3. *Why does `-Werror` matter?* It turns every compiler warning into an error, so no warning can stay unnoticed.
4. *Which line makes the coverage floor part of `check`?* `dependsOn(tasks.jacocoTestCoverageVerification)` inside `tasks.check`.
5. *How do the tests find the certificates?* `tasks.test` depends on `testCertificates` and passes the folder `build/tls` as the system property `seatwave.tls.directory`.

## Further reading

- Gradle 9.9.1 user guide: <https://docs.gradle.org/9.9.1/userguide/userguide.html>
- Build file basics: <https://docs.gradle.org/9.9.1/userguide/part1_gradle_init.html>
- Java toolchains: <https://docs.gradle.org/9.9.1/userguide/toolchains.html>
- Kotlin DSL primer: <https://docs.gradle.org/9.9.1/userguide/kotlin_dsl.html>
- Custom task classes and up-to-date checks: <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The Java plugin and its `check` task: <https://docs.gradle.org/9.9.1/userguide/java_plugin.html>
- The JaCoCo plugin and its reports: <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
