/**
 * Created by steb on 2/24/14.
 */

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.Queue

public enum class PromiseState {
    PENDING
    FULFILLED
    BROKEN
    CHANGING
}

private val scheduler = Executors.newSingleThreadScheduledExecutor()
private val threadManager = Executors.newCachedThreadPool()

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

public fun <T, O> Promise<T>.plus(other: Promise<O>): PromisePair<T, O> {
    return PromisePair(this, other)
}

fun <T> Obligation<T>.receive(promise: Promise<T>) {
    promise then        { this fulfill it }
    promise otherwise   { this abandon it }
}

// TODO: Break promise on finalize?
public class BasicPromise<T>(): OpenPromise<T, T> {
    override var state: PromiseState = PromiseState.PENDING
        get() = internalState.get()!!
    private var internalState = AtomicReference(PromiseState.PENDING)

    private val callbacks = ConcurrentLinkedQueue<(T) -> Unit>()
    private var value: T? = null
    private val catchers = ConcurrentLinkedQueue<(Exception) -> Unit>() // Should we support multiple?? They'll all get called...
    private var throwable: Exception? = null

    // This is an optimization. It ensures that there are at most 2
    // scheduled flushes at a time per promise.
    private var pendingFlush = AtomicBoolean(false)

    private fun <V> Queue<(V) -> Unit>.flush(value: V) {
        if (!pendingFlush.getAndSet(true)) {
            scheduler.execute {
                pendingFlush.set(false)
                while (notEmpty) {
                    try {
                        val fn = poll() ?: break
                        fn(value)
                    } catch (e: Exception) {
                        // I can't do anything better...
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun flush() {
        if (callbacks.notEmpty) when (state) {
            PromiseState.FULFILLED ->   callbacks.flush(value as T) // Do not use !!. It can be null!
            PromiseState.BROKEN ->      callbacks.clear()
        }
        if (catchers.notEmpty) when (state) {
            PromiseState.FULFILLED ->   catchers.clear()
            PromiseState.BROKEN ->      catchers.flush(throwable!!)
        }
    }

    override fun then(cb: (T) -> Unit) {
        callbacks.add(cb)
        flush()
    }

    override fun otherwise(fn: (Exception) -> Unit) {
        if (state == PromiseState.FULFILLED) return
        catchers.add(fn)
        flush()
    }

    override fun abandon(exception: Exception) {
        if (!internalState.compareAndSet(PromiseState.PENDING, PromiseState.CHANGING)) {
            throw IllegalStateException("Promise already fulfilled.")
        }
        throwable = exception
        internalState.set(PromiseState.BROKEN)
        flush()
    }

    override fun fulfill(value: T) {
        if (!internalState.compareAndSet(PromiseState.PENDING, PromiseState.CHANGING)) {
            throw IllegalStateException("Promise already fulfilled.")
        }
        this.value = value
        internalState.set(PromiseState.FULFILLED)
        flush()
    }
}

public class PrepaidPromise<A, I, O>(private val promise: OpenPromise<I, O>, private val value: I): OpenPromise<A, O>, Promise<O> by promise {
    override fun fulfill(value: A) {
        promise.fulfill(this.value)
    }
    override fun abandon(exception: Exception) = promise.abandon(exception)
}

public class TrivialPromise<T>(private val value: T): Promise<T> {
    override var state: PromiseState = PromiseState.FULFILLED
    override fun then(cb: (T) -> Unit) {
        scheduler.execute { cb(value) }
    }
    override fun otherwise(fn: (Exception) -> Unit) {}
}

public class EmptyPromise<T>(private val exception: Exception): Promise<T> {
    override var state: PromiseState = PromiseState.BROKEN
    override fun otherwise(fn: (Exception) -> Unit) {
        scheduler.execute { fn(exception) }
    }
    override fun then(cb: (T) -> Unit) {}
}


public class PromisePair<A, B>(private val promise1: Promise<A>, private val promise2: Promise<B>): Promise<Pair<A, B>> {
    override fun otherwise(fn: (Exception) -> Unit) {
        // TODO Not easy to support with separate then/otherwise methods.
        throw UnsupportedOperationException()
    }
    override var state: PromiseState = PromiseState.PENDING
        get() = when {
            promise1.state == PromiseState.FULFILLED && promise2.state == PromiseState.FULFILLED -> PromiseState.FULFILLED
            promise1.state == PromiseState.BROKEN, promise2.state == PromiseState.BROKEN -> PromiseState.BROKEN
            else -> PromiseState.PENDING
        }

    override fun then(cb: (Pair<A, B>) -> Unit) {
        promise1 then { value1 ->
            promise2 then { value2 ->
                cb(Pair(value1, value2)) // Put on scheduler?
            }
        }
    }
}


public fun unblock<O>(fn: () -> O): Promise<O> {
    val promise = BasicPromise<O>()
    threadManager.execute {
        try {
            promise fulfill fn()
        } catch (e: Exception) {
            promise abandon e
        }
    }
    return promise
}

public fun delay(time: Long, units: TimeUnit = TimeUnit.MILLISECONDS): Promise<Unit> {
    val p = BasicPromise<Unit>()
    scheduler.schedule({
        p.fulfill(Unit.VALUE)
    }, time, units)
    return p
}

public trait AsyncIterator<E> {
    public fun hasNext(): Promise<Boolean>
    public fun next(): E
}

public trait MutableAsyncIterator<E>: AsyncIterator<E> {
    public fun remove()
}

public trait AsyncIterable<E> {
    public fun iterator(): AsyncIterator<E>
}

public trait MutableAsyncIterable<E>: AsyncIterable<E> {
    override fun iterator(): MutableAsyncIterator<E>
}

public open class Async<O> internal () {
    private class ThrowablePromise<T>(private val promise: Promise<T>): Exception(), Promise<T> by promise
    private class AsyncLoopException : Exception()

    public class LoopBody internal (private val breakException: AsyncLoopException, private val continueException: AsyncLoopException): Async<Unit>() {
        // Break with exception???
        public fun abreak() {
            throw breakException
        }
        public fun acontinue() {
            throw continueException
        }
    }

    public class TryPromise<T> internal (private val promise: Promise<T>, private val otherPromise: BasicPromise<T> = BasicPromise()): Promise<T> by otherPromise {
        {
            promise then { otherPromise fulfill it }
        }
        private var caught = false
        public fun catch<E>(cls: Class<E>, catcher: Async<T>.(E) -> T): TryPromise<T> {
            promise otherwise {
                if (!caught && (it.javaClass.identityEquals(cls) || it.javaClass.isInstance(cls)))  {
                    caught = true // No need to synchronize
                    otherPromise receive async<T>{catcher(it as E)}
                }
            }
            return this
        }
        public fun finally(fn: Async<Unit>.() -> Unit): Promise<T> {
            val finalPromise = BasicPromise<T>()
            otherPromise then { value ->
                val finally = async<Unit>(fn)
                finally then {
                    finalPromise fulfill value
                }
                finally otherwise { error ->
                    finalPromise abandon error
                }

            }
            otherPromise otherwise { error ->
                val finally = async<Unit>(fn)
                finally then {
                    finalPromise abandon error
                }
                finally otherwise { error2 ->
                    finalPromise abandon error2
                }
            }
            return finalPromise
        }
    }


    // Multiple return types would make this less insane...
    public fun await<I>(promise: Promise<I>, fn: Async<O>.(I) -> O): O {
        val endPromise = BasicPromise<O>()
        promise then {
            endPromise receive async{fn(it)}
        }
        promise otherwise {
            endPromise abandon it
        }
        throw ThrowablePromise(endPromise)
    }

    public fun await(promise: Promise<O>): O {
        throw ThrowablePromise(promise)
    }

    public fun async(fn: Async<O>.() -> O): Promise<O> {
        val promise = BasicPromise<O>()
        scheduler.submit {
            try {
                promise fulfill this.fn()
            } catch (e: ThrowablePromise<O>) {
                promise receive e
            } catch (e: Exception) {
                promise abandon e
            }
        }
        return promise
    }

    public fun awhile(condition: Async<Boolean>.() -> Boolean, body: LoopBody.() -> Unit): Promise<Unit> {
        val breakException = AsyncLoopException()
        val continueException = AsyncLoopException()
        val bodyCtx = LoopBody(breakException, continueException)
        val resultingPromise = BasicPromise<Unit>()

        async(condition) then {
            if (it) {
                val result = async<Unit>{bodyCtx.body()}
                result then {
                    resultingPromise receive awhile(condition, body)
                }
                result otherwise { e ->
                    when (e) {
                        continueException ->    resultingPromise receive awhile(condition, body)
                        breakException ->       resultingPromise fulfill Unit.VALUE
                        else ->                 resultingPromise abandon e
                    }
                }
            }
        }
        return resultingPromise
    }

    public fun aforeach<T>(iterable: Iterable<T>, body: LoopBody.(T) -> Unit): Promise<Unit> {
        return aforeach(iterable.iterator(), body)
    }

    public fun aforeach<T>(iterator: Iterator<T>, body: LoopBody.(T) -> Unit): Promise<Unit> {
        return awhile({iterator.hasNext()}) {
            await(async{body(iterator.next())})
        }
    }

    public fun aforeach<T>(iterable: AsyncIterable<T>, body: LoopBody.(T) -> Unit): Promise<Unit> {
        return aforeach(iterable.iterator(), body)
    }

    public fun aforeach<T>(iterator: AsyncIterator<T>, body: LoopBody.(T) -> Unit): Promise<Unit> {
        return awhile({await(iterator.hasNext())}) {
            await(async{body(iterator.next())})
        }
    }

    public fun atry<T>(fn: Async<T>.() -> T): TryPromise<T> {
        return TryPromise(async(fn))
    }
}

public fun async<O>(fn: Async<O>.() -> O): Promise<O> {
    return Async<O>().async(fn)
}
