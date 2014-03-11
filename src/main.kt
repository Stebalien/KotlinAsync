/**
 * Created by steb on 2/9/14.
 */


fun main(args: Array<String>) {
    asyncMain(args) otherwise {
        throw it
    }
}

fun asyncMain(args: Array<String>) = async<Unit> {
    await(TrivialPromise(1)) {

    await(atry<Unit> {
        println("before")
        throw IllegalStateException("Testing")
    }.catch(javaClass<IllegalStateException>()) {
        println("here")
        //throw it
    }.finally {
        println("at last")
    }) {

    println("first")
    // Kotlin *claims* it can't deduce the type Unit. However, it demonstrates
    // that it can in the error message...
    await<Unit>(delay(1000)) {
    println("second")
    await<Unit>(unblock{Thread.sleep(1000)}) {
    println("third")

    // Random interval timer.

        /*
    var canceled = false
    val interval = async<Unit> {
        await(awhile({!canceled}) {
            println("Interval")
            await(delay(10, TimeUnit.SECONDS))
        })
    }
    */

    await<Unit>(aforeach(1..10) {
        if (it == 5) acontinue()
        if (it == 8) abreak()
        println(it)
    }) {

    val iterator = java.io.File("/etc/shells").readLinesAsync()
    println("reading lines")
    await<Unit>(aforeach(iterator) { line ->
        println(line)
    }) {
    println("done")
}}}}}}}

/* How it will actually look:

fun asyncMain(args: Array<String>) = async {
    await TrivialPromise(1)

    try {
        println("before")
        throw IllegalStateException("Testing")
    } catch (it: IllegalStateException) {
        println("here")
        //throw it
    } finally {
        println("at last")
    }

    println("first")

    await sleep(1000)
    println("second")
    await unblock{Thread.sleep(1000)}
    println("third")

    var canceled = false
    val interval = async {
        while(!canceled) {
            println("Interval")
            await sleep(10, TimeUnit.SECONDS)
        }
    }

    for (it in 1..10) {
        if (it == 5) continue
        if (it == 8) break
        println(it)
    }

    val linesPromise = java.io.File("/etc/shells").readLinesAsync()
    println("reading lines")
    val lines = await linesPromise

    for (line in lines)
        println(line)
    }
    println("done")
    await interval
}
 */
