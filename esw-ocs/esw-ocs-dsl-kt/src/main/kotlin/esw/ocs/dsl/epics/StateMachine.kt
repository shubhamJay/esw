package esw.ocs.dsl.epics

import kotlinx.coroutines.*
import kotlin.time.Duration

class StateMachine(private val name: String, val coroutineScope: CoroutineScope) : Refreshable {
    private var currentState: String? = null
    private var previousState: String? = null

    private var fsmJob: Job? = null // Gets CANCELLED whenever the FSM is completed
    private var fsmCompletorJob: CompletableJob = Job() // Gets COMPLETED whenever the FSM is completed

    //fixme : do we need to passas receiver coroutine scope to state lambda
    val states = mutableMapOf<String, suspend () -> Unit>()

    fun state(name: String, block: suspend () -> Unit) {
        states += Pair(name, block)
    }

    suspend fun become(state: String) {
        if (states.keys.any { it.equals(state,true) }){
            currentState = state
            refresh()
            //fixme: add concerete exception for this
        } else throw RuntimeException("Failed transition to invalid state:  $state")
    }

    fun start(initState: String){
        fsmJob = coroutineScope.launch {
            become(initState)
            refresh()
        }
    }

    suspend fun await() {
        fsmCompletorJob.join()
    }

    suspend fun startAndWait(initState: String) {
        start(initState)
        await()
    }

    suspend fun completeFsm() {
        fsmJob?.cancelAndJoin()
        fsmCompletorJob.complete()
    }

    override suspend fun refresh() {
        println(
                "machine = $name    previousState = $previousState     currentState = $currentState}"
        )
        //fixme: what happens when there is no entry for given state
        states[currentState]?.invoke()
    }

    suspend fun on(condition: Boolean = true, body: suspend () -> Unit) {
        previousState = currentState
        if (condition) {
            body()
        }
    }

    suspend fun on(duration: Duration, body: suspend () -> Unit) {
        delay(duration.toLongMilliseconds())
        on(body = body)
    }

    //fixme: restrict entry only to lambda passed to state
    suspend fun entry(body: suspend () -> Unit) {
        if (currentState != previousState) {
            body()
        }
    }

    // todo: can we use generics here?
    operator fun Int?.compareTo(other: Int?): Int =
            if (this != null && other != null) this.compareTo(other)
            else -1
}