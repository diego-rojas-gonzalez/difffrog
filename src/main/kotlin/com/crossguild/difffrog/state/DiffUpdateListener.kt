package com.crossguild.difffrog.state

import com.intellij.util.messages.Topic

interface DiffUpdateListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("DiffFrog Update Events", DiffUpdateListener::class.java)
    }

    fun onDiffUpdated(added: Int, deleted: Int)
}
