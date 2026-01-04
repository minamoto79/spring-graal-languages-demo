'use strict'

/**
 * Module dependencies.
 */

var express = require('/app/shims/express');
var path = require('/app/shims/path');
var fs = require('/app/shims/fs');

module.exports = function(parent, options){
  var dir = path.join(__dirname, '..', 'controllers');
  var verbose = options.verbose;
  fs.readdirSync(dir).forEach(function(name){
    var file = path.join(dir, name)
    if (!fs.statSync(file).isDirectory()) return;
    verbose && console.log(`\n   ${name}`);
    var obj = require(file);
    var name = obj.name || name;
    var prefix = obj.prefix || '';
    var app = express();
    var handler;
    var method;
    var url;

    // allow specifying the view engine
    if (obj.engine) app.set('view engine', obj.engine);
    app.set('views', path.join(__dirname, '..', 'controllers', name, 'views'));

    // generate routes based
    // on the exported methods
    for (var key in obj) {
      // "reserved" exports
      if (~['name', 'prefix', 'engine', 'before'].indexOf(key)) continue;
      // route exports
      switch (key) {
        case 'show':
          method = 'get';
          url = '/' + name + '/:' + name + '_id';
          break;
        case 'list':
          method = 'get';
          url = '/' + name + 's';
          break;
        case 'edit':
          method = 'get';
          url = '/' + name + '/:' + name + '_id/edit';
          break;
        case 'update':
          method = 'put';
          url = '/' + name + '/:' + name + '_id';
          break;
        case 'create':
          method = 'post';
          url = '/' + name;
          break;
        case 'index':
          method = 'get';
          url = '/';
          break;
        default:
          /* istanbul ignore next */
          throw new Error('unrecognized route: ' + name + '.' + key);
      }

      // setup
      handler = obj[key];
      url = prefix + url;

      // before middleware support
      if (obj.before) {
        verbose && console.log(`     ${method.toUpperCase()} ${url} -> before -> ${key}`);
        app[method](url, obj.before, handler);
      } else {
        verbose && console.log(`     ${method.toUpperCase()} ${url} -> ${key}`);
        app[method](url, handler);
      }
    }

    // mount the app
    parent.use(app);
  });
};
