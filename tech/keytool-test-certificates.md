# Test certificates with `keytool`

## What it is

When you open `https://something` your program must answer two questions before it trusts the server: "Is this really the server I asked for?" and "Can anyone else read our messages?" **TLS** (Transport Layer Security) is the protocol that answers both. This file explains the vocabulary of TLS from zero, then the tool this repository uses to make test material for it.

- A **private key** is a secret number that only its owner knows. Whatever it signs, only it could have signed. It must never be shared, printed or committed to Git.
- A **public key** is the matching number that anyone may know. It can check a signature made with the private key, but it cannot make one.
- A **certificate** is a small file that says "this public key belongs to this name", with a start date, an end date and a signature. The name can be a host name (`localhost`) or an address (`127.0.0.1`), listed in a field called the **subject alternative name** (SAN).
- A **certificate authority** (CA) is a party that signs certificates. A client that trusts the CA trusts every certificate the CA signed, as long as the dates and the name fit. The CA's own certificate says it is a CA (`CA:true`) and is signed by itself.
- A **chain** is a server's certificate followed by the certificate of the CA that signed it. The client follows the chain until it reaches a CA it already trusts.
- A **key store** is a file that holds private keys together with their certificate chains. A server loads one to prove who it is. Here the format is **PKCS12**, a standard container; the files end in `.p12`. A key store is protected by a password.
- A **trust store** is the list of CA certificates a client trusts. A normal Java program trusts the public CAs that ship with the JDK. This project's client trusts one CA only, the test CA.
- **PEM** is a text format for a certificate: base64 text between a `BEGIN CERTIFICATE` line and an `END CERTIFICATE` line. A `.pem` file of a certificate is public information.
- **`keytool`** is the command-line program in every JDK that makes and inspects keys, certificates and key stores.

**EC P-256** means elliptic-curve keys on the curve named `secp256r1`. They are short, fast and supported by every current TLS client.

## Why this repository uses it

The lesson needs a real TLS handshake on the loopback address, and it needs TLS to fail in three ways: an expired certificate, a certificate for another name, and a client that is too old (`tests-tls-and-timing-walkthrough.md`). Public sites cannot be told to expire on request. So the build makes its own test CA and three server certificates every time. The README says: "These are throwaway test certificates for this lab. Never import the CA into a browser or an operating system."

## Where it appears

| Path | What it does with the certificates |
| --- | --- |
| `build.gradle.kts` | The Kotlin class `TestCertificates` (lines 58 to 139), its registration (lines 141 to 148), and the lines that make `test` and every program task wait for it. |
| `build/tls/` | The output: `seatwave-test-ca.p12`, `seatwave-test-ca.pem`, `seatwave-valid.p12`, `seatwave-expired.p12`, `seatwave-wrong-name.p12` and `store.password`. Created on your machine; never in Git. |
| `.gitignore` | Keeps every key store, key and password file out of Git (`git.md`). |
| `config/targets-seatwave.txt` | Gives `curl` the CA file `build/tls/seatwave-test-ca.pem` (`bash-curl-timings.md`). |
| `src/test/java/io/plainround/seatwave/network/tls/TestTls.java` | Reads the folder and builds the server and client contexts (`tests-tls-and-timing-walkthrough.md`). |

## How it works underneath

1. Before the tests, and before each of the five programs, Gradle runs the task `testCertificates`.
2. The task deletes `build/tls`, makes it again, and writes a random password into `store.password`.
3. It starts the `keytool` program of the Java 25 toolchain several times. Each call is a separate operating system process (a running program) and reports its result as an **exit status**: 0 means success, anything else means failure.
4. First it makes the CA: a key pair and a self-signed CA certificate, inside `seatwave-test-ca.p12`.
5. It exports the CA's certificate, and only the certificate, to `seatwave-test-ca.pem`. Clients use this file as their trust store.
6. For each of the three servers it makes a key pair, signs the certificate with the CA's key, and copies that one key with its chain into its own key store (`seatwave-valid.p12`, and one more file each for the expired and the wrong-name certificate).
7. At run time a server loads one of those key stores. A client builds an in-memory trust store from the `.pem` file (`TlsMaterial.clientContext`). During the TLS handshake the server sends its chain, and the client checks the signature, the dates and the name.

On this machine the files looked like this (names, permissions and sizes only; I did not open them):

```
-rw-r--r--@ 1736 seatwave-expired.p12
-rw-r--r--@ 5405 seatwave-test-ca.p12
-rw-r--r--@ 656 seatwave-test-ca.pem
-rw-r--r--@ 1736 seatwave-valid.p12
-rw-r--r--@ 1736 seatwave-wrong-name.p12
-rw-------@ 24 store.password
```

Only `store.password` is limited to its owner (`rw-------`). The CA's own store (5,405 bytes) is bigger than the others because `keytool -genkeypair -signer` writes each server key into the store that it signs with. The store of each server holds one key and the two-certificate chain.

## Walkthrough of this repository's use

### The comment above the class

```kotlin
/**
 * Makes throwaway TEST certificates with the JDK's keytool: one test certificate authority (CA)
 * and three server certificates it signs (valid, expired and issued for the wrong name).
 *
 * Everything lands in build/tls, which git ignores, so no key or keystore is ever committed. The
 * keystore password is random per run and written to a file next to the keystores, so no password
 * appears in code, configuration or a process listing.
 */
```

This is a documentation comment (it starts with `/**`). It says what the class does: it makes one test CA and three server certificates, everything goes to `build/tls`, which Git ignores, and the key store password is random for each run and written to a file, so "no password appears in code, configuration or a process listing". A **process listing** (the `ps` command) shows the arguments of running programs. If the password were passed on the command line, any user of the machine could read it there. The `-storepass:file` option of `keytool` reads the password from a file instead.

### The class and its three declared values

```kotlin
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

```

- `abstract class TestCertificates : DefaultTask()` declares a new kind of Gradle task. `DefaultTask` is Gradle's base class for tasks. `abstract` is required for Gradle task classes: Gradle writes the missing parts of the class itself.
- `@get:Inject abstract val execOperations: ExecOperations` asks Gradle to hand the task an object that can start operating system programs. `@get:Inject` is the label that means "Gradle supplies this". `ExecOperations` is Gradle's supported way to start `keytool`.
- `@get:Input abstract val keytool: Property<String>` is the path to the `keytool` program. A `Property<String>` is a text value that Gradle can fill in later. `@get:Input` marks it as something that decides whether the task is up to date.
- The two comment lines and `@get:Input abstract val day: Property<String>` explain the date input: "The date is an input, so the certificates are made again on a new day and never expire while a reader is still using a build folder from last month." The task does not read `day`. A changed input is enough to make Gradle run the task again.
- `@get:OutputDirectory abstract val outputDirectory: DirectoryProperty` is the folder the task writes to. Gradle watches it: if someone deletes it, the task runs again.

### The start of `generate`: the password file

```kotlin
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
```

- `@TaskAction fun generate()` marks the function Gradle calls when the task runs. A **function** is a named block of steps; `fun` is how Kotlin declares one.
- `val directory = outputDirectory.get().asFile` turns the output property into an ordinary folder object. `val` means a value that is never changed.
- `directory.deleteRecursively()` removes the folder with everything inside, and `directory.mkdirs()` makes it again, empty. So no certificate of an older run can stay behind.
- `val passwordBytes = ByteArray(18)` makes 18 empty bytes, and `SecureRandom().nextBytes(passwordBytes)` fills them with random bytes from the operating system's secure random source. 18 bytes are 144 random bits.
- `Base64.getEncoder().encodeToString(passwordBytes)` writes those bytes as 24 printable characters (base64 turns every 3 bytes into 4 letters, digits or signs). That is why `store.password` is 24 bytes long in the listing above. `passwordFile.writeText(..., Charsets.UTF_8)` saves it.
- `Files.setPosixFilePermissions(passwordFile.toPath(), PosixFilePermissions.fromString("rw-------"))` limits the file to its owner. A **permission string** has three groups of three letters for owner, group and others: `r` read, `w` write, `-` not allowed. `rw-------` lets the owner read and write and nobody else do anything.
- `val password = passwordFile.absolutePath` stores the **path** of that file, not the password. Every `keytool` call below receives this path.
- `val caStore = directory.resolve("seatwave-test-ca.p12").absolutePath` is the path of the CA's key store.

### The key type

```kotlin
        // EC P-256 keys: the curve every current TLS client supports, and a fast handshake.
        val ecKey = listOf("-keyalg", "EC", "-groupname", "secp256r1")
```

The comment says: "EC P-256 keys: the curve every current TLS client supports, and a fast handshake." The list `ecKey` holds the two `keytool` options that are repeated: `-keyalg EC` (elliptic-curve keys) and `-groupname secp256r1` (the curve).

### The CA

```kotlin
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storetype", "PKCS12", "-storepass:file", password,
                "-alias", "ca", "-dname", "CN=SeatWave TEST CA - never trust outside this lab", "-validity", "30",
                "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign") + ecKey,
        )
```

`keytool(listOf(...) + ecKey)` calls the helper function below with a list of arguments. The `+ ecKey` adds the two key options at the end. The options mean:

| Option | Meaning |
| --- | --- |
| `-genkeypair` | Make a new private and public key and a certificate for it. |
| `-keystore caStore` | Put it in this key store file; the file is made if it does not exist. |
| `-storetype PKCS12` | The file format of the key store. |
| `-storepass:file password` | The store password is in the file at this path. |
| `-alias ca` | The name of the entry inside the store. |
| `-dname "CN=SeatWave TEST CA - never trust outside this lab"` | The **distinguished name**: who the certificate is for. `CN` is the common name. The text is a warning to anyone who sees it. |
| `-validity 30` | The certificate is valid for 30 days from now. |
| `-ext bc:c=ca:true` | An **extension**: basic constraints, `critical`, `CA:true`. It says this certificate may sign other certificates. |
| `-ext ku:c=keyCertSign` | An extension: key usage, critical, only `keyCertSign` (signing certificates). |

Because no `-signer` is given, the certificate is **self-signed**: its issuer is itself. That is how a root CA looks.

### Exporting the CA certificate

```kotlin
        keytool(
            listOf("-exportcert", "-rfc", "-keystore", caStore, "-storepass:file", password, "-alias", "ca",
                "-file", directory.resolve("seatwave-test-ca.pem").absolutePath),
        )
```

`-exportcert` writes a certificate out of a store. `-rfc` asks for the PEM text form instead of binary. `-alias ca` picks the entry. `-file ...seatwave-test-ca.pem` is the target. This copies the **certificate only**. A certificate holds no secret, which is why the `.pem` can be handed to `curl` and to `openssl` (`curl-and-openssl.md`).

### The three server certificates

```kotlin
        // Three server certificates: the good one, one that expired two days ago, and one whose only
        // name is seatwave.test, so a client that connects to 127.0.0.1 must refuse it.
        val servers = mapOf(
            "valid" to listOf("-validity", "30", "-ext", "san=dns:localhost,ip:127.0.0.1"),
            "expired" to listOf("-startdate", "-3d", "-validity", "1", "-ext", "san=dns:localhost,ip:127.0.0.1"),
            "wrong-name" to listOf("-validity", "30", "-ext", "san=dns:seatwave.test"),
        )
```

The comment: "Three server certificates: the good one, one that expired two days ago, and one whose only name is seatwave.test, so a client that connects to 127.0.0.1 must refuse it."

- `val servers = mapOf(...)` is a Kotlin map: a name and a list of options for each server.
- `"valid"` gets `-validity 30` and `-ext san=dns:localhost,ip:127.0.0.1`. The `san` extension lists the names the certificate is good for: the host name `localhost` and the address `127.0.0.1`.
- `"expired"` gets `-startdate -3d`, `-validity 1` and the same names. `-startdate -3d` starts the validity three days in the past, and `-validity 1` lasts one day, so the certificate ended two days before it was made. I read the dates back with `keytool -list -v`: the expired certificate was valid from `Sat Nov 07 23:11:27 GMT 2026` until `Sun Nov 08 23:11:27 GMT 2026`, and the valid one from `Tue Nov 10 23:11:26 GMT 2026` until `Thu Dec 10 23:11:26 GMT 2026`.
- `"wrong-name"` gets 30 days but only `san=dns:seatwave.test`. It has no address `127.0.0.1`, so a client that connects to `127.0.0.1` finds no matching name.

### Signing each one and giving it its own store

```kotlin
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
```

- `servers.forEach { (name, options) -> ... }` runs the block once for each entry: `name` is `valid`, `expired` or `wrong-name`; `options` is its list.
- The first `keytool` call makes a key pair in the CA's store under the alias of the name. `-dname "CN=SeatWave TEST server ($name)"` puts the name in the text, for example `CN=SeatWave TEST server (valid)`. `-signer ca` signs the new certificate with the key that has the alias `ca`, and `-signerkeypass:file password` says the CA key's password is in the file. The `+ ecKey + options` adds the key type and the per-server options.
- The comment: "Each server gets a keystore with only its own key and chain; the CA's key stays behind." The second call, `-importkeystore`, copies one entry from the source store (`-srckeystore`, `-srcstorepass:file`, `-srcalias name`) into a new store (`-destkeystore`, `-deststoretype PKCS12`, `-deststorepass:file`, `-destalias seatwave`). The server's store therefore holds one key under the alias `seatwave`, with its chain of two certificates (the server certificate, then the CA). The server never gets the CA's private key.

I listed the stores with `keytool -list -v` (certificate details only). Each server store has `Entry type: PrivateKeyEntry` and `Certificate chain length: 2`; the valid one has `Owner: CN=SeatWave TEST server (valid)`, `Issuer: CN=SeatWave TEST CA - never trust outside this lab`, `Signature algorithm name: SHA384withECDSA`, `256-bit EC (secp256r1) key` and the names `DNSName: localhost` and `IPAddress: 127.0.0.1`. The wrong-name certificate has only `DNSName: seatwave.test`.

### The helper that runs `keytool`

```kotlin
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
```

- `private fun keytool(arguments: List<String>)` is a function that only this class can call. It takes the list of arguments.
- The comment: "keytool's chatter is kept and shown only if it fails, so a run's output stays readable." `val chatter = ByteArrayOutputStream()` is an in-memory buffer.
- `execOperations.exec { ... }` starts the program. Inside: `commandLine(listOf(keytool.get()) + arguments)` is the program path followed by the arguments; `standardOutput = chatter` and `errorOutput = chatter` send both output streams into the buffer; `isIgnoreExitValue = true` stops Gradle from throwing by itself on a non-zero exit status, because the next lines do it with a better message.
- `if (result.exitValue != 0) { throw GradleException(...) }` fails the build if `keytool` returned anything but 0. The message is `keytool <the first argument> failed: ` followed by what `keytool` printed, for example `keytool -genkeypair failed: ...`.

### The registration

The lines 141 to 148 that register the task are quoted and explained in `gradle.md`, section "The certificate task". In short: `keytool` is the path found from the Java 25 toolchain, `day` is today's date in UTC, and `outputDirectory` is `build/tls`.

### How the tests and the programs find the files

`tasks.test` has `dependsOn(testCertificates)` and passes the folder as the system property `seatwave.tls.directory`. Each `JavaExec` task has `dependsOn(testCertificates)`, and its settings file says `tls.directory=build/tls`, a path read from the project folder. So the same `build/tls` folder serves the tests, the servers and `curl`.

### The `.gitignore` lines that keep it all out of Git

```text
build/
# Test certificates and their keys are generated into build/ on every machine and never committed.
*.p12
*.jks
*.pem
*.key
*.crt
*.password
```

The folder `build/tls` is already inside the ignored `build/`. The six patterns repeat the protection for the same kinds of file anywhere in the tree: PKCS12 stores, Java KeyStore files, PEM files, key files, certificate files and password files (`git.md`).

**Why generate on every machine, and never commit.** A key that is committed is public to everyone who can read the history, forever, even if the file is deleted later. A certificate also expires: a committed 30-day certificate would break on day 31, and the "valid" server would start failing the test that says it must be accepted. And the expired certificate must be expired relative to today, which a committed file cannot be. Generating them takes about two seconds, so each run starts from a fresh authority, a fresh password and dates that fit the day.

## Try it yourself

1. Make the certificates in a scratch copy and list the files (names, permissions and sizes only):

```
./gradlew --offline testCertificates
ls -l build/tls | awk '{print $1,$5,$9}'
```

   Expected: `BUILD SUCCESSFUL`, then the six files shown above, with `store.password` as `-rw-------`.

2. Read the validity dates of the expired certificate (certificate details; no key is shown):

```
keytool -list -v -keystore build/tls/seatwave-expired.p12 -storepass:file build/tls/store.password | grep -E "Owner|Valid from"
```

   Expected: `Owner: CN=SeatWave TEST server (expired)`, a `Valid from` line whose end date is before now, then the same two lines for the CA at the end of the chain.

3. See which names the valid and the wrong-name certificates carry:

```
keytool -list -v -keystore build/tls/seatwave-wrong-name.p12 -storepass:file build/tls/store.password | grep -E "DNSName|IPAddress"
```

   Expected: `DNSName: seatwave.test` and no `IPAddress` line. The same command on `seatwave-valid.p12` prints `DNSName: localhost` and `IPAddress: 127.0.0.1`.

4. Check that nothing here can be committed:

```
git check-ignore -v build/tls/seatwave-valid.p12 build/tls/store.password a.pem
```

   Expected: `.gitignore:2:build/`, `.gitignore:2:build/` and `.gitignore:12:*.pem`.

5. See the first line of the public CA file:

```
head -1 build/tls/seatwave-test-ca.pem
```

   Expected: `-----BEGIN CERTIFICATE-----`. (Never print a `.p12` or `store.password`.)

## Mistakes people make

- **Committing a key store or a key.** It publishes a private key. The `.gitignore` patterns and the `build/` rule stop it.
- **Putting a password on a command line.** Other users of the machine can see it in the process listing. The build uses `-storepass:file`.
- **Trusting the test CA outside the lab.** Anyone who has the CA's private key could make a certificate for any site. The CA's name says "never trust outside this lab".
- **Switching off certificate checks to make a client pass.** The client here trusts the test CA and nothing else, and no code path skips verification (`ARCHITECTURE.md`).
- **Forgetting that dates matter.** A valid certificate is valid for 30 days only. The `day` input makes the task run again each new day.
- **Matching a name that is not in the SAN.** A certificate for `localhost` does not cover `127.0.0.1` unless the address is listed as an `ip:` entry; that is exactly what the wrong-name certificate shows.

## Check yourself

1. *What is the difference between a key store and a trust store?* A key store holds a private key and its chain, so a server can prove who it is. A trust store holds the CA certificates a client trusts.
2. *Which file may be shared safely, and why?* `seatwave-test-ca.pem`: it holds only a certificate, no private key.
3. *How does the build make an expired certificate?* `-startdate -3d` with `-validity 1`, so it ended two days ago.
4. *Why is the password in a file?* So it never appears in code, configuration or a process listing; the file is readable by its owner only.
5. *Why are the certificates made on every machine?* A committed key is public forever, and a committed certificate would expire.

## Further reading

- `keytool` for JDK 25: <https://docs.oracle.com/en/java/javase/25/docs/specs/man/keytool.html>
- Java Secure Socket Extension (JSSE) reference: <https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html>
- Gradle custom tasks (inputs and outputs): <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The `ExecOperations` interface: <https://docs.gradle.org/9.9.1/javadoc/org/gradle/process/ExecOperations.html>
