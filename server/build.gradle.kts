plugins {
    application
    id("com.gradleup.shadow") version "9.1.0"
}

dependencies {
    implementation(project(":core"))
    implementation(files("${rootProject.projectDir}/externals/meico.jar"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
}

application {
    mainClass.set("meicotools.server.HttpServerMain")
}
