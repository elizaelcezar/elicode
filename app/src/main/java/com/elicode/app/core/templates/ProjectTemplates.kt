package com.elicode.app.core.templates

import com.elicode.app.core.ProjectType

/** A project template offered by the "New project" wizard. */
data class ProjectTemplate(
    val id: String,
    val label: String,
    val description: String,
    val type: ProjectType,
    val files: (appName: String, pkg: String) -> Map<String, String>
)

object Templates {

    val all: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "android-compose",
            label = "Android (Kotlin + Compose)",
            description = "Minimal Compose app. Edit → Build APK → Install on this device.",
            type = ProjectType.ANDROID_GRADLE,
            files = ::androidCompose
        ),
        ProjectTemplate(
            id = "web-static",
            label = "Web (HTML/CSS/JS)",
            description = "Static site with live Preview on localhost.",
            type = ProjectType.STATIC_WEB,
            files = ::webStatic
        ),
        ProjectTemplate(
            id = "node",
            label = "Node.js",
            description = "Express-style server with Preview support.",
            type = ProjectType.NODE,
            files = ::nodeApp
        ),
        ProjectTemplate(
            id = "vite",
            label = "Vite",
            description = "Vite dev server on port 5173 with Preview.",
            type = ProjectType.VITE,
            files = ::viteApp
        ),
        ProjectTemplate(
            id = "python",
            label = "Python",
            description = "Python script project (http.server preview).",
            type = ProjectType.PYTHON,
            files = ::pythonApp
        ),
        ProjectTemplate(
            id = "empty",
            label = "Empty project",
            description = "Blank folder with a README.",
            type = ProjectType.EMPTY,
            files = ::emptyProject
        )
    )

    fun byId(id: String): ProjectTemplate = all.firstOrNull { it.id == id } ?: all.last()

    // ------------------------------------------------------------------
    // Android (Kotlin + Compose). Kept minimal but fully buildable:
    // `gradle assembleDebug` (or `./gradlew` once the wrapper jar is
    // provisioned by the runtime setup) produces app-debug.apk.
    // ------------------------------------------------------------------

    private fun androidCompose(appName: String, pkg: String): Map<String, String> {
        val safePkg = pkg.ifBlank { "com.example.app" }
        val appLabel = appName.ifBlank { "My App" }
        return mapOf(
            "settings.gradle" to
                "pluginManagement {\n    repositories {\n        google()\n        mavenCentral()\n        gradlePluginPortal()\n    }\n}\n" +
                "dependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n" +
                "    repositories {\n        google()\n        mavenCentral()\n    }\n}\n" +
                "rootProject.name = \"$appLabel\"\ninclude(\":app\")\n",
            "build.gradle" to
                "plugins {\n    id(\"com.android.application\") version \"8.5.2\" apply false\n" +
                "    id(\"org.jetbrains.kotlin.android\") version \"1.9.22\" apply false\n}\n",
            "gradle.properties" to
                "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\n" +
                "android.useAndroidX=true\nkotlin.code.style=official\n" +
                "android.nonTransitiveRClass=true\n",
            "gradle/wrapper/gradle-wrapper.properties" to
                "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\n" +
                "networkTimeout=10000\nvalidateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n",
            "gradlew" to
                "#!/bin/sh\n# Gradle wrapper stub: the EliCode runtime setup installs the wrapper jar.\n" +
                "# Falls back to a system-wide gradle when present.\n" +
                "if [ -f \"\\$(dirname \"\\$0\")/gradle/wrapper/gradle-wrapper.jar\" ]; then\n" +
                "  exec java -jar \"\\$(dirname \"\\$0\")/gradle/wrapper/gradle-wrapper.jar\" \"\\$@\"\n" +
                "elif command -v gradle >/dev/null 2>&1; then\n  exec gradle \"\\$@\"\n" +
                "else\n  echo \"Gradle not found. Install it in the EliCode runtime first.\" >&2\n  exit 1\nfi\n",
            "app/build.gradle" to
                "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\n" +
                "android {\n    namespace = \"$safePkg\"\n    compileSdk = 34\n\n" +
                "    defaultConfig {\n        applicationId = \"$safePkg\"\n        minSdk = 26\n        targetSdk = 34\n" +
                "        versionCode = 1\n        versionName = \"1.0\"\n    }\n" +
                "    buildTypes {\n        release {\n            isMinifyEnabled = false\n" +
                "            proguardFiles(getDefaultProguardFile(\"proguard-android-optimize.txt\"), \"proguard-rules.pro\")\n" +
                "        }\n    }\n" +
                "    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n" +
                "        targetCompatibility = JavaVersion.VERSION_17\n    }\n" +
                "    kotlinOptions { jvmTarget = \"17\" }\n" +
                "    composeOptions { kotlinCompilerExtensionVersion = \"1.5.10\" }\n" +
                "    buildFeatures { compose = true }\n}\n\n" +
                "dependencies {\n    val composeBom = platform(\"androidx.compose:compose-bom:2024.02.00\")\n" +
                "    implementation(composeBom)\n" +
                "    implementation(\"androidx.core:core-ktx:1.12.0\")\n" +
                "    implementation(\"androidx.activity:activity-compose:1.8.2\")\n" +
                "    implementation(\"androidx.compose.ui:ui\")\n" +
                "    implementation(\"androidx.compose.material3:material3\")\n}\n",
            "app/proguard-rules.pro" to "# Add project specific ProGuard rules here.\n",
            "app/src/main/AndroidManifest.xml" to
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <application android:label=\"$appLabel\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">\n" +
                "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                "            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n" +
                "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                "            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n",
            "app/src/main/java/${safePkg.replace('.', '/')}/MainActivity.kt" to
                "package $safePkg\n\n" +
                "import android.os.Bundle\nimport androidx.activity.ComponentActivity\n" +
                "import androidx.activity.compose.setContent\n" +
                "import androidx.compose.foundation.layout.*\n" +
                "import androidx.compose.material3.*\nimport androidx.compose.runtime.*\n" +
                "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\n\n" +
                "class MainActivity : ComponentActivity() {\n" +
                "    override fun onCreate(savedInstanceState: Bundle?) {\n" +
                "        super.onCreate(savedInstanceState)\n" +
                "        setContent {\n            MaterialTheme {\n" +
                "                Surface(Modifier.fillMaxSize()) {\n" +
                "                    var name by remember { mutableStateOf(\"EliCode\") }\n" +
                "                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n" +
                "                        Text(\"Hello, \$name!\", style = MaterialTheme.typography.headlineMedium)\n" +
                "                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(\"Your name\") })\n" +
                "                        Text(\"Built with EliCode on Android — no computer needed.\")\n" +
                "                    }\n                }\n            }\n        }\n    }\n}\n",
            "README.md" to "# $appLabel\n\nMinimal Android (Kotlin + Compose) project created by EliCode.\n\nBuild inside the EliCode Linux runtime:\n\n```sh\n./gradlew assembleDebug   # or: gradle assembleDebug\n```\n\nThe APK lands in `app/build/outputs/apk/debug/`.\n"
        )
    }

    private fun webStatic(appName: String, @Suppress("UNUSED_PARAMETER") pkg: String): Map<String, String> = mapOf(
        "index.html" to
            "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\" />\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
            "<title>${appName.ifBlank { "My Site" }}</title>\n<link rel=\"stylesheet\" href=\"style.css\" />\n</head>\n<body>\n" +
            "  <main>\n    <h1 id=\"title\">Hello from EliCode</h1>\n" +
            "    <p>Edit <code>index.html</code>, then refresh the Preview tab.</p>\n" +
            "    <button id=\"btn\">Click me</button>\n  </main>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
        "style.css" to
            ":root { color-scheme: light dark; }\nbody { font-family: system-ui, sans-serif; margin: 0; }\n" +
            "main { max-width: 640px; margin: 8vh auto; padding: 0 20px; }\n" +
            "h1 { font-size: 2.2rem; }\nbutton { padding: 10px 18px; font-size: 1rem; }\n",
        "app.js" to
            "document.getElementById('btn').addEventListener('click', () => {\n" +
            "  document.getElementById('title').textContent = 'Preview works!';\n});\n",
        "README.md" to "# ${appName.ifBlank { "My Site" }}\n\nRun: `python3 -m http.server 8080` then open the Preview tab.\n"
    )

    private fun nodeApp(appName: String, @Suppress("UNUSED_PARAMETER") pkg: String): Map<String, String> = mapOf(
        "package.json" to
            "{\n  \"name\": \"${slug(appName)}\",\n  \"version\": \"1.0.0\",\n" +
            "  \"type\": \"module\",\n  \"scripts\": { \"start\": \"node index.js\", \"dev\": \"node index.js\" },\n" +
            "  \"dependencies\": {}\n}\n",
        "index.js" to
            "import http from 'node:http';\n\nconst port = process.env.PORT || 3000;\n" +
            "http.createServer((req, res) => {\n  res.writeHead(200, { 'content-type': 'text/html' });\n" +
            "  res.end('<h1>Hello from EliCode + Node</h1><p>Edit <code>index.js</code> and restart.</p>');\n" +
            "}).listen(port, '0.0.0.0', () => console.log(`Listening on http://0.0.0.0:${'$'}{port}`));\n",
        "README.md" to "# ${appName.ifBlank { "Node app" }}\n\nRun: `npm start` then open the Preview tab (port 3000).\n"
    )

    private fun viteApp(appName: String, @Suppress("UNUSED_PARAMETER") pkg: String): Map<String, String> = mapOf(
        "package.json" to
            "{\n  \"name\": \"${slug(appName)}\",\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n" +
            "  \"scripts\": { \"dev\": \"vite --host 0.0.0.0\", \"build\": \"vite build\", \"preview\": \"vite preview --host 0.0.0.0\" },\n" +
            "  \"devDependencies\": { \"vite\": \"^5.0.0\" }\n}\n",
        "vite.config.js" to "import { defineConfig } from 'vite';\n\nexport default defineConfig({ server: { port: 5173 } });\n",
        "index.html" to
            "<!doctype html>\n<html><head><meta charset=\"utf-8\" />\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
            "<title>${appName.ifBlank { "Vite app" }}</title></head>\n" +
            "<body><div id=\"app\"></div><script type=\"module\" src=\"/src/main.js\"></script></body></html>\n",
        "src/main.js" to "document.getElementById('app').innerHTML = '<h1>Hello Vite + EliCode</h1>';\n",
        "README.md" to "# ${appName.ifBlank { "Vite app" }}\n\nRun: `npm install && npm run dev` then open Preview (port 5173).\n"
    )

    private fun pythonApp(appName: String, @Suppress("UNUSED_PARAMETER") pkg: String): Map<String, String> = mapOf(
        "main.py" to
            "from http.server import BaseHTTPRequestHandler, HTTPServer\n\n" +
            "class H(BaseHTTPRequestHandler):\n    def do_GET(self):\n" +
            "        body = b'<h1>Hello from EliCode + Python</h1>'\n" +
            "        self.send_response(200)\n        self.send_header('content-type', 'text/html')\n" +
            "        self.send_header('content-length', str(len(body)))\n        self.end_headers()\n" +
            "        self.wfile.write(body)\n    def log_message(self, *a):\n        print(' '.join(str(x) for x in a))\n\n" +
            "if __name__ == '__main__':\n    HTTPServer(('0.0.0.0', 8000), H).serve_forever()\n",
        "requirements.txt" to "# no third-party deps needed\n",
        "README.md" to "# ${appName.ifBlank { "Python app" }}\n\nRun: `python3 main.py` then open Preview (port 8000).\n"
    )

    private fun emptyProject(appName: String, @Suppress("UNUSED_PARAMETER") pkg: String): Map<String, String> = mapOf(
        "README.md" to "# ${appName.ifBlank { "Empty project" }}\n\nCreated with EliCode. Add files from the Editor tab.\n"
    )

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "app" }.take(64)
}
