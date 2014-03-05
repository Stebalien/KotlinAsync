/**
 * Created by steb on 2/9/14.
 */



import java.util.concurrent.TimeUnit

fun main(args: Array<String>): Unit {
    async<Unit> {
        await(TrivialPromise(1)) {

        // Kotlin *claims* it can't determine Int (but it can and does in the error message...)
        await<Unit>(atry<Unit> {
            println("before")
            throw IllegalStateException("Testing")
        }.catch(javaClass<IllegalStateException>()) {
            println("here")
        }.finally {
            println("at last")
        }) {

        println("first")
        await<Unit>(sleep(1000)) {
        println("second")
        await<Unit>(unblock{Thread.sleep(1000)}) {
        println("third")

        await<Unit>(aforeach(1..10) {
            if (it == 5) acontinue()
            if (it == 8) abreak()
            println(it)
        }) {

        val linesPromise = java.io.File("/home/steb/go.kt").readLinesAsync()
        println("reading lines")
        await(linesPromise) { lines ->

        await<Unit>(aforeach(lines) { line ->
            println(line)
        }) {
        println("done")
    }}}}}}}}
}

