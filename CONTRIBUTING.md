Contributor Guide
=================

Prerequisites
-------------
To build and run the plugin, you'll need JDK 17 (e.g., JetBrains Runtime 17 or Microsoft OpenJDK 17).

Build
-----
Use the following Gradle command. It will build a plugin ZIP archive in `build/distributions`.
```console
$ ./gradlew buildPlugin
# On Windows PowerShell: .\gradlew.bat buildPlugin
```

### Run local IDE
To run a test instance of Rider with your plugin, use:
```console
$ ./gradlew runIde
# On Windows PowerShell: .\gradlew.bat runIde
```

Test
----
To run the tests, use:
```console
$ ./gradlew check
# On Windows PowerShell: .\gradlew.bat check
```

Upgrade Rider Version
---------------------
To upgrade the IDE version targeted by the plugin, follow these steps:

1. Update Rider SDK versions in `gradle/libs.versions.toml` (keys: `riderSdk`, and optionally `riderSdkPreview`).
2. Update the `kotlin` version in the `versions` section of `gradle/libs.versions.toml` if needed (see the comment there for the link to the corresponding documentation).
