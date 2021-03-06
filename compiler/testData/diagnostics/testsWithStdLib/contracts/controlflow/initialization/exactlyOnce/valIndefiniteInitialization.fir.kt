// !LANGUAGE: +AllowContractsForCustomFunctions +UseCallsInPlaceEffect
// !USE_EXPERIMENTAL: kotlin.contracts.ExperimentalContracts
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER

import kotlin.contracts.*

fun <T> myRun(block: () -> T): T {
    contract {
        <!INAPPLICABLE_CANDIDATE!>callsInPlace<!>(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

fun myRepeat(n: Int, action: () -> Unit) {
    contract {
        <!INAPPLICABLE_CANDIDATE!>callsInPlace<!>(action)
    }
    for (i in 1..n) action()
}

fun branchingIndetermineFlow(a: Any?) {
    val x: Int

    if (a is String) {
        myRepeat(a.length) {
            // Val reassignment because we know that repeat's lambda called in-place
            myRun { x = 42 }
        }
    }
    else {
        myRun { x = 43 }
    }

    x.inc()
}

fun nonAnonymousLambdas() {
    val x: Int
    val initializer = { x = 42 }
    myRun(initializer)
    x.inc()
}

fun multipleAssignments() {
    val x: Int
    myRepeat(42) {
        // Val reassignment because we know that repeat's lambda called in-place
        myRun { x = 42 }
    }
    x.inc()
}

fun funWithUnknownInvocations(block: () -> Unit) = block()

fun nestedIndefiniteAssignment() {
    val x: Int
    // Captured val initialization reported, because we don't know anything about funWithUnknownInvocations
    funWithUnknownInvocations { myRun { x = 42 } }
    x.inc()
}

class InitializationForbiddenInNonInitSection {
    val x: Int

    fun setup() {
        myRun { x = 42 }
    }
}