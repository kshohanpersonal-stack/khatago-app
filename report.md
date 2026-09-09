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

## Probe 2 - unit-test sources compile, kapt tasks disabled

exit=0 lines=74 errors=0

```
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:javaPreCompileDebug FROM-CACHE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:kaptGenerateStubsDebugKotlin SKIPPED
> Task :app:kaptDebugKotlin SKIPPED
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugJavaWithJavac FROM-CACHE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:kaptGenerateStubsDebugUnitTestKotlin SKIPPED
> Task :app:kaptDebugUnitTestKotlin SKIPPED
> Task :app:compileDebugUnitTestKotlin FROM-CACHE
BUILD SUCCESSFUL in 10s
```

## Probe 3 - release variant compiles, kapt tasks disabled

exit=0 lines=68 errors=0

```
> Task :app:preBuild UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:generateReleaseResValues FROM-CACHE
> Task :app:generateReleaseBuildConfig FROM-CACHE
> Task :app:createReleaseCompatibleScreenManifests
> Task :app:generateReleaseResources FROM-CACHE
> Task :app:extractDeepLinksRelease FROM-CACHE
> Task :app:packageReleaseResources FROM-CACHE
> Task :app:parseReleaseLocalResources FROM-CACHE
> Task :app:mapReleaseSourceSetPaths
> Task :app:processReleaseMainManifest FROM-CACHE
> Task :app:processReleaseManifest FROM-CACHE
> Task :app:checkReleaseAarMetadata
> Task :app:processReleaseManifestForPackage FROM-CACHE
> Task :app:mergeReleaseResources FROM-CACHE
> Task :app:processReleaseResources FROM-CACHE
> Task :app:kaptGenerateStubsReleaseKotlin SKIPPED
> Task :app:kaptReleaseKotlin SKIPPED
> Task :app:compileReleaseKotlin FROM-CACHE
BUILD SUCCESSFUL in 9s
```


## status


