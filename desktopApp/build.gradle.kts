import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":shared"))
}

// androidx.paging's desktop variant pulls in the Android-only paging-runtime;
// replace it with paging-common (same workaround as in :shared).
configurations.configureEach {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "androidx.paging" && requested.name == "paging-runtime") {
                useTarget("${requested.group}:paging-common:${requested.version}")
                because("paging-runtime doesn't support desktop, using paging-common instead")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.apps.adrcotfas.goodtime.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Goodtime"
            packageVersion = "3.2.9"
            description = "Goodtime - Productivity Timer"

            // androidx.datastore (protobuf reflective schemas) and other libs
            // use sun.misc.Unsafe reflectively; jdeps cannot see it, so it must
            // be added to the jlink module set explicitly.
            modules("jdk.unsupported")

            windows {
                dirChooser = true
                menuGroup = "Goodtime"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}
