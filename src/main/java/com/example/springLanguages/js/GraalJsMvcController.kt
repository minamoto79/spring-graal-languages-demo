package com.example.springLanguages.js

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMapAdapter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/js")
class GraalJsMvcController(private val handleFn: JsRuntimeService) {

    @GetMapping("/public/**")
    fun serveStatic(req: HttpServletRequest): ResponseEntity<ByteArray> {
        val path = req.requestURI.removePrefix("/js/public/")
        val resource = this::class.java.classLoader
            .getResourceAsStream("js/mvc/public/$path")
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, getContentType(path))
            .body(resource.readAllBytes())
    }

    private fun getContentType(path: String) = when {
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        else -> "application/octet-stream"
    }

    @GetMapping("/**")
    fun dispatch(req: HttpServletRequest): ResponseEntity<String> {

        val resp = handleFn.handle(JsHttpRequest(req.method,
            req.requestURI.removePrefix("/js").ifEmpty { "/" },
            req.queryString,
            req.headerNames.toList().associateWith { req.headerNames.toList() },
            req.inputStream.readAllBytes()))

        return ResponseEntity(
            resp.body.decodeToString(),
            HttpHeaders(MultiValueMapAdapter(resp.headers)),
            resp.status)
    }
}
