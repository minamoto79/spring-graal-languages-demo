package com.example.springLanguages.python

import jakarta.servlet.http.HttpServletRequest
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Value
import org.intellij.lang.annotations.Language
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.stereotype.Service

@Language("python")
private val pythonModuleText = """
    # flask_app.py
    from flask import  Blueprint, Flask, render_template, session, redirect, url_for, request, flash
    
    app = Flask(__name__, template_folder="/graalpy_vfs/templates")
    app.secret_key = "dev-secret"  # needed for sessions
    
    main = Blueprint("main", __name__)
    
    import os
    print(os.path.exists("/graalpy_vfs"))
    assert os.path.exists("/graalpy_vfs/templates/index.html"), "templates not visible"
    
    
    @main.before_request
    def dbg():
        from flask import request
        print("METHOD", request.method, "PATH", request.path)
    
    @main.get("/")
    def index():
        print("index")
        if not session.get("logged_in"):
            print("not logged in")
            return render_template("login.html")
        print("logged in")
        return render_template("index.html")
    
    @main.route("/about")
    def about() -> str:
        return render_template("about.html")
    
    @main.route("/contact")
    def contact() -> str:
        return render_template("contact.html")
    
    @main.route("/login", methods=["GET", "POST"])
    def login():
        error = None
        if request.method == "POST":
            print("login POST")
            username = request.form.get("username", "").strip()
            password = request.form.get("password", "").strip()

            if username != "admin" or password != "admin":
                error = "Invalid Credentials. Please try again."
            else:
                session["logged_in"] = True
                flash("Successful login.")
            return redirect(url_for("main.index"))

        return render_template("login.html", error=error)
    
    @main.get("/logout")
    def logout():
        session.clear()
        return redirect(url_for("main.index"))
    
    app.register_blueprint(main)
    
    
    def handle_wsgi(environ):
        status_holder = {}
        headers_holder = {}
    
        def start_response(status, headers, exc_info=None):
            status_holder["status"] = status
            headers_holder["headers"] = headers
    
        chunks = []
        for part in app.wsgi_app(environ, start_response):
            chunks.append(part)
    
        body = b"".join(chunks)
        return status_holder["status"], headers_holder["headers"], body
        
    import io, sys

    def make_environ(method, query_string, script_name,
                     server_name, server_port, server_protocol, url_scheme,
                     headers, body,
                     content_type=None, content_length=None):
        env = {}
        if body is None:
            body_bytes = b""
        elif isinstance(body, (bytes, bytearray, memoryview)):
            body_bytes = bytes(body)
        elif isinstance(body, str):
            # only valid if you *know* body is text; for form posts you usually want bytes from the HTTP layer
            body_bytes = body.encode("utf-8")
        else:
            # Graal foreign array/list -> convert explicitly
            # This works for ForeignList of ints 0..255
            try:
                body_bytes = bytes(body)
            except TypeError:
                body_bytes = bytes([int(b) & 0xFF for b in body])
    
        env["REQUEST_METHOD"] = method
        env["PATH_INFO"] = query_string or "/"
        env["QUERY_STRING"] = query_string or ""
        env["SERVER_NAME"] = server_name
        env["SERVER_PORT"] = str(server_port)
        env["SERVER_PROTOCOL"] = server_protocol
        env["SCRIPT_NAME"] = script_name or ""
        
        print(f"make_environ: method={method} query_string={query_string} server_name={server_name} server_port={server_port} server_protocol={server_protocol} url_scheme={url_scheme} content_type={content_type} content_length={content_length}")
    
        env["wsgi.version"] = (1, 0)
        env["wsgi.url_scheme"] = url_scheme
        env["wsgi.input"] = io.BytesIO(body_bytes)
        env["wsgi.errors"] = sys.stderr   # IMPORTANT: must have .write()
        env["wsgi.multithread"] = True
        env["wsgi.multiprocess"] = False
        env["wsgi.run_once"] = False
    
        if content_type:
            env["CONTENT_TYPE"] = content_type
        if content_length is not None:
            env["CONTENT_LENGTH"] = str(content_length)
    
        for hv in headers:
            name, value = hv[0], hv[1]
            key = "HTTP_" + name.upper().replace("-", "_")
            if key in ("HTTP_CONTENT_TYPE", "HTTP_CONTENT_LENGTH"):
                continue
            env[key] = value
   
        return env
    
    def to_pydict(m):
        d = {}
        for k in m:
            d[k] = m.get(k)
        return d
""".trimIndent()

data class WsgiResult(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

@Service
class FlaskBridge {
    @Autowired
    lateinit var graalPy: GraalPyContext

    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var handleWsgiFn: Value? = null

    @Volatile private var makeEnvironFn: Value? = null
    @Volatile private var toPyDictFn: Value? = null

    private fun initOnce() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            graalPy.eval("import sys; print(sys.path)")
            graalPy.eval("import flask; print(flask.__version__)")

            // load module code once into the shared context
            graalPy.eval(pythonModuleText)

            // fetch the function from globals
            handleWsgiFn = graalPy.eval("handle_wsgi")
            require(handleWsgiFn != null && handleWsgiFn!!.canExecute()) {
                "Python function handle_wsgi not found or not executable"
            }

            makeEnvironFn = graalPy.eval("make_environ")
            require(makeEnvironFn != null && makeEnvironFn!!.canExecute()) {
                "Python function handle_wsgi not found or not executable"
            }

            toPyDictFn = graalPy.eval("to_pydict")
            require(toPyDictFn != null && toPyDictFn!!.canExecute()) {
                "Python function handle_wsgi not found or not executable"
            }

            initialized = true
        }
    }
    fun buildEnviron(req: HttpServletRequest, body: ByteArray, mountPrefix: String): Value? {
        val headers = mutableListOf<Array<String>>()
        val names = req.headerNames
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            val value = req.getHeader(name) ?: continue
            headers += arrayOf(name, value)
        }
        val (scriptName, pathInfo) = splitPath(req, mountPrefix)

        val pyEnv = makeEnvironFn!!.execute(
            req.method,
            pathInfo,        // PATH_INFO
            scriptName,
            req.serverName ?: "localhost",
            req.serverPort,
            req.protocol ?: "HTTP/1.1",
            req.scheme ?: "http",
            headers.toTypedArray(),
            body,
            req.contentType,
            if (req.contentLengthLong >= 0) req.contentLengthLong else null
        )
        return pyEnv
    }

    private fun splitPath(req: HttpServletRequest, mount: String): Pair<String, String> {
        val ctx = req.contextPath ?: ""
        val uri = req.requestURI ?: "/"

        // remove contextPath first (usually "" in local dev)
        val noCtx = if (ctx.isNotEmpty() && uri.startsWith(ctx)) uri.substring(ctx.length) else uri

        // remove mount prefix (/flask)
        val rest = if (noCtx.startsWith(mount)) noCtx.substring(mount.length) else noCtx

        val pathInfo = if (rest.isEmpty()) "/" else rest
        val scriptName = ctx + mount

        return scriptName to pathInfo
    }

    private val engine = Engine.create()

    fun handle(req: HttpServletRequest, body: ByteArray, mountPrefix: String): WsgiResult {
        initOnce()
        synchronized(lock) {
            val environ = buildEnviron(req, body, mountPrefix)
            val triple = handleWsgiFn!!.execute(environ)

            val statusStr = triple.getArrayElement(0).asString() // "200 OK"
            val status = statusStr.substringBefore(' ').toInt()

            val headersVal = triple.getArrayElement(1)
            val headers = ArrayList<Pair<String, String>>(headersVal.arraySize.toInt())
            for (i in 0 until headersVal.arraySize) {
                val h = headersVal.getArrayElement(i)
                headers += h.getArrayElement(0).asString() to h.getArrayElement(1).asString()
            }

            val bodyBytes = with(triple.getArrayElement(2)) {
                val out = ByteArray(bufferSize.toInt())
                readBuffer(0, out, 0, out.size)
                out
            }

            return WsgiResult(status, headers, bodyBytes)
        }
    }
}