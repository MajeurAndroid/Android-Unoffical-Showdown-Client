package com.majeur.psclient.service

import android.os.Handler
import android.os.Looper

class ActionQueue(looper: Looper) {

    private data class Action(val action: ()->Unit, val delay: Long, val isTurn: Boolean)

    private val handler = Handler(looper)

    private val actions = mutableListOf<Action>()
    private var lastAction: (()->Unit)? = null

    var isLooping = false
        private set

    var shouldLoopToLastTurn = true
    var enableLastActionInvoke = false

    fun clear() {
        stopLoop()
        lastAction = null
        actions.clear()
    }

    fun setLastAction(action: (()->Unit)?) {
        lastAction = action
    }

    fun enqueueTurnAction(action: ()->Unit) {
        val turnActionInQueue = actions.any { it.isTurn }
        enqueue(action, 0, isTurn = true)

        // Only skip to the latest turn if we are watching a live battle ie. when shouldLoopToLastTurn is false.
        // Otherwise, do each turn in the queue one at a time
        if (shouldLoopToLastTurn && turnActionInQueue) {
            loopTo(action)
        }
    }

    fun insertAction(action: ()->Unit) {
        insert(action, 0, isTurn = false)
    }

    private fun insert(action: ()->Unit, delay: Long, isTurn: Boolean) {
        actions.add(0, Action(action, delay, isTurn))
        if (!isLooping) startLoop()
    }

    fun enqueueAction(action: ()->Unit) {
        enqueue(action, 0, isTurn = false)
    }

    fun enqueueMajorAction(action: ()->Unit) {
        enqueue(action, 1500, isTurn = false)
    }

    fun enqueueMinorAction(action: ()->Unit) {
        enqueue(action, 750, isTurn = false)
    }

    private fun enqueue(action: ()->Unit, delay: Long, isTurn: Boolean) {
        actions.add(Action(action, delay, isTurn))
        if (!isLooping) startLoop()
    }

    fun startLoop() {
        if (isLooping) return
        if (actions.isNotEmpty()) {
            isLooping = true
            handler.post(loopRunnable)
        } else if (enableLastActionInvoke && lastAction != null) {
            lastAction!!.invoke()
            lastAction = null
        }
    }

    fun stopLoop() {
        if (!isLooping) return
        isLooping = false
        handler.removeCallbacks(loopRunnable)
    }

    fun skipToNextTurn() {
        if (actions.isEmpty()) return
        if (actions.any { it.isTurn })
            loopTo(actions.first { it.isTurn }.action)
        else
            loopTo({}) // This will loop to the end
    }

    private fun loopTo(targetAction: ()->Unit) {
        val restartLoop = isLooping
        stopLoop()
        do {
            val action = actions.removeAt(0).action
            action.invoke()
        } while (actions.isNotEmpty() && action != targetAction)
        if (restartLoop) startLoop()
    }

    private val loopRunnable = object : Runnable {

        override fun run() {
            val entry = actions.removeAt(0)
            entry.action.invoke()
            if (actions.any()) {
                handler.postDelayed(this, entry.delay)
            } else {
                stopLoop()
                if (enableLastActionInvoke) {
                    lastAction?.invoke()
                    lastAction = null
                }
            }
        }
    }

}
