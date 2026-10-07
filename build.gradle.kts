plugins { application }
group = "com.antimatterzone"
version = "0.1.0-dev"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.3")
}
application { mainClass.set("com.antimatterzone.apconnector.Main") }
tasks.test { useJUnitPlatform() }
tasks.processResources { from(listOf("LICENSE", "THIRD-PARTY-NOTICES.md")) { into("META-INF/dms") } }
tasks.register<Exec>("packageAppImage") {
    dependsOn("installDist", "test")
    val directory = providers.gradleProperty("appImageDirectory").getOrElse("app-image")
    require(directory.matches(Regex("[a-zA-Z0-9_-]+"))) { "App-image directory must be a simple name under build/." }
    val destination = layout.buildDirectory.dir(directory).get().asFile
    doFirst { check(!destination.exists()) { "Use a fresh build/app-image destination; never overwrite a running image." } }
    commandLine(javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.file("bin/jpackage.exe").asFile,
        "--type", "app-image", "--name", "DMS Archipelago Connector", "--app-version", "0.1.0",
        "--vendor", "Antimatter Zone", "--dest", destination,
        "--input", layout.buildDirectory.dir("install/dms-archipelago-connector/lib").get().asFile,
        "--main-jar", "dms-archipelago-connector-${project.version}.jar", "--main-class", application.mainClass.get(),
        "--add-modules", "java.base,java.desktop,java.net.http,jdk.httpserver,jdk.crypto.ec,java.sql,java.naming",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages", "--win-console")
}
