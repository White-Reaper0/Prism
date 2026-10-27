package com.nevcortex.prism.core.util

/**
 * Fixed-capacity ring buffer. Oldest entries are overwritten when full. Used for live plots and
 * rolling statistics so the UI never retains unbounded history. Not thread-safe by itself; the
 * callers here either own it on a single thread or guard access.
 */
class BoundedRingBuffer<T>(val capacity: Int) {
    private val buf: Array<Any?> = arrayOfNulls(capacity)
    private var head = 0     // next write index
    var size = 0
        private set

    fun add(item: T) {
        buf[head] = item
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Suppress("UNCHECKED_CAST")
    fun snapshot(): List<T> {
        if (size == 0) return emptyList()
        val out = ArrayList<T>(size)
        val start = if (size < capacity) 0 else head
        repeat(size) { i ->
            out.add(buf[(start + i) % capacity] as T)
        }
        return out
    }

    fun clear() {
        head = 0
        size = 0
        buf.fill(null)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(i: Int): T {
        require(i in 0 until size) { "index $i out of [0,$size)" }
        val start = if (size < capacity) 0 else head
        return buf[(start + i) % capacity] as T
    }
}
