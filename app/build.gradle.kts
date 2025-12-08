import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}



// Add this task to clean KSP cache
tasks.register("cleanKspCache") {
    doLast {
        delete(layout.buildDirectory.dir("generated/ksp"))
    }
}

// Make clean task depend on cleanKspCache
tasks.named("clean") {
    dependsOn("cleanKspCache")
}

// Leer variables de Supabase desde local.properties (carga ligera sin java.util.Properties)
val localPropsFile = rootProject.file("local.properties")
val localPropsMap: Map<String, String> = if (localPropsFile.exists()) {
    localPropsFile.readLines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@mapNotNull null
            val (k, v) = trimmed.split("=", limit = 2)
            k.trim() to v.trim()
        }
        .toMap()
} else emptyMap()

// Primary property names: SUPABASE_URL, SUPABASE_KEY
// Backwards-compatible fallbacks: projectid (to build URL), supabaseapi (anon key), service_role
val supabaseUrlProp = (project.findProperty("SUPABASE_URL") as? String)
    ?: localPropsMap["SUPABASE_URL"]
    ?: ""
val supabaseProjectId = (project.findProperty("projectid") as? String)
    ?: localPropsMap["projectid"]
    ?: (project.findProperty("supabase_project_id") as? String)
    ?: localPropsMap["supabase_project_id"]
    ?: ""

val supabaseUrl = if (supabaseUrlProp.isNotBlank()) {
    supabaseUrlProp
} else if (supabaseProjectId.isNotBlank()) {
    "https://${supabaseProjectId}.supabase.co"
} else ""

val supabaseKey = (project.findProperty("SUPABASE_KEY") as? String)
    ?: localPropsMap["SUPABASE_KEY"]
    ?: (project.findProperty("supabaseapi") as? String)
    ?: localPropsMap["supabaseapi"]
    ?: (project.findProperty("service_role") as? String)
    ?: localPropsMap["service_role"]
    ?: ""

// Leer la IP del host (útil para emuladores). Por defecto 10.0.2.2 para Android Emulator
val hostIp = project.findProperty("HOST_IP") as? String ?: localPropsMap["HOST_IP"] ?: "10.0.2.2"

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }
    namespace = "com.example.tareamov"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tareamov.app"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Enable RenderScript for BlurView
        renderscriptTargetApi = 31
        renderscriptSupportModeEnabled = true

        // Provide a single authority string for FileProvider usage
        manifestPlaceholders["fileProviderAuthority"] = "${applicationId}.fileprovider"

    // Exponer variables de Supabase y HOST_IP como BuildConfig
    buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
    buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    buildConfigField("String", "HOST_IP", "\"$hostIp\"")
    
    // DeepSeek API Key para LLM
    val deepseekApiKey = (project.findProperty("DEEPSEEK_API_KEY") as? String) ?: localPropsMap["DEEPSEEK_API_KEY"] ?: ""
    buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
    
    // Cloudflare R2 Storage Configuration
    val r2AccountId = (project.findProperty("R2_ACCOUNT_ID") as? String) ?: localPropsMap["R2_ACCOUNT_ID"] ?: ""
    val r2AccessKeyId = (project.findProperty("R2_ACCESS_KEY_ID") as? String) ?: localPropsMap["R2_ACCESS_KEY_ID"] ?: ""
    val r2SecretAccessKey = (project.findProperty("R2_SECRET_ACCESS_KEY") as? String) ?: localPropsMap["R2_SECRET_ACCESS_KEY"] ?: ""
    val r2BucketName = (project.findProperty("R2_BUCKET_NAME") as? String) ?: localPropsMap["R2_BUCKET_NAME"] ?: "coursev-files"
    // Remove escape characters from R2_ENDPOINT (local.properties escapes : as \:)
    val r2EndpointRaw = (project.findProperty("R2_ENDPOINT") as? String) ?: localPropsMap["R2_ENDPOINT"] ?: ""
    val r2Endpoint = r2EndpointRaw.replace("\\:", ":").replace("\\=", "=")
    
    buildConfigField("String", "R2_ACCOUNT_ID", "\"$r2AccountId\"")
    buildConfigField("String", "R2_ACCESS_KEY_ID", "\"$r2AccessKeyId\"")
    buildConfigField("String", "R2_SECRET_ACCESS_KEY", "\"$r2SecretAccessKey\"")
    buildConfigField("String", "R2_BUCKET_NAME", "\"$r2BucketName\"")
    buildConfigField("String", "R2_ENDPOINT", "\"$r2Endpoint\"")

        // Add Room schema location
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.expandProjection", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Update deprecated packagingOptions to packaging
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/notice.txt", "META-INF/ASL2.0")
        }
    }
}

dependencies {

    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Room components
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Kotlin components
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle components
    val lifecycleVersion = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")

    // UI components
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")


    // CircleImageView for circular profile images
    implementation("de.hdodenhof:circleimageview:3.1.0")

    // Supabase SDK (compatible con Kotlin 1.9.x)
    implementation("io.github.jan-tennert.supabase:supabase-kt:1.4.0")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:compiler:4.16.0")

    // Retrofit para comunicación HTTP con Ollama

    // Retrofit & OkHttp for network calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON processing
    implementation("org.json:json:20230227")


    // Gson para manejo de JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager para tareas en segundo plano
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Add MPAndroidChart dependency
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Note: Google Drive API dependencies removed - using native Android Storage Access Framework
    // which provides access to Google Drive files without requiring Google Play Services authentication

    // BCrypt for password hashing
    implementation("at.favre.lib:bcrypt:0.9.0")

    // File conversion libraries
    // PDFBox-Android - versión compatible con Android (no usa java.awt)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")

    // BlurView for real-time blur effect (glassmorphism)
    implementation("com.github.Dimezis:BlurView:version-2.0.5")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    
    // Firebase Cloud Messaging
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")

    // Credential Manager for modern Google Sign-In
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}

// Apply Google Services plugin only when google-services.json exists in the app module.
// This avoids build failures on machines/environments where the credentials file is intentionally absent.
val googleServicesFile = project.file("google-services.json")
if (googleServicesFile.exists()) {
    // Plugin already applied at the top
} else {
    println("Skipping com.google.gms.google-services plugin because app/google-services.json was not found. If you need Firebase, add the file to app/ or configure local.properties.")
}