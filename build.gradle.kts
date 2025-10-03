plugins { /* no root plugins */ }

subprojects {
    repositories { mavenCentral() }
    apply(plugin = "java")

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
