import java.io.FileInputStream
import java.util.Properties
import org.apache.tools.ant.taskdefs.condition.Os

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }

            create("play") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["uploadKeyAlias"] as String
                keyPassword = keystoreProperties["uploadKeyPassword"] as String
            }
        }
    }

    compileSdk = 36
    buildToolsVersion = "36.0.0"

    namespace = "app.grapheneos.pdfviewer"

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        getByName("debug") {
            resValue("string", "app_name", "PDF Viewer d")
        }

        getByName("release") {
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        create("play") {
            initWith(getByName("release"))
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("play")
            }
        }

    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.google.android.material:material:1.12.0")
}

fun getCommand(command: String, winExt: String = "cmd"): String {
    return if (Os.isFamily(Os.FAMILY_WINDOWS)) "$command.$winExt" else command
}

val npmSetup = tasks.register("npmSetup", Exec::class) {
    workingDir = projectDir
    commandLine(getCommand("npm"), "ci", "--ignore-scripts")
}

val processStatic = tasks.register("processStatic", Exec::class) {
    workingDir = projectDir.parentFile
    dependsOn(npmSetup)
    commandLine(getCommand("node", "exe"), "process_static.js")
}

val cleanStatic = tasks.register("cleanStatic", Delete::class) {
    delete("src/main/assets/viewer", "src/debug/assets/viewer")
}

tasks.preBuild {
    dependsOn(processStatic)
}

tasks.clean {
    dependsOn(cleanStatic)
}
