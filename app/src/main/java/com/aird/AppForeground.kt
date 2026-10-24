package com.aird

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

object AppForeground {
    fun install() {
        ProcessLifecycleOwner.get()
    }

    fun resumed(): Boolean {
        install()
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
}
