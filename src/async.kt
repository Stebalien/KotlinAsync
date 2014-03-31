/**
 * Created by steb on 2/24/14.
 */

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Queue
import java.util.concurrent.Semaphore
import java.util.Arrays

public enum class PromiseState {
    PENDING
    FULFILLED
    BROKEN
    CHANGING
}

private val scheduler = Executors.newSingleThreadScheduledExecutor()
private val threadManager = Executors.newCachedThreadPool()
private class EndPromise<T>(private val promise: Promise<T>): Promise<T> by promise
private val DONE = EndPromise(TrivialPromise(Unit.VALUE))

/**
 * This is the public half that should be returned from an async function.
 */
public trait Promise<out T> {
    public var state: PromiseState
        private set

    /**
     * Attach a success callback.
     *
     * When this promise is fulfilled, or if this promise has already been fulfilled,
     * any function literals passed to this function will be called with the promises
     * value.
     */
    public fun thenAsync<O>(cb: Async<O>.(T) -> EndPromise<O>): Promise<O>
    public fun then<O>(cb: (T) -> O): Promise<O>

    /**
     * Attach an error callback.
     *
     * When this promise is broken, or if this promise has already been broken,
     * any function literals passed to this function will be called with the
     * associated exception.
     */
    public fun otherwiseAsync<O>(fn: Async<O>.(Exception) -> EndPromise<O>): Promise<O>
    public fun otherwise<O>(fn: (Exception) -> O): Promise<O>

    public fun fulfills(obligation: Obligation<T>)
}

/**
 * This is the private half that should be kept by the actor responsible for
 * fulfilling the associated promise.
 */
public trait Obligation<in T> {
    public var state: PromiseState
        private set

    /**
     * Fulfill the obligation.
     *
     * Fulfilling an obligation will mark the associated promise as fulfilled and
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

/*
public fun <T, O> Promise<T>.plus(other: Promise<O>): PromisePair<T, O> {
    return PromisePair(this, other)
}
*/

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

    // TODO: Name? (confusing?)
    override fun fulfills(obligation: Obligation<T>) {
        callbacks add { v -> schedule { obligation fulfill v } }
        catchers  add { v -> schedule { obligation abandon v } }
        flush()
    }

    override fun then<O>(cb: (T) -> O): Promise<O> {
        if (state == PromiseState.BROKEN) return NullPromise()
        val nextPromise = BasicPromise<O>()
        callbacks.add { v ->
            schedule{cb(v)} fulfills nextPromise
        }
        flush()
        return nextPromise
    }

    override fun thenAsync<O>(cb: Async<O>.(T) -> EndPromise<O>): Promise<O> {
        if (state == PromiseState.BROKEN) return NullPromise()
        val nextPromise = BasicPromise<O>()
        callbacks.add { v ->
            async<O>{cb(v)} fulfills nextPromise
        }
        flush()
        return nextPromise
    }

    override fun otherwise<O>(fn: (Exception) -> O): Promise<O> {
        if (state == PromiseState.FULFILLED) return NullPromise()
        val nextPromise = BasicPromise<O>()
        catchers.add {
            schedule{fn(it)} fulfills nextPromise
        }
        flush()
        return nextPromise
    }

    override fun otherwiseAsync<O>(fn: Async<O>.(Exception) -> EndPromise<O>): Promise<O> {
        if (state == PromiseState.FULFILLED) return NullPromise()
        val nextPromise = BasicPromise<O>()
        catchers.add {
             async<O>{fn(it)} fulfills nextPromise
        }
        flush()
        return nextPromise
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
    override fun fulfill(value: A) = promise.fulfill(this.value)
    override fun abandon(exception: Exception) = promise.abandon(exception)
}

// Inline stuff to avoid creating pointless closures. Does kotlin do this anyways?

public class TrivialPromise<T>(private val value: T): Promise<T> {
    override fun fulfills(obligation: Obligation<T>) = obligation fulfill value
    override var state: PromiseState = PromiseState.FULFILLED
    override fun <O> then(cb: (T) -> O): Promise<O> = schedule{cb(value)}
    override fun <O> thenAsync(cb: Async<O>.(T) -> EndPromise<O>): Promise<O> = async{cb(value)}

    final override inline fun otherwise<O>(fn: (Exception) -> O): Promise<O> = NullPromise()
    final override inline fun otherwiseAsync<O>(fn: Async<O>.(Exception) -> EndPromise<O>): Promise<O> = NullPromise()
}

public class EmptyPromise<T>(private val exception: Exception): Promise<T> {
    override var state: PromiseState = PromiseState.BROKEN
    override fun <O> otherwiseAsync(fn: Async<O>.(Exception) -> EndPromise<O>): Promise<O> = async{fn(exception)}
    override fun <O> otherwise(fn: (Exception) -> O): Promise<O> = schedule{fn(exception)}
    override fun fulfills(obligation: Obligation<T>) = obligation abandon exception

    final override inline fun <O> then(cb: (T) -> O): Promise<O> = NullPromise()
    final override inline fun <O> thenAsync(cb: Async<O>.(T) -> EndPromise<O>): Promise<O> = NullPromise()
}

public class NullPromise<T>(): Promise<T> {
    override var state: PromiseState = PromiseState.PENDING

    override fun fulfills(obligation: Obligation<T>) = Unit.VALUE

    final override inline fun <O> thenAsync(cb: Async<O>.(T) -> EndPromise<O>): Promise<O> = NullPromise<O>()
    final override inline fun <O> then(cb: (T) -> O): Promise<O> = NullPromise()
    final override inline fun <O> otherwiseAsync(fn: Async<O>.(Exception) -> EndPromise<O>): Promise<O> = NullPromise<O>()
    final override inline fun <O> otherwise(fn: (Exception) -> O): Promise<O> = NullPromise()
}


/*
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
*/


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

// You probably shouldn't use this...
public fun block<O>(promise: Promise<O>): O {
    // TODO: Throw exception if called from scheduler...
    val lock = Semaphore(1)
    var value: O? = null
    var exception: Exception? = null
    lock.acquire()
    promise then {
        value = it
        lock.release()
    }
    promise otherwise {
        exception = it
        lock.release()
    }
    lock.acquire()

    if (exception == null) {
        return value as O
    } else {
        throw exception!!
    }
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
        promise.thenAsync<Unit> { done(otherPromise fulfill it) }
    }
    private var caught = false
    private inline fun catchTemplate<E>(cls: Class<E>, fn: (E) -> Promise<T>): TryPromise<T> {
        promise otherwise {
            if (!caught && (it.javaClass.identityEquals(cls) || it.javaClass.isInstance(cls)))  {
                caught = true // No need to synchronize
                fn(it as E) fulfills otherPromise
            }
        }
        return this
    }
    public fun catchAsync<E>(cls: Class<E>, catcher: Async<T>.(E) -> EndPromise<T>): TryPromise<T> = catchTemplate(cls) { async{catcher(it)} }
    public fun catch<E>(cls: Class<E>, catcher: (E) -> T): TryPromise<T> = catchTemplate(cls) { schedule{catcher(it)} }

    // TEMPLATES!
    private inline fun finallyTemplate(fn: () -> Promise<Unit>): Promise<T> {
        val finalPromise = BasicPromise<T>()
        otherPromise then { value ->
            val finally = fn()
            finally then {
                finalPromise fulfill value
            }
            finally otherwise { error ->
                finalPromise abandon error
            }

        }
        otherPromise otherwise { error ->
            val finally = fn()
            finally then {
                finalPromise abandon error
            }
            finally otherwise { error2 ->
                finalPromise abandon error2
            }
        }
        return finalPromise
    }
    public fun finally(fn: () -> Unit): Promise<T> = finallyTemplate { schedule(fn) }
    public fun finallyAsync(fn: Async<Unit>.() -> EndPromise<Unit>): Promise<T> = finallyTemplate { async(fn) }
}

public fun whileAsync(condition: Async<Boolean>.() -> EndPromise<Boolean>, body: LoopBody.() -> EndPromise<Unit>): Promise<Unit> {
    val breakException = AsyncLoopException()
    val continueException = AsyncLoopException()
    val resultingPromise = BasicPromise<Unit>()

    async(condition) then {
        if (it) {
            val result = async<Unit>{LoopBody(breakException, continueException).body()}
            result then {
                whileAsync(condition, body) fulfills resultingPromise
            }
            result otherwise { e ->
                when (e) {
                    continueException ->    whileAsync(condition, body) fulfills resultingPromise
                    breakException ->       resultingPromise fulfill Unit.VALUE
                    else ->                 resultingPromise abandon e
                }
            }
        } else {
            resultingPromise fulfill Unit.VALUE
        }
    }
    return resultingPromise
}

public inline fun foreachAsync<T>(iterable: Iterable<T>, body: LoopBody.(T) -> EndPromise<Unit>): Promise<Unit> {
    return foreachAsync(iterable.iterator(), body)
}

public inline fun foreachAsync<T>(iterator: Iterator<T>, body: LoopBody.(T) -> EndPromise<Unit>): Promise<Unit> {
    return whileAsync({done(iterator.hasNext())}) {
        body(iterator.next())
    }
}

public inline fun foreachAsync<T>(iterable: AsyncIterable<T>, body: LoopBody.(T) -> EndPromise<Unit>): Promise<Unit> {
    return foreachAsync(iterable.iterator(), body)
}

public inline fun foreachAsync<T>(iterator: AsyncIterator<T>, body: LoopBody.(T) -> EndPromise<Unit>): Promise<Unit> {
    return whileAsync({await(iterator.hasNext())} : Async<Boolean>.() -> EndPromise<Boolean>) {
        body(iterator.next())
    }
}

public fun tryAsync<T>(fn: Async<T>.() -> EndPromise<T>): TryPromise<T> {
    return TryPromise(async(fn))
}

private class AsyncError(message: String): Error(message)

public open class Async<O> {
    private var awaited = false
    private fun check() {
        if (awaited) {
            val err = AsyncError("await/done may only be called once!")
            val trace = err.getStackTrace()
            JavaUtils.setStackTrace(err, Arrays.copyOfRange(trace, 2, trace.size))
            throw err
        }
        awaited = true
    }

    public fun <I> await(promise: Promise<I>, andThen: Async<O>.(I) -> EndPromise<O>): EndPromise<O> {
        return EndPromise(promise.thenAsync<O>(andThen))
    }
    public fun await(promise: Promise<O>): EndPromise<O> {
        check()
        return EndPromise(promise)
    }
    public fun done(value: O): EndPromise<O> {
        check()
        return EndPromise(TrivialPromise(value))
    }
    public fun done(): EndPromise<Unit> {
        check()
        return DONE
    }
}
public fun async<O>(fn: Async<O>.() -> EndPromise<O>): Promise<O> {
    val promise = BasicPromise<O>()
    scheduler.execute {
        try {
            Async<O>().fn() fulfills promise
        } catch (e: Exception) {
            promise abandon e
        } catch (e: AsyncError) {
            e.printStackTrace()
            // Kill program?
        }
    }
    return promise
}

public fun schedule<O>(fn: () -> O): Promise<O> {
    val promise = BasicPromise<O>()
    scheduler.execute {
        try {
            promise fulfill fn()
        } catch (e: Exception) {
            promise abandon e
        } catch (e: AsyncError) {
            e.printStackTrace()
            // Kill program?
        }
    }
    return promise
}
