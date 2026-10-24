plugins { java }

group = "fr.customtools"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Adapte la version à celle de ton serveur si besoin (voir https://docs.papermc.io)
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.jar { archiveFileName.set("CustomTools.jar") }
