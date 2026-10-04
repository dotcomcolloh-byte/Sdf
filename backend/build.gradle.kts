plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
application { mainClass.set("com.vidtubehub.backend.ApplicationKt") }
dependencies {
    val ktor = "3.0.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-server-partial-content-jvm:$ktor")
    implementation("io.ktor:ktor-server-auto-head-response-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
