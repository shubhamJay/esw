package esw.ocs.dsl.core

import esw.ocs.dsl.script.CswServices
import esw.ocs.dsl.script.StrandEc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class Result(val scriptFactory: (CswServices) -> Script) {
    operator fun invoke(cswService: CswServices): Script = scriptFactory(cswService)
}

fun script(block: suspend Script.(csw: CswServices) -> Unit): Result =
        Result {
            Script(it).apply { runBlocking { block(it) } }
        }

class ReusableScriptResult(val scriptFactory: (CswServices, StrandEc, CoroutineScope) -> ReusableScript) {
    operator fun invoke(cswService: CswServices, strandEc: StrandEc, coroutineScope: CoroutineScope) =
            scriptFactory(cswService, strandEc, coroutineScope)
}

fun reusableScript(block: ReusableScript.(csw: CswServices) -> Unit) =
        ReusableScriptResult { csw, ec, ctx ->
            ReusableScript(csw, ec, ctx).apply { block(csw) }
        }
