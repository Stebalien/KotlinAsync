/**
 * Created by steb on 3/5/14.
 */

import java.util.NoSuchElementException
import java.io.File
import java.io.BufferedReader
import java.util.ArrayList

fun BufferedReader.readLineAsync(): Promise<String?> = unblock {
    readLine()
}

// This is not how you would actually do this. If you wanted it to be efficient,
// you would probably fork off a single reader thread and have it put lines
// into a queue.
// Also, this isn't thread safe (but iterators shouldn't be shared anyways).
private class AsyncFileIterator(private val file: File): AsyncIterator<String> {
    private val bufferedReader = file.reader().buffered()

    var next: String? = null
    override fun next(): String {
        val tmp = next
        if (tmp == null) {
            throw NoSuchElementException()
        }
        next = null
        return tmp
    }
    override fun hasNext(): Promise<Boolean> = async {
        if (next != null) {
            done(true)
        } else {
            await(bufferedReader.readLineAsync()) { line ->
            if (line == null) {
                done(false)
            } else {
                next = line
                done(true)
            }
        }}
    }
}

fun java.io.File.readLinesAsync(): AsyncIterator<String> {
    return AsyncFileIterator(this)
}

fun <T> AsyncIterator<T>.toList(): Promise<List<T>> {
    val that = this // Should't be a problem in the final version
    return async {
        val list = ArrayList<T>()
        await(aforeach(that) {
            list.add(it)
            done()
        }){
        done(list)
    }}
}

fun File.readTextAsync(): Promise<String> = async {
    // Totally inefficient. This is just a demonstration.
    await(readLinesAsync().toList()) {
        done(it.makeString("\n"))
    }
}
