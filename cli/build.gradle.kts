plugins {
    application
    id("com.gradleup.shadow") version "9.1.0"
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.6")
    implementation(files("${rootProject.projectDir}/externals/meico.jar"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6") // optional
}

application {
    mainClass.set("meicotools.cli.CliMain")
}
