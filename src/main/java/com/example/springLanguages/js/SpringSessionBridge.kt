package com.example.springLanguages.js

import jakarta.servlet.http.HttpServletRequest
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.Proxy
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.concurrent.ConcurrentHashMap

class SpringSessionBridge : ProxyObject {

    @JvmField
    val getOrCreate = ProxyExecutable { args ->
        val req: Value? = args[0]
        val res: Value? = args[1]
        val options: Value? = args[2]
        val saveUninitialized = options?.getMember("saveUninitialized")?.let { v ->
            if (v.isBoolean) v.asBoolean() else null
        } ?: true

        SessionProxy(
            bridge = this,
            requestProvider = ::currentRequest,
            createOnWriteOnly = !saveUninitialized
        )
    }

    private fun currentRequest(): HttpServletRequest {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: error("No current request (RequestContextHolder is empty)")
        return attrs.request
    }

    internal fun toJsValue(raw: Any, sessionProxy: ProxyObject, attrName: String): Any {
        return when (raw) {
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                val list = raw as MutableList<Any?>
                ArrayProxy(this,list) {
                }
            }
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = raw as MutableMap<String, Any?>
                ObjectProxy(this,map) {}
            }
            else -> raw
        }
    }

    internal fun fromJsValue(v: Value?): Any? {
        if (v == null) return null

        if (!v.isProxyObject) {

            if (v.isNull) return null
            if (v.isBoolean) return v.asBoolean()
            if (v.isNumber) {
                // выбирай сам стратегию; так безопаснее для template/логики
                return if (v.fitsInInt()) v.asInt() else v.asDouble()
            }
            if (v.isString) return v.asString()

            if (v.hasArrayElements()) {
                val list = ArrayList<Any?>()
                val n = v.arraySize
                for (i in 0 until n) list.add(fromJsValue(v.getArrayElement(i)))
                return list
            }

            if (v.hasMembers()) {
                val map = ConcurrentHashMap<String, Any?>()
                for (k in v.memberKeys) {
                    val mv = v.getMember(k)
                    if (mv != null && mv.canExecute()) continue
                    map[k] = fromJsValue(mv)
                }
                return map
            }
        } else {
            return when (v.asProxyObject<Proxy>()) {
                is ArrayProxy -> v.asProxyObject<ArrayProxy>()
                is ObjectProxy -> v.asProxyObject<ObjectProxy>()
                else -> error("Unknown proxy object: $v")
            }
        }

        return v
    }

    override fun getMember(key: String?): Any? {
        return jsVisibleMembers[key]
    }

    override fun getMemberKeys(): Any {
        return ProxyArray.fromList(jsVisibleMembers.keys.toList())
    }

    override fun hasMember(key: String?): Boolean {
        return jsVisibleMembers.containsKey(key)
    }

    override fun putMember(key: String?, value: Value?) {

    }

    private val jsVisibleMembers = mapOf("getOrCreate" to getOrCreate)
}

/**
 * Proxy для req.session
 */
class SessionProxy(
    private val requestProvider: () -> HttpServletRequest,
    private val createOnWriteOnly: Boolean,
    val bridge: SpringSessionBridge
) : ProxyObject {

    override fun getMember(key: String): Any? {
        if (key == "id") {
            val s = requestProvider().getSession(false) ?: return null
            return s.id
        }
        if (key == "destroy") {
            return ProxyExecutable { args ->
                val req = requestProvider()
                req.getSession(false)?.invalidate()
                if (args.isNotEmpty() && args[0].canExecute()) args[0].execute(null)
                null
            }
        }

        val session = requestProvider().getSession(false) ?: return null
        val raw = session.getAttribute(key) ?: return null
        return bridge.toJsValue(raw, sessionProxy = this, attrName = key)
    }

    override fun putMember(key: String, value: Value?) {
        val req = requestProvider()
        val session = if (createOnWriteOnly) req.getSession(true) else req.getSession(true)

        val stored = bridge.fromJsValue(value)
        session.setAttribute(key, stored)
    }

    override fun removeMember(key: String): Boolean {
        val session = requestProvider().getSession(false) ?: return false
        session.removeAttribute(key)
        return true
    }

    override fun hasMember(key: String): Boolean {
        if (key == "id" || key == "destroy") return true
        val session = requestProvider().getSession(false) ?: return false
        return session.getAttribute(key) != null
    }

    override fun getMemberKeys(): Any {
        val session = requestProvider().getSession(false)
        val names = session?.attributeNames?.toList() ?: emptyList()
        return (names + listOf("id", "destroy")).distinct().toTypedArray()
    }
}

private class ArrayProxy(
    private val bridge: SpringSessionBridge,
    private val backing: MutableList<Any?>,
    private val onMutate: () -> Unit
) : ProxyObject {

    override fun getMember(key: String): Any? {
        return when (key) {
            "length" -> backing.size
            "push" -> ProxyExecutable { args ->
                for (a in args) backing.add(bridge.fromJsValue(a))
                onMutate()
                backing.size
            }
            "pop" -> ProxyExecutable {
                if (backing.isEmpty()) null else backing.removeAt(backing.lastIndex).also { onMutate() }
            }
            else -> {
                key.toIntOrNull()?.let { idx -> backing.getOrNull(idx) } ?: null
            }
        }
    }

    override fun putMember(key: String, value: Value?) {
        val idx = key.toIntOrNull()
        if (idx != null) {
            while (backing.size <= idx) backing.add(null)
            backing[idx] = bridge.fromJsValue(value)
            onMutate()
        }
    }

    override fun hasMember(key: String): Boolean {
        if (key == "length" || key == "push" || key == "pop") return true
        val idx = key.toIntOrNull() ?: return false
        return idx in 0 until backing.size
    }

    override fun getMemberKeys(): Any {
        val keys = ArrayList<String>(backing.size + 3)
        keys.add("length"); keys.add("push"); keys.add("pop")
        for (i in backing.indices) keys.add(i.toString())
        return keys.toTypedArray()
    }

    override fun removeMember(key: String): Boolean {
        val idx = key.toIntOrNull() ?: return false
        if (idx !in 0 until backing.size) return false
        backing.removeAt(idx)
        onMutate()
        return true
    }
}

private class ObjectProxy(
    private val bridge: SpringSessionBridge,
    private val backing: MutableMap<String, Any?>,
    private val onMutate: () -> Unit
) : ProxyObject {

    override fun getMember(key: String): Any? = backing[key]
    override fun putMember(key: String, value: Value?) {
        backing[key] = bridge.fromJsValue(value)
        onMutate()
    }
    override fun removeMember(key: String): Boolean = (backing.remove(key) != null).also { if (it) onMutate() }
    override fun hasMember(key: String): Boolean = backing.containsKey(key)
    override fun getMemberKeys(): Any = backing.keys.toTypedArray()
}