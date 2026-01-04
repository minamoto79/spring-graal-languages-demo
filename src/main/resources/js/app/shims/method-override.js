// /app/shims/method-override.js
"use strict";

/**
 * Express expects:
 *   const methodOverride = require('method-override')
 *   app.use(methodOverride('_method'))
 */
module.exports = function methodOverride(getter = "_method", options = {}) {
    return function methodOverrideMiddleware(req, res, next) {
        // req.method is expected to be like 'GET', 'POST', ...
        let override = null;

        // 1) if getter is a string: look in query or body
        if (typeof getter === "string") {
            // query param
            if (req.query && req.query[getter]) override = req.query[getter];

            // form field (if you already populate req.body somewhere)
            if (!override && req.body && req.body[getter]) override = req.body[getter];
        }

        // 2) if getter is a function: call it
        if (!override && typeof getter === "function") {
            override = getter(req, res);
        }

        // 3) optional: header-based override (common in APIs)
        if (!override && req.headers) {
            override = req.headers["x-http-method-override"] || req.headers["X-HTTP-Method-Override"];
        }

        if (override && typeof override === "string") {
            const m = override.toUpperCase();
            // Express usually supports these
            if (m === "PUT" || m === "PATCH" || m === "DELETE") {
                req.method = m;
            }
        }

        if (typeof next === "function") next();
    };
};