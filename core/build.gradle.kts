dependencies {
    implementation(files("${rootProject.projectDir}/externals/meico.jar"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
