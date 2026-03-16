# engine-carballo

`EngineAdapterModule` implementation wrapping [Carballo](https://github.com/albertoruibal/carballo), a pure Java chess engine (~2100 Elo). Runs on any JVM platform including Android with no native dependencies.

Parameters: `UCI_LimitStrength`, `UCI_Elo` (500-2100), `Hash`, `UseBook`.

## Dependency setup

Carballo is not published on Maven Central or JitPack. You need to build it from source and install to your local Maven repository:

```sh
git clone https://github.com/albertoruibal/carballo.git /tmp/carballo
cd /tmp/carballo
sed -i 's/gradle-7.5.1-bin.zip/gradle-8.5-bin.zip/' gradle/wrapper/gradle-wrapper.properties
./gradlew :core:publishToMavenLocal
```

This installs `com.github.albertoruibal.carballo:carballo-core:1.8` to `~/.m2/repository`. The root `build.gradle.kts` includes `mavenLocal()` to resolve it.
