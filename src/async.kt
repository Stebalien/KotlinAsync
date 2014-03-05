/**
 * Created by steb on 2/24/14.
 */

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledExecutorService

public class UnfulfilledPromiseException: RuntimeException()

enum class PromiseState {
    PENDING
    FULFILLED
    BROKEN
    CHANGING
}

private val scheduler = Executors.newSingleThreadScheduledExecutor()
private val threadManager = Executors.newCachedThreadPool()

public trait Promise<T> {
    public fun then(cb: (T) -> Unit): Unit
    internal fun otherwise(fn: (Throwable) -> Unit): Unit

    public fun plus<O>(other: Promise<O>): PromisePair<T, O> {
        return PromisePair(this, other)
    }
    public var state: PromiseState
        private set
}

public trait Obligation<T> {
    public var state: PromiseState
        private set

    public fun fulfill(value: T): Unit
    public fun abandon(exception: Throwable): Unit

}

public trait OpenPromise<I, O>: Obligation<I>, Promise<O>

public class PromisePair<A, B>(private val promise1: Promise<A>, private val promise2: Promise<B>): Promise<Pair<A, B>> {
    override fun otherwise(fn: (Throwable) -> Unit) {
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

// TODO: Break promise on finalize?
public class BasicPromise<T>(): Promise<T>, OpenPromise<T, T> {
    override var state: PromiseState = PromiseState.PENDING
        get() = internalState.get()!!
    private var internalState = AtomicReference(PromiseState.PENDING)

    private val callbacks = ConcurrentLinkedQueue<(T) -> Unit>()
    private var value: T? = null
    private val catchers = ConcurrentLinkedQueue<(Throwable) -> Unit>() // Should we support multiple?? They'll all get called...
    private var throwable: Throwable? = null
    private val lock = AtomicBoolean(false)

    private fun flush() {
        // Continue flushing the callbacks while
        //   the state is either broken or fulfilled (completed),
        // until
        //   we see the queues empty and while not holding the lock.
        do {} while ((callbacks.notEmpty || catchers.notEmpty)
            && when (state) {
                PromiseState.BROKEN -> {
                    if (lock.compareAndSet(false, true)) {
                        try {
                            callbacks.clear()
                            val t = throwable!!
                            while (catchers.notEmpty) {
                                val fn = catchers.poll()!!
                                scheduler.submit({fn(t)})
                            }
                        } finally {
                            lock.set(false)
                        }
                        true
                    } else {
                        false
                    }
                }
                PromiseState.FULFILLED -> {
                    if (lock.compareAndSet(false, true)) {
                        try {
                            val v = value!!
                            while (callbacks.notEmpty) {
                                val fn = callbacks.poll()!!
                                scheduler.submit({fn(v)})
                            }
                            catchers.clear()
                        } finally {
                            lock.set(false)
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        )
    }

    override fun then(cb: (T) -> Unit) {
        callbacks.add(cb)
        flush()
    }

    override fun otherwise(fn: (Throwable) -> Unit) {
        if (state == PromiseState.FULFILLED) return
        catchers.add(fn)
        flush()
    }

    override fun abandon(exception: Throwable) {
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
    override fun abandon(exception: Throwable) = promise.abandon(exception)
}

public class TrivialPromise<T>(private val value: T): Promise<T> {
    override var state: PromiseState = PromiseState.FULFILLED
    override fun then(cb: (T) -> Unit) {
        // Catch exception?
        // Actually, just put it on the scheduler
        // TODO
        cb(value)
    }
    override fun otherwise(fn: (Throwable) -> Unit) {}
}

public class EmptyPromise<T>(private val exception: Throwable): Promise<T> {
    override var state: PromiseState = PromiseState.BROKEN
    override fun otherwise(fn: (Throwable) -> Unit) {
        fn(exception)
    }
    override fun then(cb: (T) -> Unit) {}
}

private class ThrowablePromise<T>(private val promise: Promise<T>): Exception(), Promise<T> by promise

private class AsyncLoopException(msg: String) : Exception(msg)

public class LoopBody internal (private val breakException: AsyncLoopException, private val continueException: AsyncLoopException): Async<Unit>() {
    // Break with exception???
    public fun abreak() {
        throw breakException
    }
    public fun acontinue() {
        throw continueException
    }
}

fun <T> Obligation<T>.receive(promise: Promise<T>) {
    promise then        { this fulfill it }
    promise otherwise   { this abandon it }
}

public class TryPromise<T>(private val promise: Promise<T>, private val otherPromise: BasicPromise<T> = BasicPromise()): Promise<T> by otherPromise {
    {
        promise then { otherPromise fulfill it }
    }
    private var caught = false
    public fun catch(catcher: Async<T>.(Throwable) -> T) {
        promise otherwise {
            if (!caught) {
                caught = true
                otherPromise receive async<T>{catcher(it)}
            }
        }
    }
    public fun catch<E>(cls: Class<E>, catcher: Async<T>.(E) -> T): TryPromise<T> {
        promise otherwise {
            if (!caught && (it.javaClass.identityEquals(cls) || it.javaClass.isInstance(cls)))  {
                caught = true
                otherPromise receive async<T>{catcher(it as E)}
            }
        }
        return this
    }
    // TODO: Deal with async finallies...
    public fun finally(fn: () -> Unit): TryPromise<T> {
        promise then {fn()}
        promise otherwise {fn()}
        return this
    }

}


public fun awhile(condition: Async<Boolean>.() -> Boolean, body: LoopBody.() -> Unit): Promise<Unit> {
    val breakException = AsyncLoopException("whileBreak")
    val continueException = AsyncLoopException("whileCtd")
    val bodyCtx = LoopBody(breakException, continueException)
    val resultingPromise = BasicPromise<Unit>()

    async(condition) then {
        if (it) {
            val result = async<Unit>({ bodyCtx.body() })
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
    val iterator = iterable.iterator()
    return awhile({iterator.hasNext()}) {
        await(async{body(iterator.next())})
    }
}

public fun atry<T>(fn: Async<T>.() -> T): TryPromise<T> {
    return TryPromise(async(fn))
}

public fun sleep(delay: Long, units: TimeUnit = TimeUnit.MILLISECONDS): Promise<Unit> {
    val p = BasicPromise<Unit>()
    scheduler.schedule({
        p.fulfill(Unit.VALUE)
    }, delay, units)
    return p
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

public open class Async<O> internal () {
    // Multiple return types would make this less insane...
    public fun await<I>(promise: Promise<I>, fn: Async<O>.(I) -> O): O {
        val endPromise = BasicPromise<O>()
        promise then {
            try {
                endPromise fulfill Async<O>().fn(it)
            } catch (e: ThrowablePromise<O>) {
                e then { endPromise fulfill it  }
            } catch (e: Exception) {
                endPromise abandon e
            }
        }
        promise otherwise {
            endPromise abandon it
        }
        throw ThrowablePromise(endPromise)
    }

    public fun await(promise: Promise<O>) {
        throw ThrowablePromise(promise)
    }

    public fun invoke(fn: Async<O>.() -> O): Promise<O> {
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
}

public fun async<O>(fn: Async<O>.() -> O): Promise<O> {
    return Async<O>().invoke(fn)
}
