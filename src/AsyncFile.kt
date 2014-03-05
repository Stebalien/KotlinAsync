/**
 * Created by steb on 3/5/14.
 */

fun java.io.File.readLinesAsync(): Promise<List<String>> = unblock {
    this.readLines()
}

fun java.io.File.readTextAsync(): Promise<String> = async {
    await(readLinesAsync()) { lines ->
    lines.makeString("\n")
}}
