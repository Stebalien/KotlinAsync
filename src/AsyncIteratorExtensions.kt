import java.util.ArrayList
import java.util.LinkedList
import java.util.LinkedHashSet
import java.util.SortedSet
import java.util.TreeSet


private class AsyncIndexIterator<T>(val iterator : AsyncIterator<T>): AsyncIterator<Pair<Int, T>> {
    private var index : Int = 0

    override fun next(): Pair<Int, T> = Pair(index++, iterator.next())
    override fun hasNext(): Promise<Boolean> = iterator.hasNext()
}

public fun <T, C: MutableCollection<in T>> AsyncIterator<T>.toCollection(result: C) : Promise<C> {
    return foreachAsync(this) {
        result.add(it)
        done()
    } then { result }
}

public fun <E> AsyncIterator<E>.toList(): Promise<List<E>> = toCollection(ArrayList<E>())
public fun <E> AsyncIterator<E>.toLinkedList(): Promise<LinkedList<E>> = toCollection(LinkedList<E>())
public fun <E> AsyncIterator<E>.toSet(): Promise<Set<E>> = toCollection(LinkedHashSet<E>())
public fun <E> AsyncIterator<E>.toSortedSet(): Promise<SortedSet<E>> = toCollection(TreeSet<E>())
public fun <E> AsyncIterator<E>.withIndices() : AsyncIterator<Pair<Int, E>> = AsyncIndexIterator(this)

