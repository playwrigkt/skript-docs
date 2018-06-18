# Skript Core

This module provides the basis for skript composition.

# Skript

One of the simplest skripts possible just transforms an integer to a String.

```kotlin:ank
import playwrigkt.skript.Skript
import playwrigkt.skript.result.AsyncResult

val skript = Skript.map<Int, String, Unit> { it.toString() }

skript.run(10, Unit).assertEquals(AsyncResult.succeeded("10"))
skript.run(30, Unit).assertEquals(AsyncResult.succeeded("30"))
```

This example doesn't do much that we don't get out of the box with most
programming languages, so it isn't really showing off what Skripts really
can do. However, its a good place to start examining skript behavior.

In the first line, we create a `map` skript, which simply executes a
synchronous function (we'll get to asynchronous soon).  The skript has
three type parameters: `<Input, Output, Stage>`, input and output are
pretty self explanatory.  This simple example doesn't require a troupe
so the `Unit` value suffices. and we'll get to Stage later.

This skript is essentially a function that takes in type Int and returns
an asynchronous result with a String in it. Since the map skript is
implemented synchronously the result is immediately available,but this
is not always the case with skripts.

The second and third lines run the skript.  The second parameter is the Troupe,
and the value `Unit` declares that the troupe provides no additional functionality.

### Skripts all the way down

Skripts are a recursive data structure.  The skript interface exposes methods
that take other skripts as parameters.  For example, a skript can define a
database query or command, but a skript that represents a complex transaction
would likely be composed of several such skripts.

### Composing skripts

Chaining skripts is one of the most fundamental and powerful features of
skripts.  Skripts are stored in memory as a single-linked list of skripts.
For any skript in the chain, its output is the input for the next skript,
until the end of the chain.  The last skript's output type is the output
type of the chain.byby using Unit we are saying using Unit we are saying

Here is a simple example of a chained skript:

```kotlin:ank
import playwrigkt.skript.Skript
import playwrigkt.skript.result.AsyncResult

val skript = Skript
        .map<Int, String, Unit> { it.toString() }
        .map { it.toLong() * 2 }

skript.run(10, Unit).result().assertEquals(20L)
skript.run(5, Unit).result().assertEquals(10L)
skript.run(200, Unit).result().assertEquals(400L)
```

Again, this skript performs a couple of trivial operations.  The first
skript in the chain transforms an int to a String, and the second skript
transforms the string into a long and doubles it.

### Branching skripts

Skripts also offer branching mechanisms.  A simple branching skript may
perform simple mathematical calculations:

```kotlin:ank
import arrow.core.Either
import playwrigkt.skript.Skript
import playwrigkt.skript.result.AsyncResult

val double = Skript.map<Int, Int, Unit> { it * 2 }
val half = Skript.map<Int, Int, Unit> { it / 2 }
val rightIfGreaterThanTen = Skript.map<Int, Either<Int, Int>, Unit> {
    if(it > 10) {
        Either.right(it)
    } else {
        Either.left(it)
    }
}


val skript = Skript.branch(rightIfGreaterThanTen)
        .left(double)
        .right(half)

skript.run(5, Unit).result().assertEquals(10)
skript.run(16, Unit).result().assertEquals(8)
```

The above skript either halves or double, depending on the result of
`rightIfGreaterThanTen`.  `control` returns an `Either`.  When the result
of that skript is an `Either.Right`, then the skript `right` is executed,
otherwise `left` is executed.

### Combining composition and branching

Things get more interesting when you are also mapping values within your
branching logic:

```kotlin:ank
import arrow.core.Either
import playwrigkt.skript.Skript
import playwrigkt.skript.result.AsyncResult
import playwrigkt.skript.ex.andThen

val double: Skript<Double, Double, Unit> = Skript.map { it * 2 }
val stringLength = Skript.map<String, Int, Unit> { it.length }
val toLong: Skript<Number, Long, Unit> = Skript.map { it.toLong() }

val rightIfGreaterThanTen = Skript.map<Int, Either<Double, String>, Unit> {
    if(it > 10) {
        Either.right(it.toString())
    } else {
        Either.left(it.toDouble())
    }
}

val skript = Skript.branch(rightIfGreaterThanTen)
        .left(double.andThen(toLong))
        .right(stringLength.andThen(toLong))

skript.run(5, Unit).assertEquals(AsyncResult.succeeded(10L))
skript.run(16, Unit).assertEquals(AsyncResult.succeeded(2L))
```

This example not only branches, but also transforms before branching.
The logic here can be summarized as follows: if the input is greater than
ten, transform it into a string and return the length as a Long;
otherwise convert the input to a Double, then double the value and
convert it into a long.

The logic that happens in both branches has two steps: transform, and
then convert to a long.  Noticing this, we can take advantage of the
composability of skripts.  The left and right values, both of them call
one skript, and then chain into another:


While this example implements trivial logic it explained several key
concepts: skript branching and skript composition

The next sections will explore how to use these features to implement
more meaningful functionality.

## Parallellized skripts

Two skripts can be run concurrently using `both`

```kotlin:ank
import playwrigkt.skript.Skript
import playwrigkt.skript.ex.join
import playwrigkt.skript.result.AsyncResult

val sum: Skript<List<Int>, Int, Unit> = Skript.map { it.sum() }
val length: Skript<List<*>, Int, Unit> = Skript.map { it.size }

val average = Skript.both(sum, length).join { sum, length -> sum.toDouble() / length }

val input = listOf(1, 3, 5, 6, 3)
average.run(input, Unit).result().assertEquals(input.average())
```

In this example, sum and length are run, and then the results are put into a Pair.
The join extension doesn't do anything but take sum and length out of their pair,
but it provides a readable way to compose parallellism.

`split` is a special case of `both`.  In a split, the left skript is the identity skript.
`split` essentially allows you to preserve the output from one skript and combine it with
the output of another skript.  One place where split is super useful is when performing an
update that does not return an object, but you still want to use the original input.

```kotlin:ank
import playwrigkt.skript.Skript
import playwrigkt.skript.ex.join
import playwrigkt.skript.result.AsyncResult

val initialSkript = Skript.identity<String, Unit>()
val mapping: Skript<String, Int, Unit> = Skript.map { it.length }

val skript = initialSkript
        .split(mapping)
        .join { str, length -> "$str is $length characters long" }

skript.run("abcde", Unit).result().assertEquals("abcde is 5 characters long")
```

## Troupes and Performers

TODO