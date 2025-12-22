plugins {
    scala
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":lib"))
    implementation("dev.zio:zio_2.13:2.0.18")
    implementation("com.lihaoyi:fastparse_2.13:2.3.3")
}

application {
    // main class for the application plugin
    mainClass.set("com.writhlang.app.Main")
}