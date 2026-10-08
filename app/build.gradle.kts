plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version publiée : la SEULE ligne à modifier lors d'une release.
// Règle : correctif = patch, nouveau jalon = mineure, 1.0.0 = cahier des charges complet.
val brutVersion = "0.3.0"

android {
    namespace = "com.gabrielifrim.brut"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gabrielifrim.brut"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        val (major, minor, patch) = brutVersion.split(".").map { it.toInt() }
        versionCode = major * 10000 + minor * 100 + patch
        versionName = brutVersion
    }

    // Signature de publication : jamais dans le dépôt. En local, propriétés Gradle
    // brut.signing.* (~/.gradle/gradle.properties) ; en CI, variables d'environnement.
    // Absentes, le release reste non signé : un clone du dépôt se construit toujours.
    fun signing(key: String): String? =
        providers.gradleProperty("brut.signing.$key").orNull
            ?: providers.environmentVariable("BRUT_SIGNING_${key.uppercase()}").orNull
    val signingValues = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { signing(it) }
    val canSign = signingValues.values.all { it != null }
    if (canSign) {
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("storeFile")!!)
            storePassword = signingValues.getValue("storePassword")
            keyAlias = signingValues.getValue("keyAlias")
            keyPassword = signingValues.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
