package com.example.springLanguages.python

import jakarta.annotation.PreDestroy
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.python.embedding.GraalPyResources
import org.springframework.stereotype.Component

@Component
class GraalPyContext {
    private val context: Context

    init {
        context = GraalPyResources
            .contextBuilder()
            .build() // ②
        context.initialize(PYTHON) // ③
    }

    fun eval(source: String): Value? {
        return context.eval(PYTHON, source) // ④
    }

    @PreDestroy
    fun close() {
        context.close(true) // ⑤
    }

    companion object {
        const val PYTHON: String = "python"
    }
}
