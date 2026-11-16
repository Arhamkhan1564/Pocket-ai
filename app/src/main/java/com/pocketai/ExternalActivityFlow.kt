package com.pocketai

/** Keeps an existing, expiring administrator session through an explicit system document picker. */
object ExternalActivityFlow {
    @Volatile var active: Boolean = false
}
