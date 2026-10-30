import io.github.recrivenvi.conventions.platform.TargetExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins { id("net.neoforged.moddev") }

val facts = extensions.getByType<TargetExtension>()
val compatibilityDirectory = layout.buildDirectory.dir("compatibility-compile")
val compatibility =
    configurations.create("compatibilityCompileArtifacts") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

repositories {
    mavenLocal { content { includeGroup("com.radiance") } }
    mavenCentral()
    maven("https://maven.blamejared.com/") { content { includeGroup("foundry.veil") } }
}

dependencies {
    compileOnly(facts.module("com.radiance:Radiance-game", "radiance_version")) {
        isTransitive = false
    }
    testImplementation(facts.module("com.radiance:Radiance-game", "radiance_version")) {
        isTransitive = false
    }
    compileOnly(libs.snakeyaml)
    testRuntimeOnly(libs.snakeyaml)
    compileOnly(
        facts.module(
            "foundry.veil:veil-neoforge-${facts.fact("minecraft_version")}",
            "veil_version",
        ),
    ) {
        isTransitive = false
    }
    add(
        compatibility.name,
        facts.module(
            "foundry.veil:veil-neoforge-${facts.fact("minecraft_version")}",
            "veil_version",
        ),
    )
    testRuntimeOnly(
        facts.module(
            "foundry.veil:veil-neoforge-${facts.fact("minecraft_version")}",
            "veil_version",
        ),
    ) {
        isTransitive = false
    }
    for (name in listOf("create", "sable", "aeronautics_bundled", "voxy")) {
        compileOnly(facts.input(name))
        add(compatibility.name, facts.input(name))
        testRuntimeOnly(facts.input(name))
    }
    compileOnly(fileTree(compatibilityDirectory) { include("*.jar") })
    testRuntimeOnly(fileTree(compatibilityDirectory) { include("*.jar") })
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val prepareCompatibilityCompileDependencies =
    tasks.register<Sync>("prepareCompatibilityCompileDependencies") {
        from(provider { compatibility.map { zipTree(it) } })
        include("META-INF/jarjar/*.jar")
        eachFile { path = name }
        includeEmptyDirs = false
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        into(compatibilityDirectory)
    }

tasks.withType<JavaCompile>().configureEach { dependsOn(prepareCompatibilityCompileDependencies) }

tasks.withType<Test>().configureEach { jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED") }

dependencies {
    add("probeImplementation", facts.module("com.radiance:Radiance-probe", "radiance_version")) {
        isTransitive = false
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("jar"))
    systemProperty("radiance.test.sableVersion", facts.fact("sable_metadata_version"))
    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doFirst {
        systemProperty(
            "radiance.test.compatibilityArtifacts",
            compatibility.files.joinToString(File.pathSeparator) { it.absolutePath },
        )
        systemProperty("radiance.extra.distribution", jarFile.get().asFile.absolutePath)
    }
    useJUnitPlatform()
}

val sourceSets = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()

val surfaceProgram = layout.buildDirectory.file("generated/surface/levitite.comp.spv")
val compileSurfaceProgram =
    tasks.register<Exec>("compileSurfaceProgram") {
        val input =
            layout.projectDirectory.file(
                "src/main/resources/assets/radiance_extra/shaders/world/levitite.comp",
            )
        inputs.file(input)
        outputs.file(surfaceProgram)
        val sdk = providers.environmentVariable("VULKAN_SDK").orElse("")
        executable(
            sdk.map { if (it.isBlank()) "glslangValidator" else "$it/Bin/glslangValidator.exe" }
                .get(),
        )
        args(
            "-V",
            "--target-env",
            facts.fact("surface_vulkan_target"),
            input.asFile.absolutePath,
            "-o",
            surfaceProgram.get().asFile.absolutePath,
        )
        doFirst { surfaceProgram.get().asFile.parentFile.mkdirs() }
    }

tasks.named<ProcessResources>("processResources") {
    from(compileSurfaceProgram) { into("assets/radiance_extra/shaders/world") }
}

sourceSets.named("test") {
    compileClasspath += sourceSets.named("main").get().compileClasspath
    runtimeClasspath += sourceSets.named("main").get().runtimeClasspath
}

// Use the API artifact's own access contract in standalone tests; never duplicate its definitions.
val renderApiAccess =
    configurations.create("renderApiAccess") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies {
    add(renderApiAccess.name, facts.module("com.radiance:Radiance-game", "radiance_version"))
}

val extractRenderApiAccess =
    tasks.register<Sync>("extractRenderApiAccess") {
        from(provider { renderApiAccess.map { zipTree(it) } })
        include("META-INF/accesstransformer.cfg")
        into(layout.buildDirectory.dir("render-api-access"))
    }

neoForge {
    accessTransformers.from(
        files(layout.buildDirectory.file("render-api-access/META-INF/accesstransformer.cfg"))
            .builtBy(extractRenderApiAccess),
    )
}

sourceSets.named("test") {
    compileClasspath +=
        sourceSets.named("probe").get().output + sourceSets.named("probe").get().compileClasspath
    runtimeClasspath +=
        sourceSets.named("probe").get().output + sourceSets.named("probe").get().runtimeClasspath
}
