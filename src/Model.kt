package consensus

import java.io.*

val SOLUTION_FILE = File("solution.txt")

const val T_STEP = 40

sealed class Action(var time: Int, val processId: Int) {
    open val from: Action? get() = null
    open val to: Action? get() = null
    override fun toString(): String = this::class.simpleName!!
}

class Consensus(time: Int, processId: Int, val consensus: Int) : Action(time, processId) {
    override fun toString(): String = "Consensus $consensus"
}

class Send(time: Int, processId: Int, val destId: Int, val message: Message) : Action(time, processId) {
    override var to: Rcvd? = null
    override fun toString(): String = "{$processId SEND $destId} $message"
}

class Rcvd(time: Int, processId: Int, val srcId: Int, val message: Message, override val from: Send) : Action(time, processId) {
    override fun toString(): String = "{$processId RCVD $srcId} $message"
}

class PS(val processId: Int) {
    lateinit var process: Process
    var consensus: Int? = null
    val actions = ArrayList<Action>()
}

const val ACTIONS = "#actions"
const val PROCESS = "#process"

@OptIn(ExperimentalStdlibApi::class)
class Model(
    val nProcesses: Int
) {
    val ps = HashMap<Int, PS>()
    val pending = LinkedHashSet<Send>()

    var updateListener: (() -> Unit)? = null

    var name = ""
        private set
    var impl = ""
        private set

    val actions: Sequence<Action>
        get() = ps.values.asSequence().flatMap { it.actions.asSequence() }

    val processes: Map<Int, Process>
        get() = ps.values.associate { it.processId to it.process }

    private val output = ArrayList<String>()

    fun restart(name: String, impl: String) {
        this.name = name
        this.impl = impl
        ps.clear()
        pending.clear()
        // output
        output.clear()
        output += name
        output += impl
        output += ACTIONS
        if (impl.isNotEmpty()) {
            for (i in 1..nProcesses) {
                val p = PS(i)
                ps[i] = p
                p.process = createProcess(ProcessEnvironment(i), impl).apply { start() }
            }
        }
        updateListener?.invoke()
    }

    fun save(name: String, file: File) {
        this.name = name
        val text = output.toMutableList()
        text[0] = name
        for (p in ps.values) {
            text += "$PROCESS ${p.processId}"
            for (a in p.actions) text += "${a.time} $a"
        }
        file.writeText(text.joinToString("\n"))
    }

    fun load(file: File) {
        val text = file.readLines().toCollection(ArrayDeque())
        val name = text.removeFirst()
        val impl = text.removeFirst()
        check(text.removeFirst() == ACTIONS) { "Expected $ACTIONS line" }
        restart(name, impl)
        while (!text.first().startsWith(PROCESS)) {
            val act = text.removeFirst()
            val send = pending.find { it.toString() == act } ?: error("Pending action not found: $act")
            perform(send)
        }
        for (i in 1..nProcesses) {
            check(text.removeFirst() == "$PROCESS $i")
            val p = ps[i]!!
            var lastTime = -1
            for (a in p.actions) {
                val line = text.removeFirst()
                val aStr = a.toString()
                check(line.endsWith(aStr)) { "Inconsistent action in process $i: $line"}
                val time = line.substring(0, line.length - aStr.length).trim().toInt()
                check(time > lastTime) { "Program order violated in process $i: $line" }
                a.time = time
                lastTime = time
            }
        }
        for (to in actions) {
            val from = to.from ?: continue
            check(to.time > from.time) { "Happens-before violated: ${from.time} $from -> ${to.time} $to"}
        }
        updateListener?.invoke()
    }

    val time: Int
        get() = ps.values.map { it.actions.lastOrNull()?.time ?: 0 }.max() ?: 0

    fun nextTime(processId: Int, from: Action? = null): Int {
        val time = (ps[processId]?.actions?.lastOrNull()?.time ?: 0) + T_STEP
        return if (from != null) maxOf(time, from.time + T_STEP) else time
    }

    fun perform(send: Send) {
        check(send in pending)
        output += send.toString()
        pending -= send
        with(ps[send.destId]!!) {
            val rcvd = Rcvd(nextTime(send.destId, send), send.destId, send.processId, send.message, send)
            send.to = rcvd
            actions += rcvd
            process.onMessage(rcvd.srcId, rcvd.message)
        }
        updateListener?.invoke()
    }

    fun nextAction(a: Action): Action? {
        val actions = ps[a.processId]!!.actions
        return actions.getOrNull(actions.indexOf(a) + 1)
    }

    fun prevAction(a: Action): Action? {
        val actions = ps[a.processId]!!.actions
        return actions.getOrNull(actions.indexOf(a) - 1)
    }

    private inner class ProcessEnvironment(override val processId: Int) : Environment {
        override val nProcesses: Int = this@Model.nProcesses

        override fun send(destId: Int, message: Message) {
            val send = Send(nextTime(processId), processId, destId, message)
            pending += send
            ps[processId]!!.actions += send
        }

        override fun decide(consensus: Int) {
            with (ps[processId]!!) {
                check(this.consensus == null)
                this.consensus = consensus
                actions += Consensus(nextTime(processId), processId, consensus)
            }
        }
    }
}

private fun createProcess(env: Environment, impl: String): Process =
    Class.forName("consensus.$impl")
        .getConstructor(Environment::class.java)
        .newInstance(env) as Process
