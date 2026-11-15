# Building Myelin

Requires JDK 21. The Gradle wrapper downloads the required Gradle version.

These instructions are for developers building from source. Players install
the separate Myelin and NullFrame Core mod JARs; they do not need Gradle or
Maven Local.

Myelin is a standalone project. It uses the published Java library
`com.nullframe:nullframe-core:0.1.1`; Core is not a Gradle subproject and does
not need to live beside Myelin.

Before building Myelin on a new computer, build the matching Core release
separately and publish it to Maven Local. This copies Core's library into
your computer's local Maven repository (normally `~/.m2/repository`), where
Myelin's compiler can find it. It does not upload Core to the internet, run
Core, or bundle it into Myelin.

Running only `build` in Core creates its JAR but does not put it in Maven
Local. Without the required version there, Myelin's dependency resolution
will fail. Run both tasks:

```powershell
# In the NullFrame Core project directory:
.\gradlew.bat build publishToMavenLocal

# In the Myelin project directory:
.\gradlew.bat build
```

On Linux/macOS, use `./gradlew` instead of `.\gradlew.bat` (run
`chmod +x gradlew` if the executable bit was not preserved).

The distributable mod is `build/libs/myelin-0.1.0.jar`. Install NullFrame Core
as a separate mod alongside it; Core is not bundled into Myelin's JAR.

Development runs use `runClient` and `runServer`. The source repository does
not include the internal test suites. The `build` task compiles and packages
the mod. Running the native dependency requires a supported platform and an
AVX2-capable CPU; Java compilation alone does not require executing it.

Both projects can be stored in unrelated directories. Updating Core source
requires rebuilding and republishing Core before rebuilding Myelin.
