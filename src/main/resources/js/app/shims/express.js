// /app/shims/express.js
'use strict';

// Common helpers


// The exported "express" function
function express() {
    const app = function app(req, res) { // optional call style
        return app.handle(req, res);
    };

    app.settings = {};
    app.locals = {};

    // prototypes (index.js does: app.response.message = ...)
    app.request = {};
    app.response = {};
    // view engines registry
    app.engines = []
    app.engine = (ext, fn) => {
        const key = String(ext).replace(/^\./, '');
        app.engines[key] = fn;
        return app;
    };

     app.stack = [];

    function addLayer(path, fn) {
        app.stack.push({path, fn});
    }

    app.set = (k, v) => {
        app.settings[k] = v;
        return app;
    };

    function compose(handlers) {
        return function (req, res, outNext) {
            let i = 0;

            function next(err) {
                // Check if 'route' was passed as the error argument
                if (err === 'route') {
                    return outNext(); // Skip the rest of these handlers
                }

                const fn = handlers[i++];
                if (!fn) return outNext(err);

                try {
                    const r = fn(req, res, next);
                    if (res._sent) return outNext(err);

                    if (r && typeof r.then === 'function') {
                        return r.then(() => {
                            if (res._sent) return outNext();
                            next();
                        }, next);
                    }

                } catch (e) {
                    next(e);
                }
            }

            next();
        };
    }

    app.get = (path, ...handlers) => {
        addLayer(path, route('GET', path, compose(handlers)));
        return app;
    };
    app.post = (path, ...handlers) => {
        addLayer(path, route('POST', path, compose(handlers)));
        return app;
    };
    app.put = (path, ...handlers) => {
        addLayer(path, route('PUT', path, compose(handlers)));
        return app;
    };
    app.delete = (path, ...handlers) => {
        addLayer(path, route('DELETE', path, compose(handlers)));
        return app;
    };

    app.use = (path, fn) => {
        if (typeof path === 'function') {
            fn = path;
            path = '/';
        }

        if (fn && typeof fn.handle === 'function') {
            const sub = fn;

            fn = (req, res, next) => {
                if (res._sent) return;

                const prevApp = req.app;   // сохранить parent
                req.app = sub;
                res.app = sub;

                sub.handle(req, res)
                    .then(() => {
                        req.app = prevApp;
                        res.app = prevApp;

                        if (res._sent) return;
                        next();
                    })
                    .catch(err => {
                        req.app = prevApp;
                        res.app = prevApp;
                        next(err);
                    });
            };
        }

        addLayer(path, fn);
        return app;
    };

    // Main entry: produces {status, headers, body}
    app.handle = function (req, res) {
        const out = {status: 200, headers: {}, body: ""};

        // если res не передали — создаём
        res = res || createResFacade(out, app, req);

        // req.path нормализуй заранее (ты уже делал что-то подобное)
        req.path = req.path || req.url || '/';

        return new Promise((resolve, reject) => {
            let idx = 0;
            let finished = false;

            function done(err) {
                if (finished) return;
                finished = true;
                if (err) reject(err);
                else resolve(out);
            }

            function next(err) {
                if (res._sent) return done(err);

                const layer = app.stack[idx++];
                if (!layer) return done(err);

                const fn = layer.fn;
                try {
                    const r = err
                        ? (fn.length === 4 ? fn(err, req, res, next) : next(err))
                        : (fn.length <= 3 ? fn(req, res, next) : next());
                    if (res._sent) return done();

                    if (r && typeof r.then === 'function') {
                        return r.then(
                            () => { if (res._sent) done(); },
                            next
                        );
                    }

                } catch (e) {
                    next(e);
                }
            }

            next();
        });
    };

    app.listen = function(port) {}

    const ejs = require('/app/shims/ejs/ejs');
    app.engine('ejs', (tpl, ctx) => ejs.render(tpl, ctx));

    // handlebars optional, но регистрируем сразу
    app.engine('hbs', (tpl, ctx) => {
        const Handlebars = require('/app/shims/handlebars/handlebars');
        return Handlebars.compile(tpl)(ctx);
    });

    return app;
}

// --- express.static / urlencoded / json ---
express.static = function staticMiddleware(_rootDir) {
    // In your setup Spring can serve static files itself.
    // This middleware is here only to satisfy the demo chain.
    return function (_req, _res, next) { next(); };
};

express.urlencoded = function urlencoded(_opts) {
    // If Spring already parsed the body -> just pass through.
    // Otherwise, parse req.rawBody (string) into req.body.
    return function (req, _res, next) {
        if (req.body == null && typeof req.rawBody === 'string') {
            const params = new URLSearchParams(req.rawBody);
            const obj = Object.create(null);
            for (const [k, v] of params.entries()) obj[k] = v;
            req.body = obj;
        }
        next();
    };
};

express.json = function json(_opts) {
    return function (req, _res, next) {
        if (req.body == null && typeof req.rawBody === 'string') {
            try { req.body = JSON.parse(req.rawBody); } catch {}
        }
        next();
    };
};

// --- small res facade used by routes ---
function createResFacade(out, app, req) {
    const res = { locals: {} };

    res.req = req;
    res.app = app;

    Object.setPrototypeOf(res, app.response);

    res._sent = false;
    function markSent() { res._sent = true; }

    res.status = (code) => { out.status = code | 0; return res; };
    res.set = (k, v) => { out.headers[String(k).toLowerCase()] = String(v); return res; };
    res.type = (v) => res.set('content-type', v);

    res.send = (body) => {
        out.body = body == null ? '' : String(body);
        markSent();
        return res;
    };
    res.body = (body) => { out.body = body; return res; };

    res.end = (body) => res.send(body);

    res.redirect = (codeOrUrl, maybeUrl) => {
        const code = typeof codeOrUrl === 'number' ? codeOrUrl : 302;
        let url = typeof codeOrUrl === 'string' ? codeOrUrl : maybeUrl;

        if (!url) url = '/';

        // ✅ preserve mount prefix (e.g. /js)
        const base = (req && req.baseUrl) ? req.baseUrl : '';
        if (url.startsWith('/') && base && !url.startsWith(base + '/')) {
            url = base + url; // /js + /users => /js/users
        }

        res.status(code);
        res.set('location', url);
        out.body = '';
        markSent();
        return res;
    };

    return res;
}

function compilePath(pattern) {
    const keys = [];
    const reStr = '^' + pattern
            .replace(/\//g, '\\/')
            .replace(/:([A-Za-z0-9_]+)/g, (_, k) => { keys.push(k); return '([^\\/]+)'; })
        + '$';
    return { re: new RegExp(reStr), keys };
}

function route(method, pattern, handler) {
    const { re, keys } = compilePath(pattern);
    return function (req, res, next) {
        const m = (req.method || 'GET').toUpperCase();
        const p = req.path || req.url || '/';
        if (m !== method) return next();
        const match = re.exec(p);
        if (!match) return next();
        req.params = req.params || {};
        keys.forEach((k, i) => req.params[k] = match[i + 1]);
        return handler(req, res, next);
    };
}

module.exports = express;