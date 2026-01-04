"use strict";

module.exports = function session(options = {}) {
    return function (req, res, next) {
        req.session = __host.springSession.getOrCreate(req, res, options);
        return next && next();
    };
};

function dumpMethods(obj) {
    const methods = new Set();
    let cur = obj;

    while (cur && cur !== Object.prototype) {
        Object.getOwnPropertyNames(cur).forEach(name => {
            if (typeof obj[name] === "function") {
                methods.add(name);
            }
        });
        cur = Object.getPrototypeOf(cur);
    }

    return [...methods].sort();
}
