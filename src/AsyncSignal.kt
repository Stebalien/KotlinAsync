
import java.lang.ref.WeakReference
import java.util.NoSuchElementException

private class MultiQueue<T> {
    private class Node<T>(public val value: T) {
        public val link: Link<T> = Link<T>()
    }
    private class Link<T>() {
        public volatile var next: Node<T>? = null
    }
    public class MultiQueueReader<T>(private var tail: Link<T>) {
        public fun take(): T {
            val next = tail.next ?: throw NoSuchElementException()
            tail = next.link
            return next.value
        }
        public fun isEmpty(): Boolean = tail.next == null
    }

    private volatile var head = Link<T>()

    public fun add(value: T) {
        val nextNode = Node(value)
        head.next = nextNode
        head = nextNode.link
    }
    public fun newReader(): MultiQueueReader<T> {
        return MultiQueueReader(head)
    }
}
public class Signal<T>: AsyncIterable<T> {
    private volatile var nextPromise = BasicPromise<Boolean>()
    public  volatile var closed: Boolean = false
        private set

    public val queue: MultiQueue<T> = MultiQueue<T>()
    // TODO: Verify that garbage collection will work properly:
    // 1. Unreferenced iterators must be GCed
    // 2. Iterators should stop when the reference to the Signal is lost
    private class SignalIterator<T>(that: Signal<T>): AsyncIterator<T> {
        private val weakThat = WeakReference(that)
        private val queue = that.queue.newReader()

        // Can replace with single reader single writer queue.
        override fun hasNext(): Promise<Boolean> {
            // Don't touch this.
            val localNextPromise = weakThat.get()?.nextPromise
            if (queue.isEmpty()) {
                return localNextPromise ?: TrivialPromise(false)
            } else {
                return TrivialPromise(true)
            }
        }
        override fun next(): T = queue.take()
    }

    override fun iterator(): AsyncIterator<T> {
        return SignalIterator(this)
    }

    public fun fire(value: T) {
        synchronized(queue) {
            if (closed) throw IllegalStateException()
            queue.add(value)
            nextPromise.fulfill(true)
            nextPromise = BasicPromise<Boolean>()
        }
    }

    public fun close() {
        synchronized(queue) {
            closed = true
            nextPromise.fulfill(false)
        }
    }

    // Should I really go to these lengths to prevent the user from shooting him or herself in the foot?
    // Hint: This is a proxy to prevent casting.
    public fun safe(): AsyncIterable<T> = object : AsyncIterable<T> by this {}
}