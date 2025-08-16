package com.android.systemui.keyguard.domain.interactor

object PendingPinInput {
    @JvmStatic
    var digits: String = ""

    @JvmStatic
    fun addDigit(d: Char) {
        digits += d
    }

    @JvmStatic
    fun get(): String {
        return digits
    }

    @JvmStatic
    fun reset() {
        digits = ""
    }
}

