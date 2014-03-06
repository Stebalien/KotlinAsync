/**
 * Created by steb on 2/9/14.
 */



import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    asyncMain(args) otherwise {
        throw it
    }
}

fun asyncMain(args: Array<String>) = async<Unit> {
    await(TrivialPromise(1)) {

    // Kotlin *claims* it can't determine Int (but it can and does in the error message...)
    await<Unit>(atry<Unit> {
        println("before")
        throw IllegalStateException("Testing")
    }.catch(javaClass<IllegalStateException>()) {
        println("here")
        //throw it
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
/*
THIS IS A PROTOTYPE/MOCKUP!!!

Notes:

    In an actual implementation, all await(awhile/aforeach/...) would just be written as the standard
    control statements (while, for, ...). These standard control statements would *become* the async
    versions iff they contain an await. This shouldn't surprise the user as (a) they would have had to have wrapped the if
    statement in an async block (to even be able to call await) and (b) they would have to call await themselves (it would
    have to be called directly in async block.

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

    There is no ambiguity because nothing after an await call will EVER be run (it always throws a promise...).

    Finally, the actual implementation WILL NOT use exceptions for control flow...

Guarantees:

    An obligation may be fulfilled or abandoned from any thread but must only be fulfilled or abandoned once.

    All callbacks (then, otherwise, async, etc.) will be called on the same thread (the async thread).
    EXCEPT the unblock callback. For obvious reasons, it will be called on it's own thread.

    In order:
     1. Given a promise p, a matching obligation o, two functions a and b, and a value v:
        (p.then(a) -> p.then(b) -> o.fulfill(v)) implies (a(v) -> b(v))
     2. Given two promises p1 and p2, their matching obligations o1 and o2, two functions f1 and f2, and a value v:
        ({p1.then(f1), p2.then(f2)} -> o1.fulfill(v) -> o2.fulfill(v))
        implies
        (f1(v) -> f2(v))

    Blocking:
        Both promises and obligations avoid locking as much as possible. Unfortunately, due to the schedulers current
        implementation, they aren't wait-free. If we implement our own scheduler, this could (maybe) be wait free. We could
        also have a separate transfer thread that feeds the scheduler off of a wait-free ConcurrentLinkedQueue (but this is
        probably overkill).

        Anyways, it won't deadlock and I highly doubt that this will be a significant bottleneck.

Warnings:

    1. Don't catch arbitrary exceptions. You'll break the control flow...
    2. This is an experiment. Please don't take offense at any heresies committed.
    3. Don't await in an if condition expression, just await before it.

Design Questions:

    If you are familiar with Mozilla's JavaScript Promises, you'll notice that mine are slightly different.

    1. For one, Promises and Obligations are separable for safety (this isn't going to change).
    2. I have separate methods for receiving exceptions/receiving values. I could use a single method that accepts
       a nullable value and a nullable throwable (like JavaScript). I could also use a maybe type (Maybe<T, Throwable>).
    3. I throw an exception instead of returning false if you try to fulfill a promise twice. I do this because people
       have a tendency to ignore returned errors and fulfilling a promise twice is often a program error. However, I
       might consider adding a (possibly extension) function `tryFulfill` that doesn't throw an exception. This would be
       useful in cases where multiple actors can fulfill an obligation.

Other Notes:
    In general, I doubt that people will manually call then/fulfill very often. Most cases will be solvable using
    async/await. However, making fulfill available allows users to add lower-level async features.

*/

