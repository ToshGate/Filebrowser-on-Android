pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FileBrowserAndroid"
include(":app")

// O local.properties (caminho do Android SDK) é específico de cada computador e não vem
// no repositório. Se faltar, é criado aqui a partir das localizações habituais do SDK,
// para o primeiro sync funcionar sem passos manuais. O Android Studio também o corrige
// sozinho se o SDK estiver noutro sítio.
run {
    val localProperties = java.io.File(rootDir, "local.properties")
    if (localProperties.exists()) return@run
    val home = System.getProperty("user.home")
    val sdk = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" }, // Windows
        home?.let { "$it/Library/Android/sdk" },                    // macOS
        home?.let { "$it/Android/Sdk" },                            // Linux
    ).map { java.io.File(it) }.firstOrNull { it.isDirectory } ?: return@run
    localProperties.writeText(
        "# Gerado automaticamente. Não vai para o Git.\n" +
            "sdk.dir=" + sdk.absolutePath.replace('\\', '/') + "\n"
    )
}
