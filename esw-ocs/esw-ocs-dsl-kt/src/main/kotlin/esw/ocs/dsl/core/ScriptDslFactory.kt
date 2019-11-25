package esw.ocs.dsl.core

import esw.ocs.dsl.script.CswServices
import esw.ocs.dsl.script.MainScriptDsl
import esw.ocs.dsl.script.StrandEc

internal object ScriptDslFactory {
    fun make(cswServices: CswServices, strandEc: StrandEc) = object : MainScriptDsl(cswServices) {
        override fun strandEc(): StrandEc = strandEc
    }
}
