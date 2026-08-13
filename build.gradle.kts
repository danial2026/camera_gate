// Top-level build file for CameraGate.
// AGP version is declared in settings.gradle.kts.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}