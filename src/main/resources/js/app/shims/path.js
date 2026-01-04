// /app/shims/path.js (или твой /mnt/data/path.js)
module.exports = {
    join(...parts) {
        // 1) join + collapse slashes
        const raw = parts
            .filter(p => p != null && p !== "")
            .join("/")
            .replace(/\/+/g, "/");

        if (raw === "") return ".";

        // 2) remember if absolute
        const absolute = raw.startsWith("/");

        // 3) split and normalize
        const segs = raw.split("/");

        const out = [];
        for (const s of segs) {
            if (s === "" || s === ".") continue;
            if (s === "..") {
                if (out.length > 0 && out[out.length - 1] !== "..") {
                    out.pop();
                } else {
                    // if not absolute, preserve leading ".."
                    if (!absolute) out.push("..");
                }
                continue;
            }
            out.push(s);
        }

        let res = (absolute ? "/" : "") + out.join("/");

        // 4) keep "/" for absolute root, otherwise "." for empty relative
        if (res === "") res = absolute ? "/" : ".";
        return res;
    }
};