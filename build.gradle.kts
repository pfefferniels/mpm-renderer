plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation(files("externals/meico.jar"))
}

application {
    mainClass.set("MeicoServer")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

