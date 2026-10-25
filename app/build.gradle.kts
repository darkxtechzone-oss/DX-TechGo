plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.dxtech.go"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dxtech.go"
        minSdk = 24; targetSdk = 34
        val vc = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        val gh = (project.findProperty("ghRepo") as String?) ?: ""
        versionCode = vc
        versionName = "3.$vc"
        buildConfigField("String", "GH_REPO", "\"$gh\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("dx") {
            storeFile = file("dxtech.p12")
            storePassword = "dxtechgo"
            keyAlias = "dxtech"
            keyPassword = "dxtechgo"
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("dx") } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core:1.12.0")
}
