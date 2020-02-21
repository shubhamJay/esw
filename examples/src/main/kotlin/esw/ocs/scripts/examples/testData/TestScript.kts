package esw.ocs.scripts.examples.testData

import esw.ocs.dsl.core.script
import kotlinx.coroutines.delay

script {
    println("loading TestScript")

    onSetup("non-blocking-command") {
        println("received non-blocking-command")
        delay(2000)
        println("done non-blocking-command")
    }

    onSetup("blocking-command") {
        Thread.sleep(2000)
    }

//    runBlocking {
//        println("hello world in run blocking")
//    }
}
