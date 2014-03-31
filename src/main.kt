/**
 * Created by steb on 2/9/14.
 */


import kotlin.properties.Delegates

fun main(args: Array<String>) {
    asyncMain(args) otherwise { throw it }
}

class ObservableValue<T>(initial: T) {
    public var value: T by Delegates.observable(initial) { metadata, old, new ->
        changeSignal.fire(new)
    }
    private val changeSignal: Signal<T> = Signal()
    public val changes: AsyncIterable<T> = changeSignal.safe()
}

fun asyncMain(args: Array<String>) = async<Unit> {
    await(tryAsync<Unit> {
        println("before")
        throw IllegalStateException("Testing")
    }.catch(javaClass<IllegalStateException>()) {
        println("here")
        //throw it
    }.finally {
        println("at last")
    })
}.thenAsync<Unit> {
    println("first")
    // Kotlin *claims* it can't deduce the type Unit. However, it demonstrates
    // that it can in the error message...
    await(delay(1000))
}.thenAsync<Unit> {
    println("second")
    await(unblock { Thread.sleep(1000) })
}.thenAsync<Unit> {
    println("third")

    await(foreachAsync(1..10) {
        if (it == 5) acontinue()
        if (it == 8) abreak()
        println(it)
        done()
    }) {
        done(println("loop 1 done"))
    }
}.thenAsync<Unit> {

    val value = ObservableValue(1)
    val sigPromise = foreachAsync(value.changes) {
        println(it)
        done()
    }
    value.value = 2
    value.value = 3
    value.value = 4

    val iterator = java.io.File("/etc/shells").readLinesAsync()
    println("reading lines")
    await(foreachAsync(iterator) { line ->
        println(line)
        done()
    })
}.then {
    println("done")
}

