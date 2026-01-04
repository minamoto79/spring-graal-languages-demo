
module.exports = function morgan(format) {
    const fmt = format || "dev";

    return function morganMiddleware(req, res, next) {
        try {
            const method = req.method || "-";
            const url = req.originalUrl || req.url || "-";

            // status может быть ещё не установлен
            const status = res.statusCode || 200;

            if (fmt === "dev") {
                console.log(`[morgan] ${method} ${url} ${status}`);
            } else {
                console.log(`[morgan:${fmt}] ${method} ${url}`);
            }
        } catch (e) {
            console.log("[morgan:error]", e);
        }

        next();
    };
};
