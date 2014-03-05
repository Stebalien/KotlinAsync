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

        val linesPromise = java.io.File("/etc/shells").readLinesAsync()
        println("reading lines")
        await(linesPromise) { lines ->

        await<Unit>(aforeach(lines) { line ->
            println(line)
        }) {
        println("done")
    }}}}}}}}
}
/*
THIS IS A PROTOTYPE/MOCKUP!!!

Preferably, aforeach, atry, awhile, would all await by default but, for now, that makes it even harder to read.
Also, pretend that the block after await is implicit. That is, if you see:

    async {
        await(something) { v ->

    }}

Imagine you're actually seeing:

    async {
        await something => v
    }

or

    async {
        val v = await something
    }

There is no ambiguity because nothing after an call await will EVER be run (it always throws a promise...).

Guarantees:

An obligation may be fulfilled or abandoned from any thread but must only be fulfilled or abandoned once.

All callbacks (then, otherwise, async, etc.) will be called on the same thread (the async thread).
EXCEPT the unblock callback. For obvious reasons, it will be called on it's own thread.

Warnings:

1. Don't catch arbitrary exceptions. You'll break the control flow...
2. This is an experiment. Please don't take offense at any heresies committed.
3. Don't await in an if condition expression, just await before it.

 */

