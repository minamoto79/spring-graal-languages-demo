package com.example.springLanguages.js

import org.graalvm.polyglot.*
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

data class JsHttpRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: ByteArray
)

data class JsHttpResponse (
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray
)



@Service
class JsRuntimeService(
    private val resourceLoader: ResourceLoader
) {
    private val engine: Engine = Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    private fun mvc (path: String) = "/app/$path" to res("/js/mvc/$path")
    private fun shims (path: String) = "/app/shims/$path" to res("/js/app/shims/$path")
    private fun views (path: String) = mvc("views/$path")
    private fun controllers (path: String) = mvc("controllers/$path")
    private fun nodeModules (path: String,  module: String, version: String) = "/app/shims/$module/$path" to res("/js/node_modules/$module-$version/$path")
    private fun ejs(path: String) = nodeModules(path,"ejs", "v3.1.10")
    private fun handebars(path: String) = nodeModules(path,"handlebars", "4.7.8")
    private val files = mapOf(
        mvc("index.js"),
        mvc("lib/boot.js"),
        mvc("db.js"),
        views("5xx.ejs"),
        views("404.ejs"),
        controllers("main/index.js"),
        controllers("pet/index.js"),
        controllers("pet/views/edit.ejs"),
        controllers("pet/views/show.ejs"),
        controllers("pet/index.js"),
        controllers("user/index.js"),
        controllers("user/views/edit.hbs"),
        controllers("user/views/list.hbs"),
        controllers("user/views/show.hbs"),
        controllers("user-pet/index.js"),
        shims("fs.js"),
        shims("path.js"),
        shims("express.js"),
        shims("express-session.js"),
        shims("method-override.js"),
        shims("morgan.js"),
        //ejs("ejs.min.js"),
        ejs("ejs.js"),
        ejs("lib/ejs.js"),
        ejs("lib/utils.js"),
        ejs("bin/cli.js"),
        handebars("handlebars.js"),
    )

    private fun res(path: String): ByteArray {
        val r = resourceLoader.getResource("classpath:$path")
        return r.inputStream.use { it.readBytes() }
    }

    private fun buildVirtualFs(): FileSystem {

        return InMemoryFileSystem(files)
    }

    fun sourceFile(path: String): Source {
        return Source.newBuilder("js", files[path]!!.decodeToString(), path).build()
    }

    fun dir(path: String): ProxyArray? {
        val prefix = path.trimEnd('/') + "/"

        val children = files.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('/') }   // ← первый уровень
            .distinct()
            .map { it }
            .toList()

        return ProxyArray.fromList(children)
    }

    fun stat(path: String): Map<String, Any> {
        return mapOf(
            "isFile" to ProxyExecutable { files.containsKey(path) },
            "isDirectory" to ProxyExecutable {
                (files.keys.any{ it.contains(path)} &&  path.endsWith("/")) || files.keys.any{ it.contains("$path/")}
            }
        )
    }

    fun handle(req: JsHttpRequest): JsHttpResponse {
        val fs = buildVirtualFs()

        Context.newBuilder("js")
            .engine(engine)
            .fileSystem(fs)
            .allowExperimentalOptions(true)
            .option("js.commonjs-require", "true")
            .option("js.commonjs-require-cwd", "/app")
            .option("js.interop-complete-promises", "true")
            .allowIO(true)
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .option("js.console", "true")
            .build().use { ctx ->

                ctx.getBindings("js").putMember(
                    "__fs",
                    ProxyObject.fromMap(mapOf(
                        "readFile" to ProxyExecutable { args ->
                            sourceFile(args[0].asString()).characters
                        },
                        "readFileSync" to ProxyExecutable { args ->
                            sourceFile(args[0].asString()).characters
                        },
                        "readDir" to ProxyExecutable { args ->
                            dir(args[0].asString())
                        },
                        "stat" to ProxyExecutable { args ->
                            ProxyObject.fromMap(stat(args[0].asString()))
                        }
                    )))
                ctx.getBindings("js").putMember("__host", ProxyObject.fromMap(mapOf(
                    "expressWrapper" to ExpressBridge(),
                    "springSession" to SpringSessionBridge()
                )))

                if (!ctx.getBindings("js").hasMember("__dirname"))
                    ctx.getBindings("js").putMember("__dirname", Value.asValue("/app"))


                val exportsOrModule = ctx.eval(sourceFile("/app/index.js"))

                val handler = when {
                    exportsOrModule.hasMember("handle") -> exportsOrModule.getMember("handle")
                    ctx.getBindings("js").hasMember("handle") -> ctx.getBindings("js").getMember("handle")
                    else -> throw IllegalStateException("No JS handler 'handle' found in /app/index.js")
                }

                val jsReq = ProxyObject.fromMap(
                    mapOf(
                        "method" to req.method,
                        "url" to req.path,
                        "path" to req.path,
                        "baseUrl" to "/js",
                        "query" to (req.query ?: ""),
                        "headers" to ProxyObject.fromMap(req.headers),
                        "body" to req.body // byte[]
                    )
                )


                val jsResp = callHandleWithCallbacks(handler, jsReq)

                val status = jsResp.getMember("status").asInt()
                val headersVal = jsResp.getMember("headers")
                val headers = headersVal.memberKeys.associateWith { k -> listOf(headersVal.getMember(k).asString()) }

                val bodyVal = jsResp.getMember("body")
                val body: ByteArray =
                    if (bodyVal.isNull) ByteArray(0)
                    else if (bodyVal.hasArrayElements()) {
                        ByteArray(bodyVal.arraySize.toInt()) { i -> bodyVal.getArrayElement(i.toLong()).asInt().toByte() }
                    } else {
                        bodyVal.asString().toByteArray(StandardCharsets.UTF_8)
                    }

                return JsHttpResponse(status, headers, body)
            }
    }

    private fun callHandleWithCallbacks(
        jsHandle: Value,
        jsReq: Any,
        timeoutMs: Long = 10_000
    ): Value {
        val cf = CompletableFuture<Value>()

        val onSuccess = ProxyExecutable { args ->
            // callback(value)
            val v = args.getOrNull(0)
            if (v == null) cf.completeExceptionally(IllegalStateException("JS callback called without value"))
            else cf.complete(v)
            null
        }

        val onError = ProxyExecutable { args ->
            // error(err)
            val err = args.getOrNull(0)
            val msg = err?.toString() ?: "unknown error"
            cf.completeExceptionally(RuntimeException("JS error callback: $msg"))
            null
        }

        // IMPORTANT: execute returns undefined now — ignore it.
        jsHandle.execute(jsReq, onSuccess, onError)

        return try {
            cf.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }
}