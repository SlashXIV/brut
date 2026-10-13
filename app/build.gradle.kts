plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version publiée : la SEULE ligne à modifier lors d'une release.
// Règle : correctif = patch, nouveau jalon = mineure, 1.0.0 = cahier des charges complet.
val brutVersion = "0.13.0"

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

    // Langues proposées dans les réglages Android (13+) : français par défaut, anglais.
    androidResources {
        generateLocaleConfig = true
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

/*
 * Aucun texte visible codé en dur dans un composable : tout vit dans strings.xml, où s'écrit un
 * français correct et d'où part la traduction. Détection volontairement simple, par motifs :
 * un littéral contenant une lettre, passé à Text( ou à un paramètre qui porte du texte visible.
 * Le `label` d'une animation Compose n'est jamais affiché : il est ignoré. Un faux positif se
 * lève par `// texte-technique : <raison>` en fin de ligne (la raison est obligatoire).
 * Repris de Dromos, en tâche simple : Brut n'a qu'un module.
 */
val checkHardcodedUiText by tasks.registering {
    val sources = fileTree("src/main/java") { include("**/*.kt") }
    val report = layout.buildDirectory.file("reports/hardcoded-ui-text.txt")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    doLast {
        val visibleText = Regex(
            """(?:\bText\(\s*|\bshowSnackbar\(\s*|\b(contentDescription|title|message|label|hint|""" +
                """actionLabel|confirmLabel|text|placeholder)\s*=\s*)"((?:[^"\\]|\\.)*)"""",
        )
        // Gabarits et échappements (« · », « \n ») retirés : il n'en reste rien à traduire.
        val template = Regex("""\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*|\\u[0-9A-Fa-f]{4}|\\.""")
        val optOut = Regex("""//\s*texte-technique\s*:\s*\S""")
        val animationCall = Regex("""\b(?:animate\w*|\w*Transition|AnimatedVisibility|AnimatedContent|Crossfade)\s*\(""")
        val findings = sources.files.sortedBy { it.path }.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().mapNotNull { (index, line) ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || optOut.containsMatchIn(line)) return@mapNotNull null
                val hardcoded = visibleText.findAll(line).any { match ->
                    val (name, text) = match.destructured
                    // L'appel d'animation est souvent réparti sur plusieurs lignes au-dessus du label.
                    val inAnimation = (maxOf(0, index - 8)..index).any { animationCall.containsMatchIn(lines[it]) }
                    template.replace(text, "").any(Char::isLetter) && !(name == "label" && inAnimation)
                }
                if (hardcoded) "${file.path}:${index + 1}  $code" else null
            }
        }
        report.get().asFile.writeText(findings.joinToString("\n"))
        if (findings.isNotEmpty()) {
            throw GradleException(
                "Texte visible codé en dur (à déplacer dans strings.xml, ou à justifier par " +
                    "« // texte-technique : raison ») :\n" + findings.joinToString("\n"),
            )
        }
    }
}
tasks.named("check") { dependsOn(checkHardcodedUiText) }
