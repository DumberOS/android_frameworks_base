package com.android.systemui.keyguard.domain.interactor

object PendingPinInput {
    fun interface Listener {
        fun onPendingPinChanged()
    }

    @JvmStatic
    var digits: String = ""

    private val listeners = ArrayList<Listener>()

    @JvmStatic
    fun addDigit(d: Char) {
        digits += d
        notifyListeners()
    }

    @JvmStatic
    fun get(): String {
        return digits
    }

    @JvmStatic
    fun take(): String {
        val pending = digits
        digits = ""
        return pending
    }

    @JvmStatic
    fun reset() {
        digits = ""
    }

    @JvmStatic
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onPendingPinChanged()
        }
    }
}
