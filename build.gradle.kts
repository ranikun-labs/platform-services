plugins {
    base
}

val gatewayBuild = gradle.includedBuild("gateway-app")
val platformCoreBuild = gradle.includedBuild("platform-core")

tasks.named("clean") {
    dependsOn(
            gatewayBuild.task(":clean"),
            platformCoreBuild.task(":clean"))
}

tasks.named("check") {
    dependsOn(
            gatewayBuild.task(":check"),
            platformCoreBuild.task(":check"))
}

tasks.named("build") {
    dependsOn(
            gatewayBuild.task(":build"),
            platformCoreBuild.task(":build"))
}

tasks.register("test") {
    group = "verification"
    description = "Runs tests for Gateway and Platform Core."
    dependsOn(
            gatewayBuild.task(":test"),
            platformCoreBuild.task(":test"))
}

tasks.register("bootJar") {
    group = "build"
    description = "Builds the Gateway and Platform Core executable jars."
    dependsOn(
            gatewayBuild.task(":bootJar"),
            platformCoreBuild.task(":app:bootJar"))
}
