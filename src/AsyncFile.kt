/**
 * Created by steb on 3/5/14.
 */

import java.util.NoSuchElementException
import java.io.File
import java.io.BufferedReader

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
    override fun hasNext(): Promise<Boolean> = async<Boolean> {
        if (next != null) {
            done(true)
        } else {
            await(bufferedReader.readLineAsync().then { line ->
                if (line == null) {
                    false
                } else {
                    next = line
                    true
                }
            })
        }
    }
}

fun java.io.File.readLinesAsync(): AsyncIterator<String> {
    return AsyncFileIterator(this)
}

// Totally inefficient. This is just a demonstration.
fun File.readTextAsync(): Promise<String> = readLinesAsync().toList() then { it.makeString("\n") }
