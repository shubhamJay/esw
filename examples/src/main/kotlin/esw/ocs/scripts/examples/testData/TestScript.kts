package esw.ocs.scripts.examples.testData

import esw.ocs.dsl.core.script
import kotlinx.coroutines.delay
import java.net.URL

script {
    println("loading TestScript")

    println("outside url ${Thread.currentThread().name}")
    URL("http://google.com").openStream().use {
        it.bufferedReader().readLines()
    }

    onSetup("non-blocking-command") {
        println("inside url ${Thread.currentThread().name}")
        URL("http://google.com").openStream().use {
            it.bufferedReader().readLines()
        }
        println("received non-blocking-command")
        delay(2000)
        println("done non-blocking-command")
    }

    onSetup("blocking-command") {
        Thread.sleep(2000)
    }
}
