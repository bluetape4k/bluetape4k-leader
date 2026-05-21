import me.champeau.jmh.JmhParameters
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.kotlin.dsl.configure

class JmhConventionPlugin: Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("me.champeau.jmh")

        project.extensions.configure<JmhParameters>("jmh") {
            jmhVersion.set("1.37")
            includeTests.set(false)
            benchmarkMode.set(listOf("thrpt", "avgt"))
            warmup.set("1s")
            warmupIterations.set(2)
            timeOnIteration.set("1s")
            iterations.set(3)
            fork.set(1)
            threads.set(1)
            timeUnit.set("us")
            resultFormat.set("JSON")
            failOnError.set(true)
            duplicateClassesStrategy.set(DuplicatesStrategy.EXCLUDE)
            humanOutputFile.set(project.layout.buildDirectory.file("reports/jmh/human.txt"))
            resultsFile.set(project.layout.buildDirectory.file("reports/jmh/results.json"))
        }
    }
}
