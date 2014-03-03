/**
 * Created by steb on 2/24/14.
 */

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import PromiseState.BROKEN
import PromiseState.PENDING

public class UnfulfilledPromiseException: RuntimeException()

enum class PromiseState {
    PENDING
    FULFILLED
    BROKEN
}

public trait Promise<T> {
    public fun then<O>(cb: async.(T) -> Promise<O>): Promise<O>
    // TODO: Doesn't work
    //public fun then<O>(cb: async.(T) -> O): Promise<O> = then {TrivialPromise(this.cb(it))}
    internal fun catchAll(fn: async.(e: Throwable) -> Promise<Unit>): Promise<Unit>
    public fun finally(fn: async.() -> Promise<Unit>): Promise<Unit>
    public fun plus<O>(other: Promise<O>): PromisePair<T, O> {
        return PromisePair(this, other)
    }
    public var state: PromiseState
        private set
}

public trait Obligation<T> {
    public var state: PromiseState
        private set

    public fun fulfill(v: T): Unit
    public fun raise(e: Throwable): Unit

}
public class PromiseChainBypass<I, O>(private val promise: PromiseChain<*, O>, private val bypassValue: O): Obligation<I> {
    override var state: PromiseState = promise.state
        get() = promise.state
    override fun fulfill(v: I): Unit =  promise.bypass(bypassValue)
    override fun raise(e: Throwable): Unit = promise.raise(e)
}

public trait OpenPromise<I, O>: Obligation<I>, Promise<O>


public class PromisePair<A, B>(private val promise1: Promise<A>, private val promise2: Promise<B>): Promise<Pair<A, B>> {
    override fun catchAll(fn: async.(Throwable) -> Promise<Unit>): Promise<Unit> {
        throw UnsupportedOperationException()
    }
    override fun finally(fn: async.() -> Promise<Unit>): Promise<Unit> {
        throw UnsupportedOperationException()
    }
    override var state: PromiseState = PromiseState.PENDING
        get() = when {
            promise1.state == PromiseState.FULFILLED && promise2.state == PromiseState.FULFILLED -> PromiseState.FULFILLED
            promise1.state == PromiseState.BROKEN, promise2.state == PromiseState.BROKEN -> PromiseState.BROKEN
            else -> PromiseState.PENDING
        }

    override fun <O> then(cb: async.(Pair<A, B>) -> Promise<O>): Promise<O> {
        return promise1.then { value1 ->
            promise2.then { value2 ->
                cb(Pair(value1, value2))
            }
        }
    }
}

// TODO: Break promise on finalize?
public class BasicPromise<T>(): Promise<T>, OpenPromise<T, T> {
    override var state: PromiseState = PromiseState.PENDING

    private val callbacks = ConcurrentLinkedQueue<Obligation<T>>()
    private var value: T? = null
    private val catchers = ConcurrentLinkedQueue<Obligation<Throwable>>() // Should we support multiple?? They'll all get called...
    private var throwable: Throwable? = null
    private val lock = Semaphore(1) // Because we need a non-reentrant lock.

    private fun <T> Iterable<Obligation<T>>.fulfill(v: T) {
        this.forEach {
            try {
                it.fulfill(v)
            } catch (e: Throwable) {
                it.raise(e)
            }
        }
    }

    [tailRecursive]
    private fun flush() {
        // Continue flushing the callbacks while (a) they aren't empty and (b) we aren't holding the lock
        if (lock.tryAcquire()) {
            try {
                when (state) {
                    PromiseState.PENDING -> return
                    PromiseState.BROKEN -> {
                        callbacks.clear()
                        catchers.fulfill(throwable!!)
                    }
                    PromiseState.FULFILLED -> {
                        callbacks.fulfill(value!!)
                        catchers.clear()
                    }
                }
            } finally {
                lock.release()
            }
            // Need to check outside of the lock.
            if (callbacks.notEmpty || catchers.notEmpty) {
                flush()
            }
        }
    }

    override fun then<O>(cb: async.(T) -> Promise<O>): Promise<O> {
        val future = PromiseChain(cb)
        callbacks.add(future)
        flush()
        return future
    }

    override fun catchAll(fn: async.(Throwable) -> Promise<Unit>): Promise<Unit> {
        if (state == PromiseState.FULFILLED) return async.done()
        val promise = PromiseChain(fn)
        callbacks.add(PromiseChainBypass(promise, Unit.VALUE))
        catchers.add(promise)
        flush()
        return promise
    }
    override fun finally(fn: async.() -> Promise<Unit>): Promise<Unit> {
        if (state != PromiseState.PENDING) return async.fn()
        val promise = PromiseChain<Any?, Unit>({async.fn()})
        catchers.add(PrepaidPromise(promise, Unit.VALUE))
        callbacks.add(PrepaidPromise(promise, Unit.VALUE))
        flush()
        return promise
    }

    override fun raise(e: Throwable) {
        synchronized(state) {
            if (state != PromiseState.PENDING) throw IllegalStateException("Promise already fulfilled.")
            throwable = e
            state = PromiseState.BROKEN
        }
        flush()
    }

    override fun fulfill(value: T) {
        synchronized(state) {
            if (state != PromiseState.PENDING) throw IllegalStateException("Promise already fulfilled.")
            this.value = value
            state = PromiseState.FULFILLED
        }
        flush()
    }
}

public class PrepaidPromise<A, I, O>(private val promise: OpenPromise<I, O>, private val value: I): OpenPromise<A, O>, Promise<O> by promise {
    override fun fulfill(v: A) {
        promise.fulfill(value)
    }
    override fun raise(e: Throwable) = promise.raise(e)
}

public class TrivialPromise<T>(private val value: T): Promise<T> {
    override var state: PromiseState = PromiseState.FULFILLED
    override fun then<O>(cb: async.(T) -> Promise<O>): Promise<O> = try {
        async.cb(value)
    } catch (e: Throwable) {
        EmptyPromise(e)
    }
    override fun catchAll(fn: async.(Throwable) -> Promise<Unit>): Promise<Unit> = async.done()
    override fun finally(fn: async.() -> Promise<Unit>): Promise<Unit> = async.fn()
}

public class EmptyPromise<T>(private val exception: Throwable): Promise<T> {
    override var state: PromiseState = PromiseState.BROKEN

    override fun catchAll(fn: async.(Throwable) -> Promise<Unit>): Promise<Unit> = try {
        async.fn(exception)
    } catch (e: Throwable) { EmptyPromise(e) }

    override fun finally(fn: async.() -> Promise<Unit>): Promise<Unit> = try {
        async.fn()
        EmptyPromise(exception)
    } catch (e: Throwable) { EmptyPromise(e) }

    override fun then<O>(cb: async.(T) -> Promise<O>): Promise<O> = EmptyPromise(exception)
}

public class PromiseChain<I, O> public (
        private val fn: async.(I) -> Promise<O>,
        private val intermediate: BasicPromise<O> = BasicPromise<O>() // How do I do this without exposing it...
): Promise<O> by intermediate, OpenPromise<I, O> {

    override fun raise(e: Throwable) {
        intermediate.raise(e)
    }

    override fun fulfill(v: I) {
        // TODO: Two different meanings of state = FULFILLED.
        var result: Promise<O>
        try {
            result = async.fn(v)
        } catch (e: Throwable) {
            intermediate.raise(e)
            return
        }
        result.then {
            intermediate.fulfill(it)
            done()
        } catchAll {
            intermediate.raise(it)
            done() // Don't care
        }
    }

    public fun bypass(v: O) {
        intermediate.fulfill(v)
    }
}


public object async {
    public fun done(): Promise<Unit> = TrivialPromise(Unit.VALUE)
    public fun done<T>(value: T): Promise<T> = TrivialPromise(value)
    public fun wait(delay: Int): Promise<Unit> {
        println("waiting...${delay}")
        return done() // Actually do something...
    }
    public fun loop(condition: async.() -> Promise<Boolean>, fn: async.() -> Promise<Unit>): Promise<Unit> {
        return condition() then {
            when (it) {
                true -> fn() then { loop(condition, fn) }
                else -> done()
            }
        }
    }
}

public fun async<O>(fn: async.() -> Promise<O>): Promise<O> = try {
    async.fn()
} catch (e: Throwable) {
    EmptyPromise(e)
}
