package com.crossguild.difffrog.config

import com.intellij.util.messages.Topic

interface DiffFrogConfigListener {
    companion object {
        val TOPIC = Topic.create("DiffFrog Config Change", DiffFrogConfigListener::class.java)
    }
    fun onConfigChanged(newConfig: DiffFrogConfig)
}
