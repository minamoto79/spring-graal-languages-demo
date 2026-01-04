module.exports = {
    readFileSync(path) {
        return __fs.readFile(path)
    },

    readdirSync(path) {
        return __fs.readDir(path)
    },

    statSync(path) {
        return __fs.stat(path)
    }
}