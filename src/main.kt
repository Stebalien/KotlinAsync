/**
 * Created by steb on 2/9/14.
 */



fun main(args: Array<String>) {
    async {
        val pa = wait(20)
        val pb = wait(20)
        // Wait on them together.
        pa + pb             then {
        async {
            var i = 0
            // An async loop... =D
            loop({done(i++ < 5)}) {
                wait(30)    then {
                println(i)
                // Because I always need to return a promise. I wish I could make this automatic
                // If there was some way to make something implement a trait, I could T implement T
                done()
            }}              then { // And we always need a then :(
            each(1..10) {
                println(it)
                done()
            }               then {
            throw IllegalStateException("Here")
            done()
        }}} catchAll { e ->
            println("first catch")
            throw e
        } catchAll { e ->
            // Unfortunately, this actually wraps the previous catch...
            // That's why I don't let the user catch individual exceptions...
            println("second catch")
            throw e
        } finally {
            println("regardless!")
            done()
        }                   then {
            println("not reached")
            done()
        }
    }} catchAll { e ->
        when (e) {
            is IllegalStateException -> println("Thrown!")
        }
        throw e
        done()
    }
    // Nothing thrown in the end because we *could* have attached a catch... Not good...

    /*
        Thoughts...

        Define a special operator `=>` such that

            fun test(): Int {
                a() => b
                b() => c
                return c
            }

        Is equivalent to:

            fun test() {
         return a() then { b ->
                b() then { c ->
                c
            }}}

        Basically, `a => b` calls `then` on `a` with a function literal that
            (1) contains the rest of the block and
            (2) accepts an argument `b`
        and sets the result as the value of the current block.

        However, this doesn't really help with control flow...
     */
}