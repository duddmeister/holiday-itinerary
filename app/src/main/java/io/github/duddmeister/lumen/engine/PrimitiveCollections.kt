package io.github.duddmeister.lumen.engine

/** Growable int array without boxing; used on hot paths of the engine. */
class IntList(capacity: Int = 16) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    fun clear() {
        size = 0
    }

    fun contains(value: Int): Boolean {
        for (i in 0 until size) if (data[i] == value) return true
        return false
    }
}

/** Binary max-heap of longs; callers pack priority and payload into one long. */
class LongMaxHeap(capacity: Int = 64) {
    private var heap = LongArray(capacity)
    var size = 0
        private set

    fun isEmpty() = size == 0

    fun clear() {
        size = 0
    }

    fun push(value: Long) {
        if (size == heap.size) heap = heap.copyOf(size * 2)
        var i = size++
        heap[i] = value
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (heap[parent] >= heap[i]) break
            val t = heap[parent]; heap[parent] = heap[i]; heap[i] = t
            i = parent
        }
    }

    fun pop(): Long {
        val top = heap[0]
        size--
        if (size > 0) {
            heap[0] = heap[size]
            var i = 0
            while (true) {
                val l = 2 * i + 1
                if (l >= size) break
                val r = l + 1
                val c = if (r < size && heap[r] > heap[l]) r else l
                if (heap[i] >= heap[c]) break
                val t = heap[i]; heap[i] = heap[c]; heap[c] = t
                i = c
            }
        }
        return top
    }
}
