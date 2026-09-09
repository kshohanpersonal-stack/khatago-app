# KhataGo compile diagnostics for run 34295392126

```
gradle on PATH: /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-8.9/bin/gradle
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)
OpenJDK 64-Bit Server VM Temurin-17.0.20.1+1 (build 17.0.20.1+1, mixed mode, sharing)

------------------------------------------------------------
Gradle 8.9
------------------------------------------------------------

Build time:    2024-07-11 14:37:41 UTC
Revision:      d536ef36a19186ccc596d8817123e5445f30fef8

Kotlin:        1.9.23
Groovy:        3.0.21
Ant:           Apache Ant(TM) version 1.10.13 compiled on January 4 2023
Launcher JVM:  17.0.20.1 (Eclipse Adoptium 17.0.20.1+1)
Daemon JVM:    /usr/lib/jvm/temurin-17-jdk-amd64 (no JDK specified, using current Java home)
OS:            Linux 6.17.0-1022-azure amd64

```

## Probe 1 - compileDebugKotlin, kapt tasks disabled

exit=0 lines=68 errors=0

```
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:generateDebugResValues FROM-CACHE
> Task :app:generateDebugBuildConfig FROM-CACHE
> Task :app:generateDebugResources FROM-CACHE
> Task :app:createDebugCompatibleScreenManifests
> Task :app:packageDebugResources FROM-CACHE
> Task :app:parseDebugLocalResources FROM-CACHE
> Task :app:extractDeepLinksDebug FROM-CACHE
> Task :app:mapDebugSourceSetPaths
> Task :app:checkDebugAarMetadata
> Task :app:processDebugMainManifest FROM-CACHE
> Task :app:processDebugManifest FROM-CACHE
> Task :app:processDebugManifestForPackage FROM-CACHE
> Task :app:mergeDebugResources FROM-CACHE
> Task :app:processDebugResources FROM-CACHE
> Task :app:kaptGenerateStubsDebugKotlin SKIPPED
> Task :app:kaptDebugKotlin SKIPPED
> Task :app:compileDebugKotlin FROM-CACHE
BUILD SUCCESSFUL in 13s
```

