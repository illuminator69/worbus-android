import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

val isTaskRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

val fdroid = System.getenv("FDROID") == "true" || providers.gradleProperty("fdroid")
	.map { it.toBoolean() }
	.getOrElse(false)

extensions.configure<ApplicationExtension> {
	namespace = "app.worbus.androidApp"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	buildFeatures {
		resValues = true
	}

	defaultConfig {
		// Worbus P5: the app's own ID, for good (design §6.1). It installs beside Navic, which keeps
		// its own ID, data and sign-in.
		applicationId = "app.worbus.android"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		// One version for every piece of Worbus. The code keeps counting from 0.4.0's 60. BuildInfo
		// reads versionName from this file for the User-Agent (composeApp/build.gradle.kts).
		versionCode = 61
		versionName = "0.5.0"

		ndk {
			abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
			if (!isTaskRelease) {
				abiFilters.add("x86_64")
			}
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = System.getenv("SIGNING_KEY_ALIAS")
			keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
			storeFile = System.getenv("SIGNING_STORE_FILE")?.let(::File)
			storePassword = System.getenv("SIGNING_STORE_PASSWORD")
		}
	}

	buildTypes {
		val isRelease = true
		val hasReleaseSigning = System.getenv("SIGNING_STORE_PASSWORD")?.isNotEmpty() == true
		// Worbus P5 (design §6.6): the release key lives only on the workstation, so a release built
		// anywhere else is debug-signed. Say so once, so such an APK is never taken for a release one.
		// The real key's certificate is recorded in release-cert.sha256 (build.yml checks it).
		// The line is written by assembleRelease's first action, not while the script configures: a
		// configuration-cache hit skips configuration, and the build log of every such build must carry it.
		// Only the Boolean captured here reaches the action, so nothing of the project is kept for it.
		tasks.matching { it.name == "assembleRelease" }.configureEach {
			val debugSigned = isTaskRelease && !hasReleaseSigning
			doFirst {
				if (debugSigned) logger.warn("Worbus release APK signed with the DEBUG key (SIGNING_* unset): not for distribution")
			}
		}

		getByName("release") {
			isMinifyEnabled = true
			isDebuggable = false
			isProfileable = false
			isJniDebuggable = false
			isShrinkResources = true
			// Upstream leaves an unset SIGNING_* release UNSIGNED. This fork's installs (phone
			// and emulator) are all debug-key signed, so falling back keeps them updatable.
			signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}

		getByName("debug") {
			applicationIdSuffix = ".debug"
			resValue("string", "app_name", "Worbus (Dev)")
		}
	}

	packaging {
		resources {
			excludes += "/okhttp3/**"
			excludes += "/*.properties"
			excludes += "/org/antlr/**"
			excludes += "/com/android/tools/smali/**"
			excludes += "/org/eclipse/jgit/**"
			excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
			excludes += "/org/bouncycastle/**"
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}

		jniLibs {
			keepDebugSymbols.add("**/*.so")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

extensions.configure<ApplicationAndroidComponentsExtension> {
	onVariants { variant ->
		variant.outputs.forEach { output ->
			if (output is VariantOutputImpl) {
				output.outputFileName = if (fdroid) {
					"Worbus.fdroid.apk"
				} else {
					"Worbus.apk"
				}
			}
		}
	}
	onVariants(selector().withBuildType("release")) {
		it.packaging.resources.excludes.apply {
			add("/**/*.version")
			add("/kotlin-tooling-metadata.json")
			add("/DebugProbesKt.bin")
			add("/**/*.kotlin_builtins")
		}
	}
}

dependencies {
	implementation(projects.composeApp)
	implementation(libs.androidx.activity.compose)
	implementation(libs.cmp.material3)
	implementation(libs.koin.android)
	implementation(libs.koin.core)
	// The widgets read the library through AlbumRepository, whose list types are immutable.
	implementation(libs.kotlinx.collections.immutable)
	implementation(libs.bundles.glance)
	implementation(libs.bundles.coil)
	implementation(libs.bundles.media3)
}
