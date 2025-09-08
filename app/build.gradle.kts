plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") // Google Services plugin for Firebase integration
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

// Leer variables de Supabase desde local.properties
val supabaseUrl = project.findProperty("SUPABASE_URL") as? String ?: ""
val supabaseKey = project.findProperty("SUPABASE_KEY") as? String ?: ""

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }
    namespace = "com.example.tareamov"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tareamov.service"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Exponer variables de Supabase como BuildConfig
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")

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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22") // Updated to match KSP version
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

    // Firebase Firestore and Analytics
    implementation("com.google.firebase:firebase-firestore-ktx:24.11.0")
    implementation("com.google.firebase:firebase-analytics-ktx:21.6.1")

    // Google Drive API and Google Play Services
    implementation("com.google.android.gms:play-services-drive:17.0.0")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    // BCrypt for password hashing
    implementation("at.favre.lib:bcrypt:0.9.0")

    // File conversion libraries
    implementation("org.apache.pdfbox:pdfbox:2.0.29")
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")
}