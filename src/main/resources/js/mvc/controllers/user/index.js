'use strict'

/**
 * Module dependencies.
 */

var db = require('../../db');

exports.engine = 'hbs';

exports.before = function(req, res, next){
  var id = req.params.user_id;
  if (!id) return next();

  // Wrap in a Promise to work with your express.js shim's async handling
  return new Promise((resolve) => {
    // Perform lookup immediately since process.nextTick is missing
    req.user = db.users[id];

    if (!req.user) {
      next('route');
    } else {
      next();
    }
    resolve();
  });
};

exports.list = function(req, res, next){
  res.render('list', { users: db.users });
};

exports.edit = function(req, res, next){
  res.render('edit', { user: req.user });
};

exports.show = function(req, res, next){
  if (!req.user) {
    // If user isn't found, you can pass an error or redirect
    return res.status(404).send('User not found');
  }
  console.log(`show user: ${req.user.id}`);
  res.render('show', { user: req.user });
};

exports.update = function(req, res, next){
  var body = req.body;
  req.user.name = body.user.name;
  res.message('Information updated!');
  res.redirect('/user/' + req.user.id);
};
