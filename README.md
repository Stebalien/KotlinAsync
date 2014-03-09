THIS IS A PROTOTYPE/MOCKUP!!!

# Design (how it will work, not how it does work)

This async design is very much like C#'s except that:

  1. There is no async function modifier. If a function is asynchronous, it
     returns a Promise. Instead, there is an async block that returns a
     promise. IMHO, this is slightly more flexible than C#s approach.

  2. Everything runs on the same thread.

At the core of this design is the `Promise<Value>` (and `Obligation<Value>`).
Pretty much everything else is just sugar to make promises easier to work with.
If you want to write an async function, just write a normal function but return
a promise for the value instead of the value itself.

Unlike in JavaScript, I've broken Promises into two classes: Promise and Obligation
(reproduced below).

```kt
/**
 * This is the public half that should be returned from an async function.
 */
public trait Promise<T> {
    public var state: PromiseState
        private set

    /**
     * Attach a success callback.
     *
     * When this promise is fulfilled, or if this promise has already been fulfilled,
     * any function literals passed to this function will be called with the promises
     * value.
     */
    public fun then(cb: (T) -> Unit): Unit

    /**
     * Attach an error callback.
     *
     * When this promise is broken, or if this promise has already been broken,
     * any function literals passed to this function will be called with the
     * associated exception.
     */
    public fun otherwise(fn: (Exception) -> Unit): Unit
}
/**
 * This is the private half that should be kept by the actor responsible for
 * fulfilling the associated promise.
 */
public trait Obligation<T> {
    public var state: PromiseState
        private set

    /**
     * Fulfill the obligation.
     *
     * Abandoning an obligation will mark the associated promise as fulfilled and
     * trigger any functions attached via its the `then` method.
     */
    public fun fulfill(value: T): Unit
    /**
     * Abandon the obligation.
     *
     * Abandoning an obligation will mark the associated promise as broken and
     * trigger any functions attached via its the `otherwise` method.
     */
    public fun abandon(exception: Exception): Unit
}
/**
 * This is just a combination of an obligation and a promise.
 */
public trait OpenPromise<I, O>: Obligation<I>, Promise<O>
```

This modification will also add two keywords, async and await.

`async` is just a block that returns a promise for it's inner value. That is:

```kt
val promisedInt: Promise<Int> = async { 0 }
```

`await` is a keyword that causes a function to "pause" on a promise and then
return it's value:

```kt
async {
    val theInt: Int = await promisedInt
}
```

`await` may only be used in an `async` block or a `try`/`while`/`for`/`if`/etc.
block inside of an `async` block.

The really nice thing about having an async block instead of async functions is that
programmers can easily start up multiple async actions in parallel without having to
define useless helper functions.

The prototype also defines an `unblock` function. This does not need to be a
keyword. Basically, `unblock{myMethod()}` returns a Promise for the result of
`myMethod()` and then calls `myMethod()` in a new thread (allocated as
necessary from a thread pool). When `myMethod()` returns, it's return value is
used to fulfill the promise.

You may notice that the example code above does not call then, otherwise, fulfill,
or abandon. While I expose these methods, they should really only be used for
implementing lower-level async features.

# Prototype Notes

In an actual implementation, all `await(awhile/aforeach/...)` would just be
written as the standard control statements (`while`, `for`, ...). These
standard control statements would *become* the async versions iff they
contain an await. This shouldn't surprise the user as (a) they would have
had to have wrapped the `if` statement in an `async` block (to even be able to
call `await`) and (b) they would have to call `await` themselves (it would have
to be called directly in `async` block.

Also, pretend that the block after await is implicit. That is, if you see:

```kt
async {
  await(something) { v ->

}}
```

Imagine you're actually seeing:

```kt
async {
  val v = await something
}
```

There is no ambiguity because nothing after an await call will EVER be run (it
always throws a promise...).

Pretend that main can return a promise (asyncMain == main).

Finally, the actual implementation WILL NOT use exceptions for control flow...

# Todo
1. Doctor stack traces in exceptions. We can do this by storing them when
   awaiting and restoring them when continuing. This sounds simple but is
   actually quite painful because I can't edit the actual stack, I can only
   replace exception stack traces. I can do it, but it might not be worth
   implementing it at this point.

2. Add shutdown procedure. This is kind of important. Idea:
    a. If the main thread is asynchronous (returns a promise), wait on it
       and shutdown when completed.
    b. Otherwise, shutdown when the main-function exits?
       Question: Define "shutdown". Send exceptions? We'll also probably want
       to shutdown the scheduler....

# Guarantees

## At most once
An obligation may be fulfilled or abandoned from any thread but must only be
fulfilled or abandoned once.

## Threading
All callbacks (passed to `then`, `otherwise`, `async`, etc.) will be called on
the same thread (the async thread) EXCEPT the unblock callback. For obvious
reasons, unblock callbacks will be called on their own threads.

## In order
 1. Given a promise p, a matching obligation o, two functions a and b, and a value v:
    `(p.then(a) -> p.then(b) -> o.fulfill(v)) implies (a(v) -> b(v))`
 2. Given two promises p1 and p2, their matching obligations o1 and o2, two functions f1 and f2, and a value v:
    `({p1.then(f1), p2.then(f2)} -> o1.fulfill(v) -> o2.fulfill(v))`
    implies
    `(f1(v) -> f2(v))`

## Blocking:

Both promises and obligations avoid locking as much as possible. Unfortunately,
due to the schedulers current implementation, they aren't wait-free. If we
implement our own scheduler, this could (maybe) be wait free. We could also
have a separate transfer thread that feeds the scheduler off of a wait-free
`ConcurrentLinkedQueue` (but this is probably overkill).

Anyways, it won't deadlock and I highly doubt that this will be a significant
bottleneck.

# Prototype Warnings

1. Don't catch arbitrary exceptions. You'll break the control flow...
2. This is an experiment. Please don't take offense at any heresies committed.
3. Don't `await` in an `if` condition expression, just `await` before it.
4. Don't branch atry/catch/finally statements. This won't work when they are
   actually statements not just function calls.
5. Only call await from an `async` scope or an `a*` control block. Most importantly,
   await is not valid directly inside a function literal.

# Design Questions/Notes

If you are familiar with Mozilla's JavaScript Promises, you'll notice that mine
are slightly different.

1. Unlike Mozilla's JavaScript Promises, mine are separated into Promises and
   Obligations for safety and this isn't going to change.

2. I have separate methods for receiving exceptions/receiving values. I could
   use a single method that accepts a nullable value and a nullable throwable
   (like JavaScript). I could also use a maybe type (`Maybe<T, Throwable>`).

3. I throw an exception instead of returning false if you try to fulfill a
   promise twice. I do this because people have a tendency to ignore returned
   errors and fulfilling a promise twice is often a program error. However, I
   might consider adding a (possibly extension) function `tryFulfill` that
   doesn't throw an exception. This would be useful in cases where multiple
   actors can fulfill an obligation.

4. Currently, I ensure that all then/otherwise callbacks are run from the
   scheduler. This is almost always redundant because the callbacks are almost
   always intermediates that run a user function in an async environment (which
   will, again, be put on the scheduler). Alternatively, I could relax some of
   the constraints and run then callbacks directly. This would have the added
   benefit of letting users create their own promises without interacting with
   the scheduler. HOWEVER, a call to then/fulfill would have to execute the
   callbacks itself which could lead to other bugs...

5. For now, there's only one event loop. might want to add more. One per
   thread?

6. In general, I doubt that people will manually call then/fulfill very often.
   Most cases will be solvable using async/await. However, making
   fulfill/then/otherwise available allows users to add lower-level async
   features.

7. I don't handle Throwables, only Exceptions. If you want to catch/handle
   an Error, you need to do so immediately. I could change this but I probably
   won't, I really don't want Errors to go unnoticed.

8. Who should be able to implement a Promise? Basically, what guarantees do
   we want to make about a promises operation.
  1. No restrictions: Anyone can make a promise and make it behave in whatever
     manner they want.

     We would either (a) need to trust them or (b) verify that callbacks added
     by await are executed only once.

  2. Fully restricted: No one can implement a Promise.

     It might be useful to allow programmers to extend promises. For example,
     one might want to implement a "Cancelable" promise.

  3. Partially restricted: Anyone can implement a promise but the `then` and
     `otherwise` methods are defined in the promise trait and are final.

     Unfortunately, this would be a little difficult to implement in kotlin
     as-is because traits can't private have state.

9. An exception can be caught multiple times. Unless we say that only one
   then/otherwise callback can be added to a promise, there isn't a safe way
   (that I can think of) to avoid this. Anyways, IMHO, this isn't a problem.

