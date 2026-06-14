package io.github.earth1283.loom.lang

class Environment(val parent: Environment? = null) {
    private val values = mutableMapOf<String, Any?>()

    fun define(name: String, value: Any?) {
        values[name] = value
    }

    fun get(name: String, line: Int, col: Int): Any? {
        if (values.containsKey(name)) return values[name]
        if (parent != null) return parent.get(name, line, col)
        throw LoomError.Runtime(line, col, "Undefined variable '$name'")
    }

    fun set(name: String, value: Any?, line: Int, col: Int) {
        if (values.containsKey(name)) { values[name] = value; return }
        if (parent != null) { parent.set(name, value, line, col); return }
        throw LoomError.Runtime(line, col, "Undefined variable '$name'")
    }

    fun has(name: String): Boolean = values.containsKey(name) || (parent?.has(name) == true)

    fun child(): Environment = Environment(this)
}
