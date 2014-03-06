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

Design (how it will work, not how it does work):

This async design is very much like C#'s except that:
    1. There is no async function modifier. If a function is asynchronous, it returns a Promise. Instead, there is an
       async block that returns a promise. IMHO, this is slightly more flexible than C#s approach.

    2. Everything runs on the same thread.

At the core of this design is the `Promise<Value>`. Pretty much everything else is just sugar to make promises easier
to work with. If you want to write an async function, just write a normal function but return a promise for the value
instead of the value itself.

This modification will add two keywords, async and await.

async is just a block that returns a promise for it's inner value. That is:

    val promisedInt: Promise<Int> = async { 0 }

await is a keyword that causes a function to "pause" on a promise and then return it's value.

    val theInt: Int = await promisedInt

await may only be used in an async block or a try/while/for/if/etc. block inside of an async block.

Prototype Notes:

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
            val v = await something
        }

    There is no ambiguity because nothing after an await call will EVER be run (it always throws a promise...).

    Pretend that main can return a promise (asyncMain == main).

    Finally, the actual implementation WILL NOT use exceptions for control flow...

Todo:
    1. Doctor stack traces in exceptions. We can do this by storing them when awaiting and restoring them when continuing. This sounds
       simple but is actually quite painful because I can't edit the actual stack, I can only replace exception stack
       traces. I can do it, but it might not be worth implementing it at this point.

    2. Implement a custom scheduler so we can determine if the program is done? Basically, exit if:
          (a) The user asks (stop the scheduler)
          (b) (1) There is nothing scheduled on either the threaded executor or scheduler.
              (2) There are no other threads running.
       This way the programmer can either (a) manually stop the scheduler or (b) exit everything else and let the scheduler
       die naturally.

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

Prototype Warnings:

    1. Don't catch arbitrary exceptions. You'll break the control flow...
    2. This is an experiment. Please don't take offense at any heresies committed.
    3. Don't await in an if condition expression, just await before it.
    4. Don't branch atry/catch/finally statements. This won't work when they are
       actually statements not just function calls.

Design Questions:

    If you are familiar with Mozilla's JavaScript Promises, you'll notice that mine are slightly different.

    1. For one, Promises and Obligations are separable for safety (this isn't going to change).
    2. I have separate methods for receiving exceptions/receiving values. I could use a single method that accepts
       a nullable value and a nullable throwable (like JavaScript). I could also use a maybe type (Maybe<T, Throwable>).
    3. I throw an exception instead of returning false if you try to fulfill a promise twice. I do this because people
       have a tendency to ignore returned errors and fulfilling a promise twice is often a program error. However, I
       might consider adding a (possibly extension) function `tryFulfill` that doesn't throw an exception. This would be
       useful in cases where multiple actors can fulfill an obligation.
    4. Currently, I ensure that all then/otherwise callbacks are run from the scheduler. This is almost always redundant
       because the callbacks are almost always intermediates that run a user function in an async environment (which will,
       again, be put on the scheduler). Alternatively, I could relax some of the constraints and run then callbacks
       directly. This would have the added benefit of letting users create their own promises without interacting with
       the scheduler. HOWEVER, a call to then/fulfill would have to execute the callbacks itself which could lead to
       other bugs...
    5. For now, there's only one event loop. We might want to add more? One per thread?

Other Notes:
    In general, I doubt that people will manually call then/fulfill very often. Most cases will be solvable using
    async/await. However, making fulfill available allows users to add lower-level async features.

*/

