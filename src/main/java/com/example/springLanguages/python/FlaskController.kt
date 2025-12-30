package com.example.springLanguages.python

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/flask")
class FlaskController {

    @Autowired
    lateinit var bridge: FlaskBridge
    @RequestMapping(
        value = ["/**"],
        method = [RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH]
    )
    fun dispatch(req: HttpServletRequest): ResponseEntity<ByteArray> {
        val body = req.inputStream.readBytes()
        val result = bridge.handle(req, body, mountPrefix = "/flask")
        val headers = HttpHeaders()
        result.headers.forEach { (k, v) -> headers.add(k, v) }
        return ResponseEntity.status(result.status).headers(headers).body(result.body)
    }
}