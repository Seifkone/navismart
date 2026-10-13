plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<JavaExec>().configureEach { jvmArgs("-Dfile.encoding=UTF-8") }

val verifyEconomy by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.navismart.economy.EconomyTests")
}
tasks.check { dependsOn(verifyEconomy) }

tasks.register<JavaExec>("simulate") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.navismart.economy.Simulator")
}
