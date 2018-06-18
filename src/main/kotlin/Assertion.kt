data class AssertionFailure(override val message: String, override val cause: Throwable? = null): Throwable(message, cause)

fun assert(that: () -> Boolean, message: String = "Assertion Failed") {
    if (!that()) {
        throw AssertionError(message, null)
    }
}

fun assert(that: Boolean, message: String = "Assertion Failed") {
    if(!that) {
        throw AssertionError(message, null)
    }
}

fun <T> T.assertEquals(that: T, message: String = "") {
    if(this != that) {
        throw AssertionError("$\nmessage\n\texpected $this\n\tto equal $that\n")
    }
}
