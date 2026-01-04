'use strict'

/**
 * Module dependencies.
 */

var express = require('/app/shims/express');
var logger = require('/app/shims/morgan');
var path = require('/app/shims/path');
var session = require('/app/shims/express-session');
var methodOverride = require('/app/shims/method-override');
var ejs = require('/app/shims/ejs/ejs');

var app = module.exports = express();

// set our default template engine to "ejs"
// which prevents the need for using file extensions
app.set('view engine', 'ejs');

// set views for error and 404 pages
app.set('views', path.join(__dirname, 'views'));

// define a custom res.message() method
// which stores messages in the session
app.response.message = function(msg){
  // reference `req.session` via the `this.req` reference
  var sess = this.req.session;
  // simply add the msg to an array for later
  sess.messages = sess.messages || [];
  sess.messages.push(msg);
  return this;
};

// log
if (!module.parent) app.use(logger('dev'));

// serve static files
app.use(express.static(path.join(__dirname, 'public')));

// session support
app.use(session({
  resave: false, // don't save session if unmodified
  saveUninitialized: false, // don't create session until something stored
  secret: 'some secret here'
}));

// parse request bodies (req.body)
app.use(express.urlencoded({ extended: true }))

// allow overriding methods in query (?_method=put)
app.use(methodOverride('_method'));

// expose the "messages" local variable when views are rendered
app.use(function(req, res, next){
  if (res._sent) return;
  var msgs = req.session.messages || [];

  // expose "messages" local variable
  res.locals.messages = msgs;

  // expose "hasMessages"
  res.locals.hasMessages = !! msgs.length;
  res.render = (view, locals = {}) => {
    const app = res.app || req.app;                 // MUST be set by the dispatcher
    const viewsDir = app.settings['views'];
    const defaultExt = app.settings['view engine'] || 'ejs';

    const hasExt = /\.[a-z0-9]+$/i.test(view);
    const ext = (hasExt ? view.split('.').pop() : defaultExt);
    const file = hasExt ? path.join(viewsDir, view)
        : path.join(viewsDir, `${view}.${ext}`);

    console.log(`locals: ${JSON.stringify(locals)}`)
    console.log(`render template file:${file} reading`)
    const tpl = __fs.readFile(file, 'utf8');
    const ctx = Object.assign({}, app.locals, res.locals, locals);

    const engine = app.engines[ext];
    if (!engine) throw new Error(`No view engine for .${ext}`);

    const html = engine(tpl, ctx, file);

    res.type('text/html');
    res.send(html);
    res._sent = true;
    return res;
  };


  next();
  // empty or "flush" the messages so they
  // don't build up
  req.session.messages = [];
});

// load controllers
require('./lib/boot')(app, { verbose: !module.parent });

app.use(function(err, req, res, next){
  // log it
  if (!module.parent) console.error(err.stack);

  // error page
  res.status(500).render('5xx');
});

// assume 404 since no middleware responded
app.use(function(req, res, next){
  res.app = req.app || app;
  res.status(404).render('404', { url: req.originalUrl });
});

/* istanbul ignore next */
if (!module.parent) {
  app.listen(3000);
  console.log('Express started on port 3000');
}

console.log('Express started');

const handle = function(req, callback, error) {
  app.handle(req).then(value => {
    callback(value);
  }).catch(err => {
    error(err);
  })
};
