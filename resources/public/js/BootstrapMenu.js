/******/ (function(modules) { // webpackBootstrap
/******/ 	// The module cache
/******/ 	var installedModules = {};

/******/ 	// The require function
/******/ 	function __webpack_require__(moduleId) {

/******/ 		// Check if module is in cache
/******/ 		if(installedModules[moduleId])
/******/ 			return installedModules[moduleId].exports;

/******/ 		// Create a new module (and put it into the cache)
/******/ 		var module = installedModules[moduleId] = {
/******/ 			exports: {},
/******/ 			id: moduleId,
/******/ 			loaded: false
/******/ 		};

/******/ 		// Execute the module function
/******/ 		modules[moduleId].call(module.exports, module, module.exports, __webpack_require__);

/******/ 		// Flag the module as loaded
/******/ 		module.loaded = true;

/******/ 		// Return the exports of the module
/******/ 		return module.exports;
/******/ 	}


/******/ 	// expose the modules object (__webpack_modules__)
/******/ 	__webpack_require__.m = modules;

/******/ 	// expose the module cache
/******/ 	__webpack_require__.c = installedModules;

/******/ 	// __webpack_public_path__
/******/ 	__webpack_require__.p = "";

/******/ 	// Load entry module and return exports
/******/ 	return __webpack_require__(0);
/******/ })
/************************************************************************/
/******/ ([
/* 0 */
/***/ function(module, exports, __webpack_require__) {

	/* webpack entry file to build a standalone browser script. */
	window.BootstrapMenu = __webpack_require__(1);


/***/ },
/* 1 */
/***/ function(module, exports, __webpack_require__) {

	'use strict';

	var classNames = __webpack_require__(2);
	var $ = __webpack_require__(3);
	__webpack_require__(4);

	// modular lodash requires
	var _ = function() {
	  throw new Error('Custom lodash build for BootstrapMenu. lodash chaining is not included');
	};

	_.noop = __webpack_require__(5);
	_.each = __webpack_require__(6);
	_.contains = __webpack_require__(33);
	_.extend = __webpack_require__(41);
	_.uniqueId = __webpack_require__(48);
	_.isFunction = __webpack_require__(18);


	var defaultOptions = {
	    /* container of the context menu, where it will be created and where
	     * event listeners will be installed. */
	    container: 'body',

	    /* user-defined function to obtain specific data about the currently
	     * opened element, to pass it to the rest of user-defined functions
	     * of an action. */
	    fetchElementData: _.noop,

	    /* what the source of the context menu should be when opened.
	     * Valid values are 'mouse' and 'element'. */
	    menuSource: 'mouse',

	    /* how to calculate the position of the context menu based on its source.
	     * Valid values are 'aboveLeft', 'aboveRight', 'belowLeft', and 'belowRight'. */
	    menuPosition: 'belowLeft',

	    /* the event to listen to open the menu.
	     * Valid values are 'click', 'right-click', 'hover' */
	    menuEvent: 'right-click', // TODO rename to menuAction in next mayor version

	    /* group actions to render them next to each other, with a separator
	     * between each group. */
	    actionsGroups: [],

	    /* In some weird cases, another plugin may be installing 'click' listeners
	     * in the anchors used for each action of the context menu, and stopping
	     * the event bubbling before it reachs this plugin's listener.
	     *
	     * For those cases, _actionSelectEvent can be used to change the event we
	     * listen to, for example to 'mousedown'.
	     *
	     * Unless the context menu is not working due to this and a workaround is
	     * needed, this option can be safely ignored.
	     */
	    _actionSelectEvent: 'click'
	};

	function renderMenu(_this) {
	    var $menu = $('<div class="dropdown bootstrapMenu" style="z-index:10000;position:absolute;" />');

	    var $ul = $('<ul class="dropdown-menu" style="position:static;display:block;font-size:0.9em;" />');

	    // group all actions following the actionsGroups option, to
	    // add a separator between each of them.
	    var groups = [];

	    // default group where all ungrouped actions will go
	    groups[0] = [];

	    // add the rest of groups
	    _.each(_this.options.actionsGroups, function(groupArr, ind) {
	        groups[ind+1] = [];
	    });

	    // find out if any of the actions has an icon
	    var actionsHaveIcon = false;

	    // add each action to the group it belongs to, or the default group
	    _.each(_this.options.actions, function(action, actionId) {
	        var addedToGroup = false;

	        _.each(_this.options.actionsGroups, function(groupArr, ind) {
	            if (_.contains(groupArr, actionId)) {
	                groups[ind+1].push(actionId);
	                addedToGroup = true;
	            }
	        });

	        if (addedToGroup === false) {
	            groups[0].push(actionId);
	        }

	        if (typeof action.iconClass !== 'undefined') {
	            actionsHaveIcon = true;
	        }
	    });

	    var isFirstNonEmptyGroup = true;
	    _.each(groups, function(actionsIds) {
	        if (actionsIds.length == 0)
	            return;

	        if (isFirstNonEmptyGroup === false) {
	            $ul.append('<li class="divider"></li>');
	        }
	        isFirstNonEmptyGroup = false;

	        _.each(actionsIds, function(actionId) {
	            var action = _this.options.actions[actionId];

	            /* At least an action has an icon. Add the icon of the current action,
	             * or room to align it with the actions which do have one. */
	            if (actionsHaveIcon === true) {
	                $ul.append(
	                    '<li role="presentation" data-action="'+actionId+'">' +
	                    '<a href="#" role="menuitem">' +
	                    '<i class="fa fa-fw fa-lg ' + (action.iconClass || '') + '"></i> ' +
	                    '<span class="actionName"></span>' +
	                    '</a>' +
	                    '</li>'
	                );
	            }
	            // neither of the actions have an icon.
	            else {
	                $ul.append(
	                    '<li role="presentation" data-action="'+actionId+'">' +
	                    '<a href="#" role="menuitem"><span class="actionName"></span></a>' +
	                    '</li>'
	                );
	            }
	        });
	    });

	    return $menu.append($ul);
	}

	function setupOpenEventListeners(_this) {
	    var openEventName = null;

	    switch (_this.options.menuEvent) {
	        case 'click':
	            openEventName = 'click';
	            break;
	        case 'right-click':
	            openEventName = 'contextmenu';
	            break;
	        case 'hover':
	            openEventName = 'mouseenter';
	            break;
	        default:
	            throw new Error("Unknown BootstrapMenu 'menuEvent' option");
	    }

	    // install the handler for every future elements where
	    // the context menu will open
	    _this.$container.on(openEventName + _this.namespace, _this.selector, function(evt) {
	        var $openTarget = $(this);

	        _this.open($openTarget, evt);

	        // cancel event propagation, to avoid it bubbling up to this.$container
	        // and closing the context menu as if the user clicked outside the menu.
	        return false;
	    });
	}

	function clearOpenEventListeners(_this) {
	    _this.$container.off(_this.namespace);
	}

	function setupActionsEventListeners(_this) {
	    var actionSelectEvent = _this.options._actionSelectEvent + _this.namespace;

	    // handler to run when an option is selected
	    _this.$menu.on(actionSelectEvent, function(evt) {
	        evt.preventDefault();
	        evt.stopPropagation();

	        var $target = $(evt.target);

	        var $action = $target.is('[data-action]') ? $target : $target.closest('[data-action]');
	        var actionId = $action.data('action');

	        // action is disabled, dont do anything
	        if ($action.is('.disabled'))
	            return;

	        var targetData = _this.options.fetchElementData(_this.$openTarget);

	        /* call the user click handler. It receives the optional user-defined data,
	         * or undefined. */
	        _this.options.actions[actionId].onClick(targetData);

	        // close the menu
	        _this.close();
	    });
	}

	function clearActionsEventListeners(_this) {
	    _this.$menu.off(_this.namespace);
	}

	function setupCloseEventListeners(_this) {
	    switch (_this.options.menuEvent) {
	        case 'click':
	            break;
	        case 'right-click':
	            break;
	        case 'hover':
	            // close the menu when the mouse is moved outside both
	            // the element where the context menu was opened, and
	            // the context menu itself.
	            var $elemsToCheck = _this.$openTarget.add(_this.$menu);

	            $elemsToCheck.on('mouseleave' + _this.closeNamespace, function(evt) {
	                var destElement = evt.toElement || evt.relatedTarget;
	                if (!_this.$openTarget.is(destElement) && !_this.$menu.is(destElement)) {
	                    $elemsToCheck.off(_this.closeNamespace);
	                    _this.close();
	                }
	            });
	            break;
	        default:
	            throw new Error("Unknown BootstrapMenu 'menuEvent' option");
	    }

	    // it the user clicks outside the context menu, close it.
	    _this.$container.on('click' + _this.closeNamespace, function() {
	        _this.close();
	    });
	}

	function clearCloseEventListeners(_this) {
	    _this.$container.off(_this.closeNamespace);
	}

	var BootstrapMenu = function(selector, options) {
	    this.selector = selector;
	    this.options = _.extend({}, defaultOptions, options);

	    // namespaces to use when registering event listeners
	    this.namespace = _.uniqueId('.BootstrapMenu_');
	    this.closeNamespace = _.uniqueId('.BootstrapMenuClose_');

	    this.init();
	};

	var existingInstances = [];

	BootstrapMenu.prototype.init = function() {
	    this.$container = $(this.options.container);

	    // jQuery object of the rendered context menu. Not part of the DOM yet.
	    this.$menu = renderMenu(this);
	    this.$menuList = this.$menu.children();

	    /* append the context menu to <body> to be able to use "position: absolute"
	     * absolute to the whole window. */
	    this.$menu.hide().appendTo(this.$container);

	    /* the element in which the context menu was opened. Updated every time
	     * the menu is opened. */
	    this.$openTarget = null;

	    /* event that triggered the context menu to open. Updated every time
	     * the menu is opened. */
	    this.openEvent = null;

	    setupOpenEventListeners(this);

	    setupActionsEventListeners(this);

	    // keep track of all the existing context menu instances in the page
	    existingInstances.push(this);
	};

	BootstrapMenu.prototype.updatePosition = function() {
	    var menuLocation = null; // my
	    var relativeToElem = null; // of
	    var relativeToLocation = null; // at

	    switch (this.options.menuSource) {
	        case 'element':
	            relativeToElem = this.$openTarget;
	            break;
	        case 'mouse':
	            relativeToElem = this.openEvent;
	            break;
	        default:
	            throw new Error("Unknown BootstrapMenu 'menuSource' option");
	    }

	    switch (this.options.menuPosition) {
	        case 'belowRight':
	            menuLocation = 'right top';
	            relativeToLocation = 'right bottom';
	            break;
	        case 'belowLeft':
	            menuLocation = 'left top';
	            relativeToLocation = 'left bottom';
	            break;
	        case 'aboveRight':
	            menuLocation = 'right bottom';
	            relativeToLocation = 'right top';
	            break;
	        case 'aboveLeft':
	            menuLocation = 'left bottom';
	            relativeToLocation = 'left top';
	            break;
	        default:
	            throw new Error("Unknown BootstrapMenu 'menuPosition' option");
	    }

	    // update the menu's height and width manually
	    this.$menu.css({ display: 'block' });

	    // once the menu is not hidden anymore, we can obtain its content's height and width,
	    // to manually update it in the menu
	    this.$menu.css({
	        height: this.$menuList.height(),
	        width: this.$menuList.width()
	    });

	    this.$menu.position({ my: menuLocation, at: relativeToLocation, of: relativeToElem });
	};

	// open the context menu
	BootstrapMenu.prototype.open = function($openTarget, event) {
	    var _this = this;

	    // first close all open instances of opened context menus in the page
	    BootstrapMenu.closeAll();

	    this.$openTarget = $openTarget;

	    this.openEvent = event;

	    var targetData = _this.options.fetchElementData(_this.$openTarget);

	    var $actions = this.$menu.find('[data-action]');

	    // clear previously hidden actions
	    $actions.show();

	    /* go through all actions to update the text to show, which ones to show
	     * enabled/disabled and which ones to hide. */
	    $actions.each(function() {
	        var $action = $(this);

	        var actionId = $action.data('action');
	        var action = _this.options.actions[actionId];

	        var classes = action.classNames || null;

	        if (classes && _.isFunction(classes))
	            classes = classes(targetData);

	        $action.attr('class', classNames(classes || ''));

	        if (action.isShown && action.isShown(targetData) === false) {
	            $action.hide();
	            return;
	        }

	        // the name provided for an action may be dynamic, provided as a function
	        $action.find('.actionName').html(
	            _.isFunction(action.name) && action.name(targetData) || action.name
	        );

	        if (action.isEnabled && action.isEnabled(targetData) === false) {
	            $action.addClass('disabled');
	        }
	    });

	    // once it is known which actions are or arent being shown
	    // (so we know the final height of the context menu),
	    // calculate its position
	    this.updatePosition();

	    this.$menu.show();

	    setupCloseEventListeners(this);
	};

	// close the context menu
	BootstrapMenu.prototype.close = function() {
	    // hide the menu
	    this.$menu.hide();

	    clearCloseEventListeners(this);
	};

	BootstrapMenu.prototype.destroy = function() {
	    this.close();
	    clearOpenEventListeners(this);
	    clearActionsEventListeners(this);
	};

	// close all instances of context menus
	BootstrapMenu.closeAll = function() {
	    _.each(existingInstances, function(contextMenu) {
	        contextMenu.close();
	    });
	};

	module.exports = BootstrapMenu;


/***/ },
/* 2 */
/***/ function(module, exports, __webpack_require__) {

	var __WEBPACK_AMD_DEFINE_ARRAY__, __WEBPACK_AMD_DEFINE_RESULT__;/*!
	  Copyright (c) 2016 Jed Watson.
	  Licensed under the MIT License (MIT), see
	  http://jedwatson.github.io/classnames
	*/
	/* global define */

	(function () {
		'use strict';

		var hasOwn = {}.hasOwnProperty;

		function classNames () {
			var classes = [];

			for (var i = 0; i < arguments.length; i++) {
				var arg = arguments[i];
				if (!arg) continue;

				var argType = typeof arg;

				if (argType === 'string' || argType === 'number') {
					classes.push(arg);
				} else if (Array.isArray(arg)) {
					classes.push(classNames.apply(null, arg));
				} else if (argType === 'object') {
					for (var key in arg) {
						if (hasOwn.call(arg, key) && arg[key]) {
							classes.push(key);
						}
					}
				}
			}

			return classes.join(' ');
		}

		if (typeof module !== 'undefined' && module.exports) {
			module.exports = classNames;
		} else if (true) {
			// register as 'classnames', consistent with npm package name
			!(__WEBPACK_AMD_DEFINE_ARRAY__ = [], __WEBPACK_AMD_DEFINE_RESULT__ = function () {
				return classNames;
			}.apply(exports, __WEBPACK_AMD_DEFINE_ARRAY__), __WEBPACK_AMD_DEFINE_RESULT__ !== undefined && (module.exports = __WEBPACK_AMD_DEFINE_RESULT__));
		} else {
			window.classNames = classNames;
		}
	}());


/***/ },
/* 3 */
/***/ function(module, exports) {

	module.exports = jQuery;

/***/ },
/* 4 */
/***/ function(module, exports, __webpack_require__) {

	var jQuery = __webpack_require__(3);

	/*!
	 * jQuery UI Position 1.10.4
	 * http://jqueryui.com
	 *
	 * Copyright 2014 jQuery Foundation and other contributors
	 * Released under the MIT license.
	 * http://jquery.org/license
	 *
	 * http://api.jqueryui.com/position/
	 */
	(function( $, undefined ) {

	$.ui = $.ui || {};

	var cachedScrollbarWidth,
		max = Math.max,
		abs = Math.abs,
		round = Math.round,
		rhorizontal = /left|center|right/,
		rvertical = /top|center|bottom/,
		roffset = /[\+\-]\d+(\.[\d]+)?%?/,
		rposition = /^\w+/,
		rpercent = /%$/,
		_position = $.fn.position;

	function getOffsets( offsets, width, height ) {
		return [
			parseFloat( offsets[ 0 ] ) * ( rpercent.test( offsets[ 0 ] ) ? width / 100 : 1 ),
			parseFloat( offsets[ 1 ] ) * ( rpercent.test( offsets[ 1 ] ) ? height / 100 : 1 )
		];
	}

	function parseCss( element, property ) {
		return parseInt( $.css( element, property ), 10 ) || 0;
	}

	function getDimensions( elem ) {
		var raw = elem[0];
		if ( raw.nodeType === 9 ) {
			return {
				width: elem.width(),
				height: elem.height(),
				offset: { top: 0, left: 0 }
			};
		}
		if ( $.isWindow( raw ) ) {
			return {
				width: elem.width(),
				height: elem.height(),
				offset: { top: elem.scrollTop(), left: elem.scrollLeft() }
			};
		}
		if ( raw.preventDefault ) {
			return {
				width: 0,
				height: 0,
				offset: { top: raw.pageY, left: raw.pageX }
			};
		}
		return {
			width: elem.outerWidth(),
			height: elem.outerHeight(),
			offset: elem.offset()
		};
	}

	$.position = {
		scrollbarWidth: function() {
			if ( cachedScrollbarWidth !== undefined ) {
				return cachedScrollbarWidth;
			}
			var w1, w2,
				div = $( "<div style='display:block;position:absolute;width:50px;height:50px;overflow:hidden;'><div style='height:100px;width:auto;'></div></div>" ),
				innerDiv = div.children()[0];

			$( "body" ).append( div );
			w1 = innerDiv.offsetWidth;
			div.css( "overflow", "scroll" );

			w2 = innerDiv.offsetWidth;

			if ( w1 === w2 ) {
				w2 = div[0].clientWidth;
			}

			div.remove();

			return (cachedScrollbarWidth = w1 - w2);
		},
		getScrollInfo: function( within ) {
			var overflowX = within.isWindow || within.isDocument ? "" :
					within.element.css( "overflow-x" ),
				overflowY = within.isWindow || within.isDocument ? "" :
					within.element.css( "overflow-y" ),
				hasOverflowX = overflowX === "scroll" ||
					( overflowX === "auto" && within.width < within.element[0].scrollWidth ),
				hasOverflowY = overflowY === "scroll" ||
					( overflowY === "auto" && within.height < within.element[0].scrollHeight );
			return {
				width: hasOverflowY ? $.position.scrollbarWidth() : 0,
				height: hasOverflowX ? $.position.scrollbarWidth() : 0
			};
		},
		getWithinInfo: function( element ) {
			var withinElement = $( element || window ),
				isWindow = $.isWindow( withinElement[0] ),
				isDocument = !!withinElement[ 0 ] && withinElement[ 0 ].nodeType === 9;
			return {
				element: withinElement,
				isWindow: isWindow,
				isDocument: isDocument,
				offset: withinElement.offset() || { left: 0, top: 0 },
				scrollLeft: withinElement.scrollLeft(),
				scrollTop: withinElement.scrollTop(),
				width: isWindow ? withinElement.width() : withinElement.outerWidth(),
				height: isWindow ? withinElement.height() : withinElement.outerHeight()
			};
		}
	};

	$.fn.position = function( options ) {
		if ( !options || !options.of ) {
			return _position.apply( this, arguments );
		}

		// make a copy, we don't want to modify arguments
		options = $.extend( {}, options );

		var atOffset, targetWidth, targetHeight, targetOffset, basePosition, dimensions,
			target = $( options.of ),
			within = $.position.getWithinInfo( options.within ),
			scrollInfo = $.position.getScrollInfo( within ),
			collision = ( options.collision || "flip" ).split( " " ),
			offsets = {};

		dimensions = getDimensions( target );
		if ( target[0].preventDefault ) {
			// force left top to allow flipping
			options.at = "left top";
		}
		targetWidth = dimensions.width;
		targetHeight = dimensions.height;
		targetOffset = dimensions.offset;
		// clone to reuse original targetOffset later
		basePosition = $.extend( {}, targetOffset );

		// force my and at to have valid horizontal and vertical positions
		// if a value is missing or invalid, it will be converted to center
		$.each( [ "my", "at" ], function() {
			var pos = ( options[ this ] || "" ).split( " " ),
				horizontalOffset,
				verticalOffset;

			if ( pos.length === 1) {
				pos = rhorizontal.test( pos[ 0 ] ) ?
					pos.concat( [ "center" ] ) :
					rvertical.test( pos[ 0 ] ) ?
						[ "center" ].concat( pos ) :
						[ "center", "center" ];
			}
			pos[ 0 ] = rhorizontal.test( pos[ 0 ] ) ? pos[ 0 ] : "center";
			pos[ 1 ] = rvertical.test( pos[ 1 ] ) ? pos[ 1 ] : "center";

			// calculate offsets
			horizontalOffset = roffset.exec( pos[ 0 ] );
			verticalOffset = roffset.exec( pos[ 1 ] );
			offsets[ this ] = [
				horizontalOffset ? horizontalOffset[ 0 ] : 0,
				verticalOffset ? verticalOffset[ 0 ] : 0
			];

			// reduce to just the positions without the offsets
			options[ this ] = [
				rposition.exec( pos[ 0 ] )[ 0 ],
				rposition.exec( pos[ 1 ] )[ 0 ]
			];
		});

		// normalize collision option
		if ( collision.length === 1 ) {
			collision[ 1 ] = collision[ 0 ];
		}

		if ( options.at[ 0 ] === "right" ) {
			basePosition.left += targetWidth;
		} else if ( options.at[ 0 ] === "center" ) {
			basePosition.left += targetWidth / 2;
		}

		if ( options.at[ 1 ] === "bottom" ) {
			basePosition.top += targetHeight;
		} else if ( options.at[ 1 ] === "center" ) {
			basePosition.top += targetHeight / 2;
		}

		atOffset = getOffsets( offsets.at, targetWidth, targetHeight );
		basePosition.left += atOffset[ 0 ];
		basePosition.top += atOffset[ 1 ];

		return this.each(function() {
			var collisionPosition, using,
				elem = $( this ),
				elemWidth = elem.outerWidth(),
				elemHeight = elem.outerHeight(),
				marginLeft = parseCss( this, "marginLeft" ),
				marginTop = parseCss( this, "marginTop" ),
				collisionWidth = elemWidth + marginLeft + parseCss( this, "marginRight" ) + scrollInfo.width,
				collisionHeight = elemHeight + marginTop + parseCss( this, "marginBottom" ) + scrollInfo.height,
				position = $.extend( {}, basePosition ),
				myOffset = getOffsets( offsets.my, elem.outerWidth(), elem.outerHeight() );

			if ( options.my[ 0 ] === "right" ) {
				position.left -= elemWidth;
			} else if ( options.my[ 0 ] === "center" ) {
				position.left -= elemWidth / 2;
			}

			if ( options.my[ 1 ] === "bottom" ) {
				position.top -= elemHeight;
			} else if ( options.my[ 1 ] === "center" ) {
				position.top -= elemHeight / 2;
			}

			position.left += myOffset[ 0 ];
			position.top += myOffset[ 1 ];

			// if the browser doesn't support fractions, then round for consistent results
			if ( !$.support.offsetFractions ) {
				position.left = round( position.left );
				position.top = round( position.top );
			}

			collisionPosition = {
				marginLeft: marginLeft,
				marginTop: marginTop
			};

			$.each( [ "left", "top" ], function( i, dir ) {
				if ( $.ui.position[ collision[ i ] ] ) {
					$.ui.position[ collision[ i ] ][ dir ]( position, {
						targetWidth: targetWidth,
						targetHeight: targetHeight,
						elemWidth: elemWidth,
						elemHeight: elemHeight,
						collisionPosition: collisionPosition,
						collisionWidth: collisionWidth,
						collisionHeight: collisionHeight,
						offset: [ atOffset[ 0 ] + myOffset[ 0 ], atOffset [ 1 ] + myOffset[ 1 ] ],
						my: options.my,
						at: options.at,
						within: within,
						elem : elem
					});
				}
			});

			if ( options.using ) {
				// adds feedback as second argument to using callback, if present
				using = function( props ) {
					var left = targetOffset.left - position.left,
						right = left + targetWidth - elemWidth,
						top = targetOffset.top - position.top,
						bottom = top + targetHeight - elemHeight,
						feedback = {
							target: {
								element: target,
								left: targetOffset.left,
								top: targetOffset.top,
								width: targetWidth,
								height: targetHeight
							},
							element: {
								element: elem,
								left: position.left,
								top: position.top,
								width: elemWidth,
								height: elemHeight
							},
							horizontal: right < 0 ? "left" : left > 0 ? "right" : "center",
							vertical: bottom < 0 ? "top" : top > 0 ? "bottom" : "middle"
						};
					if ( targetWidth < elemWidth && abs( left + right ) < targetWidth ) {
						feedback.horizontal = "center";
					}
					if ( targetHeight < elemHeight && abs( top + bottom ) < targetHeight ) {
						feedback.vertical = "middle";
					}
					if ( max( abs( left ), abs( right ) ) > max( abs( top ), abs( bottom ) ) ) {
						feedback.important = "horizontal";
					} else {
						feedback.important = "vertical";
					}
					options.using.call( this, props, feedback );
				};
			}

			elem.offset( $.extend( position, { using: using } ) );
		});
	};

	$.ui.position = {
		fit: {
			left: function( position, data ) {
				var within = data.within,
					withinOffset = within.isWindow ? within.scrollLeft : within.offset.left,
					outerWidth = within.width,
					collisionPosLeft = position.left - data.collisionPosition.marginLeft,
					overLeft = withinOffset - collisionPosLeft,
					overRight = collisionPosLeft + data.collisionWidth - outerWidth - withinOffset,
					newOverRight;

				// element is wider than within
				if ( data.collisionWidth > outerWidth ) {
					// element is initially over the left side of within
					if ( overLeft > 0 && overRight <= 0 ) {
						newOverRight = position.left + overLeft + data.collisionWidth - outerWidth - withinOffset;
						position.left += overLeft - newOverRight;
					// element is initially over right side of within
					} else if ( overRight > 0 && overLeft <= 0 ) {
						position.left = withinOffset;
					// element is initially over both left and right sides of within
					} else {
						if ( overLeft > overRight ) {
							position.left = withinOffset + outerWidth - data.collisionWidth;
						} else {
							position.left = withinOffset;
						}
					}
				// too far left -> align with left edge
				} else if ( overLeft > 0 ) {
					position.left += overLeft;
				// too far right -> align with right edge
				} else if ( overRight > 0 ) {
					position.left -= overRight;
				// adjust based on position and margin
				} else {
					position.left = max( position.left - collisionPosLeft, position.left );
				}
			},
			top: function( position, data ) {
				var within = data.within,
					withinOffset = within.isWindow ? within.scrollTop : within.offset.top,
					outerHeight = data.within.height,
					collisionPosTop = position.top - data.collisionPosition.marginTop,
					overTop = withinOffset - collisionPosTop,
					overBottom = collisionPosTop + data.collisionHeight - outerHeight - withinOffset,
					newOverBottom;

				// element is taller than within
				if ( data.collisionHeight > outerHeight ) {
					// element is initially over the top of within
					if ( overTop > 0 && overBottom <= 0 ) {
						newOverBottom = position.top + overTop + data.collisionHeight - outerHeight - withinOffset;
						position.top += overTop - newOverBottom;
					// element is initially over bottom of within
					} else if ( overBottom > 0 && overTop <= 0 ) {
						position.top = withinOffset;
					// element is initially over both top and bottom of within
					} else {
						if ( overTop > overBottom ) {
							position.top = withinOffset + outerHeight - data.collisionHeight;
						} else {
							position.top = withinOffset;
						}
					}
				// too far up -> align with top
				} else if ( overTop > 0 ) {
					position.top += overTop;
				// too far down -> align with bottom edge
				} else if ( overBottom > 0 ) {
					position.top -= overBottom;
				// adjust based on position and margin
				} else {
					position.top = max( position.top - collisionPosTop, position.top );
				}
			}
		},
		flip: {
			left: function( position, data ) {
				var within = data.within,
					withinOffset = within.offset.left + within.scrollLeft,
					outerWidth = within.width,
					offsetLeft = within.isWindow ? within.scrollLeft : within.offset.left,
					collisionPosLeft = position.left - data.collisionPosition.marginLeft,
					overLeft = collisionPosLeft - offsetLeft,
					overRight = collisionPosLeft + data.collisionWidth - outerWidth - offsetLeft,
					myOffset = data.my[ 0 ] === "left" ?
						-data.elemWidth :
						data.my[ 0 ] === "right" ?
							data.elemWidth :
							0,
					atOffset = data.at[ 0 ] === "left" ?
						data.targetWidth :
						data.at[ 0 ] === "right" ?
							-data.targetWidth :
							0,
					offset = -2 * data.offset[ 0 ],
					newOverRight,
					newOverLeft;

				if ( overLeft < 0 ) {
					newOverRight = position.left + myOffset + atOffset + offset + data.collisionWidth - outerWidth - withinOffset;
					if ( newOverRight < 0 || newOverRight < abs( overLeft ) ) {
						position.left += myOffset + atOffset + offset;
					}
				}
				else if ( overRight > 0 ) {
					newOverLeft = position.left - data.collisionPosition.marginLeft + myOffset + atOffset + offset - offsetLeft;
					if ( newOverLeft > 0 || abs( newOverLeft ) < overRight ) {
						position.left += myOffset + atOffset + offset;
					}
				}
			},
			top: function( position, data ) {
				var within = data.within,
					withinOffset = within.offset.top + within.scrollTop,
					outerHeight = within.height,
					offsetTop = within.isWindow ? within.scrollTop : within.offset.top,
					collisionPosTop = position.top - data.collisionPosition.marginTop,
					overTop = collisionPosTop - offsetTop,
					overBottom = collisionPosTop + data.collisionHeight - outerHeight - offsetTop,
					top = data.my[ 1 ] === "top",
					myOffset = top ?
						-data.elemHeight :
						data.my[ 1 ] === "bottom" ?
							data.elemHeight :
							0,
					atOffset = data.at[ 1 ] === "top" ?
						data.targetHeight :
						data.at[ 1 ] === "bottom" ?
							-data.targetHeight :
							0,
					offset = -2 * data.offset[ 1 ],
					newOverTop,
					newOverBottom;
				if ( overTop < 0 ) {
					newOverBottom = position.top + myOffset + atOffset + offset + data.collisionHeight - outerHeight - withinOffset;
					if ( ( position.top + myOffset + atOffset + offset) > overTop && ( newOverBottom < 0 || newOverBottom < abs( overTop ) ) ) {
						position.top += myOffset + atOffset + offset;
					}
				}
				else if ( overBottom > 0 ) {
					newOverTop = position.top - data.collisionPosition.marginTop + myOffset + atOffset + offset - offsetTop;
					if ( ( position.top + myOffset + atOffset + offset) > overBottom && ( newOverTop > 0 || abs( newOverTop ) < overBottom ) ) {
						position.top += myOffset + atOffset + offset;
					}
				}
			}
		},
		flipfit: {
			left: function() {
				$.ui.position.flip.left.apply( this, arguments );
				$.ui.position.fit.left.apply( this, arguments );
			},
			top: function() {
				$.ui.position.flip.top.apply( this, arguments );
				$.ui.position.fit.top.apply( this, arguments );
			}
		}
	};

	// fraction support test
	(function () {
		var testElement, testElementParent, testElementStyle, offsetLeft, i,
			body = document.getElementsByTagName( "body" )[ 0 ],
			div = document.createElement( "div" );

		//Create a "fake body" for testing based on method used in jQuery.support
		testElement = document.createElement( body ? "div" : "body" );
		testElementStyle = {
			visibility: "hidden",
			width: 0,
			height: 0,
			border: 0,
			margin: 0,
			background: "none"
		};
		if ( body ) {
			$.extend( testElementStyle, {
				position: "absolute",
				left: "-1000px",
				top: "-1000px"
			});
		}
		for ( i in testElementStyle ) {
			testElement.style[ i ] = testElementStyle[ i ];
		}
		testElement.appendChild( div );
		testElementParent = body || document.documentElement;
		testElementParent.insertBefore( testElement, testElementParent.firstChild );

		div.style.cssText = "position: absolute; left: 10.7432222px;";

		offsetLeft = $( div ).offset().left;
		$.support.offsetFractions = offsetLeft > 10 && offsetLeft < 11;

		testElement.innerHTML = "";
		testElementParent.removeChild( testElement );
	})();

	}( jQuery ) );


/***/ },
/* 5 */
/***/ function(module, exports) {

	/**
	 * A no-operation function that returns `undefined` regardless of the
	 * arguments it receives.
	 *
	 * @static
	 * @memberOf _
	 * @category Utility
	 * @example
	 *
	 * var object = { 'user': 'fred' };
	 *
	 * _.noop(object) === undefined;
	 * // => true
	 */
	function noop() {
	  // No operation performed.
	}

	module.exports = noop;


/***/ },
/* 6 */
/***/ function(module, exports, __webpack_require__) {

	module.exports = __webpack_require__(7);


/***/ },
/* 7 */
/***/ function(module, exports, __webpack_require__) {

	var arrayEach = __webpack_require__(8),
	    baseEach = __webpack_require__(9),
	    createForEach = __webpack_require__(30);

	/**
	 * Iterates over elements of `collection` invoking `iteratee` for each element.
	 * The `iteratee` is bound to `thisArg` and invoked with three arguments:
	 * (value, index|key, collection). Iteratee functions may exit iteration early
	 * by explicitly returning `false`.
	 *
	 * **Note:** As with other "Collections" methods, objects with a "length" property
	 * are iterated like arrays. To avoid this behavior `_.forIn` or `_.forOwn`
	 * may be used for object iteration.
	 *
	 * @static
	 * @memberOf _
	 * @alias each
	 * @category Collection
	 * @param {Array|Object|string} collection The collection to iterate over.
	 * @param {Function} [iteratee=_.identity] The function invoked per iteration.
	 * @param {*} [thisArg] The `this` binding of `iteratee`.
	 * @returns {Array|Object|string} Returns `collection`.
	 * @example
	 *
	 * _([1, 2]).forEach(function(n) {
	 *   console.log(n);
	 * }).value();
	 * // => logs each value from left to right and returns the array
	 *
	 * _.forEach({ 'a': 1, 'b': 2 }, function(n, key) {
	 *   console.log(n, key);
	 * });
	 * // => logs each value-key pair and returns the object (iteration order is not guaranteed)
	 */
	var forEach = createForEach(arrayEach, baseEach);

	module.exports = forEach;


/***/ },
/* 8 */
/***/ function(module, exports) {

	/**
	 * A specialized version of `_.forEach` for arrays without support for callback
	 * shorthands and `this` binding.
	 *
	 * @private
	 * @param {Array} array The array to iterate over.
	 * @param {Function} iteratee The function invoked per iteration.
	 * @returns {Array} Returns `array`.
	 */
	function arrayEach(array, iteratee) {
	  var index = -1,
	      length = array.length;

	  while (++index < length) {
	    if (iteratee(array[index], index, array) === false) {
	      break;
	    }
	  }
	  return array;
	}

	module.exports = arrayEach;


/***/ },
/* 9 */
/***/ function(module, exports, __webpack_require__) {

	var baseForOwn = __webpack_require__(10),
	    createBaseEach = __webpack_require__(29);

	/**
	 * The base implementation of `_.forEach` without support for callback
	 * shorthands and `this` binding.
	 *
	 * @private
	 * @param {Array|Object|string} collection The collection to iterate over.
	 * @param {Function} iteratee The function invoked per iteration.
	 * @returns {Array|Object|string} Returns `collection`.
	 */
	var baseEach = createBaseEach(baseForOwn);

	module.exports = baseEach;


/***/ },
/* 10 */
/***/ function(module, exports, __webpack_require__) {

	var baseFor = __webpack_require__(11),
	    keys = __webpack_require__(15);

	/**
	 * The base implementation of `_.forOwn` without support for callback
	 * shorthands and `this` binding.
	 *
	 * @private
	 * @param {Object} object The object to iterate over.
	 * @param {Function} iteratee The function invoked per iteration.
	 * @returns {Object} Returns `object`.
	 */
	function baseForOwn(object, iteratee) {
	  return baseFor(object, iteratee, keys);
	}

	module.exports = baseForOwn;


/***/ },
/* 11 */
/***/ function(module, exports, __webpack_require__) {

	var createBaseFor = __webpack_require__(12);

	/**
	 * The base implementation of `baseForIn` and `baseForOwn` which iterates
	 * over `object` properties returned by `keysFunc` invoking `iteratee` for
	 * each property. Iteratee functions may exit iteration early by explicitly
	 * returning `false`.
	 *
	 * @private
	 * @param {Object} object The object to iterate over.
	 * @param {Function} iteratee The function invoked per iteration.
	 * @param {Function} keysFunc The function to get the keys of `object`.
	 * @returns {Object} Returns `object`.
	 */
	var baseFor = createBaseFor();

	module.exports = baseFor;


/***/ },
/* 12 */
/***/ function(module, exports, __webpack_require__) {

	var toObject = __webpack_require__(13);

	/**
	 * Creates a base function for `_.forIn` or `_.forInRight`.
	 *
	 * @private
	 * @param {boolean} [fromRight] Specify iterating from right to left.
	 * @returns {Function} Returns the new base function.
	 */
	function createBaseFor(fromRight) {
	  return function(object, iteratee, keysFunc) {
	    var iterable = toObject(object),
	        props = keysFunc(object),
	        length = props.length,
	        index = fromRight ? length : -1;

	    while ((fromRight ? index-- : ++index < length)) {
	      var key = props[index];
	      if (iteratee(iterable[key], key, iterable) === false) {
	        break;
	      }
	    }
	    return object;
	  };
	}

	module.exports = createBaseFor;


/***/ },
/* 13 */
/***/ function(module, exports, __webpack_require__) {

	var isObject = __webpack_require__(14);

	/**
	 * Converts `value` to an object if it's not one.
	 *
	 * @private
	 * @param {*} value The value to process.
	 * @returns {Object} Returns the object.
	 */
	function toObject(value) {
	  return isObject(value) ? value : Object(value);
	}

	module.exports = toObject;


/***/ },
/* 14 */
/***/ function(module, exports) {

	/**
	 * Checks if `value` is the [language type](https://es5.github.io/#x8) of `Object`.
	 * (e.g. arrays, functions, objects, regexes, `new Number(0)`, and `new String('')`)
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is an object, else `false`.
	 * @example
	 *
	 * _.isObject({});
	 * // => true
	 *
	 * _.isObject([1, 2, 3]);
	 * // => true
	 *
	 * _.isObject(1);
	 * // => false
	 */
	function isObject(value) {
	  // Avoid a V8 JIT bug in Chrome 19-20.
	  // See https://code.google.com/p/v8/issues/detail?id=2291 for more details.
	  var type = typeof value;
	  return !!value && (type == 'object' || type == 'function');
	}

	module.exports = isObject;


/***/ },
/* 15 */
/***/ function(module, exports, __webpack_require__) {

	var getNative = __webpack_require__(16),
	    isArrayLike = __webpack_require__(20),
	    isObject = __webpack_require__(14),
	    shimKeys = __webpack_require__(24);

	/* Native method references for those with the same name as other `lodash` methods. */
	var nativeKeys = getNative(Object, 'keys');

	/**
	 * Creates an array of the own enumerable property names of `object`.
	 *
	 * **Note:** Non-object values are coerced to objects. See the
	 * [ES spec](http://ecma-international.org/ecma-262/6.0/#sec-object.keys)
	 * for more details.
	 *
	 * @static
	 * @memberOf _
	 * @category Object
	 * @param {Object} object The object to query.
	 * @returns {Array} Returns the array of property names.
	 * @example
	 *
	 * function Foo() {
	 *   this.a = 1;
	 *   this.b = 2;
	 * }
	 *
	 * Foo.prototype.c = 3;
	 *
	 * _.keys(new Foo);
	 * // => ['a', 'b'] (iteration order is not guaranteed)
	 *
	 * _.keys('hi');
	 * // => ['0', '1']
	 */
	var keys = !nativeKeys ? shimKeys : function(object) {
	  var Ctor = object == null ? undefined : object.constructor;
	  if ((typeof Ctor == 'function' && Ctor.prototype === object) ||
	      (typeof object != 'function' && isArrayLike(object))) {
	    return shimKeys(object);
	  }
	  return isObject(object) ? nativeKeys(object) : [];
	};

	module.exports = keys;


/***/ },
/* 16 */
/***/ function(module, exports, __webpack_require__) {

	var isNative = __webpack_require__(17);

	/**
	 * Gets the native function at `key` of `object`.
	 *
	 * @private
	 * @param {Object} object The object to query.
	 * @param {string} key The key of the method to get.
	 * @returns {*} Returns the function if it's native, else `undefined`.
	 */
	function getNative(object, key) {
	  var value = object == null ? undefined : object[key];
	  return isNative(value) ? value : undefined;
	}

	module.exports = getNative;


/***/ },
/* 17 */
/***/ function(module, exports, __webpack_require__) {

	var isFunction = __webpack_require__(18),
	    isObjectLike = __webpack_require__(19);

	/** Used to detect host constructors (Safari > 5). */
	var reIsHostCtor = /^\[object .+?Constructor\]$/;

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/** Used to resolve the decompiled source of functions. */
	var fnToString = Function.prototype.toString;

	/** Used to check objects for own properties. */
	var hasOwnProperty = objectProto.hasOwnProperty;

	/** Used to detect if a method is native. */
	var reIsNative = RegExp('^' +
	  fnToString.call(hasOwnProperty).replace(/[\\^$.*+?()[\]{}|]/g, '\\$&')
	  .replace(/hasOwnProperty|(function).*?(?=\\\()| for .+?(?=\\\])/g, '$1.*?') + '$'
	);

	/**
	 * Checks if `value` is a native function.
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is a native function, else `false`.
	 * @example
	 *
	 * _.isNative(Array.prototype.push);
	 * // => true
	 *
	 * _.isNative(_);
	 * // => false
	 */
	function isNative(value) {
	  if (value == null) {
	    return false;
	  }
	  if (isFunction(value)) {
	    return reIsNative.test(fnToString.call(value));
	  }
	  return isObjectLike(value) && reIsHostCtor.test(value);
	}

	module.exports = isNative;


/***/ },
/* 18 */
/***/ function(module, exports, __webpack_require__) {

	var isObject = __webpack_require__(14);

	/** `Object#toString` result references. */
	var funcTag = '[object Function]';

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/**
	 * Used to resolve the [`toStringTag`](http://ecma-international.org/ecma-262/6.0/#sec-object.prototype.tostring)
	 * of values.
	 */
	var objToString = objectProto.toString;

	/**
	 * Checks if `value` is classified as a `Function` object.
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is correctly classified, else `false`.
	 * @example
	 *
	 * _.isFunction(_);
	 * // => true
	 *
	 * _.isFunction(/abc/);
	 * // => false
	 */
	function isFunction(value) {
	  // The use of `Object#toString` avoids issues with the `typeof` operator
	  // in older versions of Chrome and Safari which return 'function' for regexes
	  // and Safari 8 which returns 'object' for typed array constructors.
	  return isObject(value) && objToString.call(value) == funcTag;
	}

	module.exports = isFunction;


/***/ },
/* 19 */
/***/ function(module, exports) {

	/**
	 * Checks if `value` is object-like.
	 *
	 * @private
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is object-like, else `false`.
	 */
	function isObjectLike(value) {
	  return !!value && typeof value == 'object';
	}

	module.exports = isObjectLike;


/***/ },
/* 20 */
/***/ function(module, exports, __webpack_require__) {

	var getLength = __webpack_require__(21),
	    isLength = __webpack_require__(23);

	/**
	 * Checks if `value` is array-like.
	 *
	 * @private
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is array-like, else `false`.
	 */
	function isArrayLike(value) {
	  return value != null && isLength(getLength(value));
	}

	module.exports = isArrayLike;


/***/ },
/* 21 */
/***/ function(module, exports, __webpack_require__) {

	var baseProperty = __webpack_require__(22);

	/**
	 * Gets the "length" property value of `object`.
	 *
	 * **Note:** This function is used to avoid a [JIT bug](https://bugs.webkit.org/show_bug.cgi?id=142792)
	 * that affects Safari on at least iOS 8.1-8.3 ARM64.
	 *
	 * @private
	 * @param {Object} object The object to query.
	 * @returns {*} Returns the "length" value.
	 */
	var getLength = baseProperty('length');

	module.exports = getLength;


/***/ },
/* 22 */
/***/ function(module, exports) {

	/**
	 * The base implementation of `_.property` without support for deep paths.
	 *
	 * @private
	 * @param {string} key The key of the property to get.
	 * @returns {Function} Returns the new function.
	 */
	function baseProperty(key) {
	  return function(object) {
	    return object == null ? undefined : object[key];
	  };
	}

	module.exports = baseProperty;


/***/ },
/* 23 */
/***/ function(module, exports) {

	/**
	 * Used as the [maximum length](http://ecma-international.org/ecma-262/6.0/#sec-number.max_safe_integer)
	 * of an array-like value.
	 */
	var MAX_SAFE_INTEGER = 9007199254740991;

	/**
	 * Checks if `value` is a valid array-like length.
	 *
	 * **Note:** This function is based on [`ToLength`](http://ecma-international.org/ecma-262/6.0/#sec-tolength).
	 *
	 * @private
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is a valid length, else `false`.
	 */
	function isLength(value) {
	  return typeof value == 'number' && value > -1 && value % 1 == 0 && value <= MAX_SAFE_INTEGER;
	}

	module.exports = isLength;


/***/ },
/* 24 */
/***/ function(module, exports, __webpack_require__) {

	var isArguments = __webpack_require__(25),
	    isArray = __webpack_require__(26),
	    isIndex = __webpack_require__(27),
	    isLength = __webpack_require__(23),
	    keysIn = __webpack_require__(28);

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/** Used to check objects for own properties. */
	var hasOwnProperty = objectProto.hasOwnProperty;

	/**
	 * A fallback implementation of `Object.keys` which creates an array of the
	 * own enumerable property names of `object`.
	 *
	 * @private
	 * @param {Object} object The object to query.
	 * @returns {Array} Returns the array of property names.
	 */
	function shimKeys(object) {
	  var props = keysIn(object),
	      propsLength = props.length,
	      length = propsLength && object.length;

	  var allowIndexes = !!length && isLength(length) &&
	    (isArray(object) || isArguments(object));

	  var index = -1,
	      result = [];

	  while (++index < propsLength) {
	    var key = props[index];
	    if ((allowIndexes && isIndex(key, length)) || hasOwnProperty.call(object, key)) {
	      result.push(key);
	    }
	  }
	  return result;
	}

	module.exports = shimKeys;


/***/ },
/* 25 */
/***/ function(module, exports, __webpack_require__) {

	var isArrayLike = __webpack_require__(20),
	    isObjectLike = __webpack_require__(19);

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/** Used to check objects for own properties. */
	var hasOwnProperty = objectProto.hasOwnProperty;

	/** Native method references. */
	var propertyIsEnumerable = objectProto.propertyIsEnumerable;

	/**
	 * Checks if `value` is classified as an `arguments` object.
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is correctly classified, else `false`.
	 * @example
	 *
	 * _.isArguments(function() { return arguments; }());
	 * // => true
	 *
	 * _.isArguments([1, 2, 3]);
	 * // => false
	 */
	function isArguments(value) {
	  return isObjectLike(value) && isArrayLike(value) &&
	    hasOwnProperty.call(value, 'callee') && !propertyIsEnumerable.call(value, 'callee');
	}

	module.exports = isArguments;


/***/ },
/* 26 */
/***/ function(module, exports, __webpack_require__) {

	var getNative = __webpack_require__(16),
	    isLength = __webpack_require__(23),
	    isObjectLike = __webpack_require__(19);

	/** `Object#toString` result references. */
	var arrayTag = '[object Array]';

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/**
	 * Used to resolve the [`toStringTag`](http://ecma-international.org/ecma-262/6.0/#sec-object.prototype.tostring)
	 * of values.
	 */
	var objToString = objectProto.toString;

	/* Native method references for those with the same name as other `lodash` methods. */
	var nativeIsArray = getNative(Array, 'isArray');

	/**
	 * Checks if `value` is classified as an `Array` object.
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is correctly classified, else `false`.
	 * @example
	 *
	 * _.isArray([1, 2, 3]);
	 * // => true
	 *
	 * _.isArray(function() { return arguments; }());
	 * // => false
	 */
	var isArray = nativeIsArray || function(value) {
	  return isObjectLike(value) && isLength(value.length) && objToString.call(value) == arrayTag;
	};

	module.exports = isArray;


/***/ },
/* 27 */
/***/ function(module, exports) {

	/** Used to detect unsigned integer values. */
	var reIsUint = /^\d+$/;

	/**
	 * Used as the [maximum length](http://ecma-international.org/ecma-262/6.0/#sec-number.max_safe_integer)
	 * of an array-like value.
	 */
	var MAX_SAFE_INTEGER = 9007199254740991;

	/**
	 * Checks if `value` is a valid array-like index.
	 *
	 * @private
	 * @param {*} value The value to check.
	 * @param {number} [length=MAX_SAFE_INTEGER] The upper bounds of a valid index.
	 * @returns {boolean} Returns `true` if `value` is a valid index, else `false`.
	 */
	function isIndex(value, length) {
	  value = (typeof value == 'number' || reIsUint.test(value)) ? +value : -1;
	  length = length == null ? MAX_SAFE_INTEGER : length;
	  return value > -1 && value % 1 == 0 && value < length;
	}

	module.exports = isIndex;


/***/ },
/* 28 */
/***/ function(module, exports, __webpack_require__) {

	var isArguments = __webpack_require__(25),
	    isArray = __webpack_require__(26),
	    isIndex = __webpack_require__(27),
	    isLength = __webpack_require__(23),
	    isObject = __webpack_require__(14);

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/** Used to check objects for own properties. */
	var hasOwnProperty = objectProto.hasOwnProperty;

	/**
	 * Creates an array of the own and inherited enumerable property names of `object`.
	 *
	 * **Note:** Non-object values are coerced to objects.
	 *
	 * @static
	 * @memberOf _
	 * @category Object
	 * @param {Object} object The object to query.
	 * @returns {Array} Returns the array of property names.
	 * @example
	 *
	 * function Foo() {
	 *   this.a = 1;
	 *   this.b = 2;
	 * }
	 *
	 * Foo.prototype.c = 3;
	 *
	 * _.keysIn(new Foo);
	 * // => ['a', 'b', 'c'] (iteration order is not guaranteed)
	 */
	function keysIn(object) {
	  if (object == null) {
	    return [];
	  }
	  if (!isObject(object)) {
	    object = Object(object);
	  }
	  var length = object.length;
	  length = (length && isLength(length) &&
	    (isArray(object) || isArguments(object)) && length) || 0;

	  var Ctor = object.constructor,
	      index = -1,
	      isProto = typeof Ctor == 'function' && Ctor.prototype === object,
	      result = Array(length),
	      skipIndexes = length > 0;

	  while (++index < length) {
	    result[index] = (index + '');
	  }
	  for (var key in object) {
	    if (!(skipIndexes && isIndex(key, length)) &&
	        !(key == 'constructor' && (isProto || !hasOwnProperty.call(object, key)))) {
	      result.push(key);
	    }
	  }
	  return result;
	}

	module.exports = keysIn;


/***/ },
/* 29 */
/***/ function(module, exports, __webpack_require__) {

	var getLength = __webpack_require__(21),
	    isLength = __webpack_require__(23),
	    toObject = __webpack_require__(13);

	/**
	 * Creates a `baseEach` or `baseEachRight` function.
	 *
	 * @private
	 * @param {Function} eachFunc The function to iterate over a collection.
	 * @param {boolean} [fromRight] Specify iterating from right to left.
	 * @returns {Function} Returns the new base function.
	 */
	function createBaseEach(eachFunc, fromRight) {
	  return function(collection, iteratee) {
	    var length = collection ? getLength(collection) : 0;
	    if (!isLength(length)) {
	      return eachFunc(collection, iteratee);
	    }
	    var index = fromRight ? length : -1,
	        iterable = toObject(collection);

	    while ((fromRight ? index-- : ++index < length)) {
	      if (iteratee(iterable[index], index, iterable) === false) {
	        break;
	      }
	    }
	    return collection;
	  };
	}

	module.exports = createBaseEach;


/***/ },
/* 30 */
/***/ function(module, exports, __webpack_require__) {

	var bindCallback = __webpack_require__(31),
	    isArray = __webpack_require__(26);

	/**
	 * Creates a function for `_.forEach` or `_.forEachRight`.
	 *
	 * @private
	 * @param {Function} arrayFunc The function to iterate over an array.
	 * @param {Function} eachFunc The function to iterate over a collection.
	 * @returns {Function} Returns the new each function.
	 */
	function createForEach(arrayFunc, eachFunc) {
	  return function(collection, iteratee, thisArg) {
	    return (typeof iteratee == 'function' && thisArg === undefined && isArray(collection))
	      ? arrayFunc(collection, iteratee)
	      : eachFunc(collection, bindCallback(iteratee, thisArg, 3));
	  };
	}

	module.exports = createForEach;


/***/ },
/* 31 */
/***/ function(module, exports, __webpack_require__) {

	var identity = __webpack_require__(32);

	/**
	 * A specialized version of `baseCallback` which only supports `this` binding
	 * and specifying the number of arguments to provide to `func`.
	 *
	 * @private
	 * @param {Function} func The function to bind.
	 * @param {*} thisArg The `this` binding of `func`.
	 * @param {number} [argCount] The number of arguments to provide to `func`.
	 * @returns {Function} Returns the callback.
	 */
	function bindCallback(func, thisArg, argCount) {
	  if (typeof func != 'function') {
	    return identity;
	  }
	  if (thisArg === undefined) {
	    return func;
	  }
	  switch (argCount) {
	    case 1: return function(value) {
	      return func.call(thisArg, value);
	    };
	    case 3: return function(value, index, collection) {
	      return func.call(thisArg, value, index, collection);
	    };
	    case 4: return function(accumulator, value, index, collection) {
	      return func.call(thisArg, accumulator, value, index, collection);
	    };
	    case 5: return function(value, other, key, object, source) {
	      return func.call(thisArg, value, other, key, object, source);
	    };
	  }
	  return function() {
	    return func.apply(thisArg, arguments);
	  };
	}

	module.exports = bindCallback;


/***/ },
/* 32 */
/***/ function(module, exports) {

	/**
	 * This method returns the first argument provided to it.
	 *
	 * @static
	 * @memberOf _
	 * @category Utility
	 * @param {*} value Any value.
	 * @returns {*} Returns `value`.
	 * @example
	 *
	 * var object = { 'user': 'fred' };
	 *
	 * _.identity(object) === object;
	 * // => true
	 */
	function identity(value) {
	  return value;
	}

	module.exports = identity;


/***/ },
/* 33 */
/***/ function(module, exports, __webpack_require__) {

	module.exports = __webpack_require__(34);


/***/ },
/* 34 */
/***/ function(module, exports, __webpack_require__) {

	var baseIndexOf = __webpack_require__(35),
	    getLength = __webpack_require__(21),
	    isArray = __webpack_require__(26),
	    isIterateeCall = __webpack_require__(37),
	    isLength = __webpack_require__(23),
	    isString = __webpack_require__(38),
	    values = __webpack_require__(39);

	/* Native method references for those with the same name as other `lodash` methods. */
	var nativeMax = Math.max;

	/**
	 * Checks if `target` is in `collection` using
	 * [`SameValueZero`](http://ecma-international.org/ecma-262/6.0/#sec-samevaluezero)
	 * for equality comparisons. If `fromIndex` is negative, it's used as the offset
	 * from the end of `collection`.
	 *
	 * @static
	 * @memberOf _
	 * @alias contains, include
	 * @category Collection
	 * @param {Array|Object|string} collection The collection to search.
	 * @param {*} target The value to search for.
	 * @param {number} [fromIndex=0] The index to search from.
	 * @param- {Object} [guard] Enables use as a callback for functions like `_.reduce`.
	 * @returns {boolean} Returns `true` if a matching element is found, else `false`.
	 * @example
	 *
	 * _.includes([1, 2, 3], 1);
	 * // => true
	 *
	 * _.includes([1, 2, 3], 1, 2);
	 * // => false
	 *
	 * _.includes({ 'user': 'fred', 'age': 40 }, 'fred');
	 * // => true
	 *
	 * _.includes('pebbles', 'eb');
	 * // => true
	 */
	function includes(collection, target, fromIndex, guard) {
	  var length = collection ? getLength(collection) : 0;
	  if (!isLength(length)) {
	    collection = values(collection);
	    length = collection.length;
	  }
	  if (typeof fromIndex != 'number' || (guard && isIterateeCall(target, fromIndex, guard))) {
	    fromIndex = 0;
	  } else {
	    fromIndex = fromIndex < 0 ? nativeMax(length + fromIndex, 0) : (fromIndex || 0);
	  }
	  return (typeof collection == 'string' || !isArray(collection) && isString(collection))
	    ? (fromIndex <= length && collection.indexOf(target, fromIndex) > -1)
	    : (!!length && baseIndexOf(collection, target, fromIndex) > -1);
	}

	module.exports = includes;


/***/ },
/* 35 */
/***/ function(module, exports, __webpack_require__) {

	var indexOfNaN = __webpack_require__(36);

	/**
	 * The base implementation of `_.indexOf` without support for binary searches.
	 *
	 * @private
	 * @param {Array} array The array to search.
	 * @param {*} value The value to search for.
	 * @param {number} fromIndex The index to search from.
	 * @returns {number} Returns the index of the matched value, else `-1`.
	 */
	function baseIndexOf(array, value, fromIndex) {
	  if (value !== value) {
	    return indexOfNaN(array, fromIndex);
	  }
	  var index = fromIndex - 1,
	      length = array.length;

	  while (++index < length) {
	    if (array[index] === value) {
	      return index;
	    }
	  }
	  return -1;
	}

	module.exports = baseIndexOf;


/***/ },
/* 36 */
/***/ function(module, exports) {

	/**
	 * Gets the index at which the first occurrence of `NaN` is found in `array`.
	 *
	 * @private
	 * @param {Array} array The array to search.
	 * @param {number} fromIndex The index to search from.
	 * @param {boolean} [fromRight] Specify iterating from right to left.
	 * @returns {number} Returns the index of the matched `NaN`, else `-1`.
	 */
	function indexOfNaN(array, fromIndex, fromRight) {
	  var length = array.length,
	      index = fromIndex + (fromRight ? 0 : -1);

	  while ((fromRight ? index-- : ++index < length)) {
	    var other = array[index];
	    if (other !== other) {
	      return index;
	    }
	  }
	  return -1;
	}

	module.exports = indexOfNaN;


/***/ },
/* 37 */
/***/ function(module, exports, __webpack_require__) {

	var isArrayLike = __webpack_require__(20),
	    isIndex = __webpack_require__(27),
	    isObject = __webpack_require__(14);

	/**
	 * Checks if the provided arguments are from an iteratee call.
	 *
	 * @private
	 * @param {*} value The potential iteratee value argument.
	 * @param {*} index The potential iteratee index or key argument.
	 * @param {*} object The potential iteratee object argument.
	 * @returns {boolean} Returns `true` if the arguments are from an iteratee call, else `false`.
	 */
	function isIterateeCall(value, index, object) {
	  if (!isObject(object)) {
	    return false;
	  }
	  var type = typeof index;
	  if (type == 'number'
	      ? (isArrayLike(object) && isIndex(index, object.length))
	      : (type == 'string' && index in object)) {
	    var other = object[index];
	    return value === value ? (value === other) : (other !== other);
	  }
	  return false;
	}

	module.exports = isIterateeCall;


/***/ },
/* 38 */
/***/ function(module, exports, __webpack_require__) {

	var isObjectLike = __webpack_require__(19);

	/** `Object#toString` result references. */
	var stringTag = '[object String]';

	/** Used for native method references. */
	var objectProto = Object.prototype;

	/**
	 * Used to resolve the [`toStringTag`](http://ecma-international.org/ecma-262/6.0/#sec-object.prototype.tostring)
	 * of values.
	 */
	var objToString = objectProto.toString;

	/**
	 * Checks if `value` is classified as a `String` primitive or object.
	 *
	 * @static
	 * @memberOf _
	 * @category Lang
	 * @param {*} value The value to check.
	 * @returns {boolean} Returns `true` if `value` is correctly classified, else `false`.
	 * @example
	 *
	 * _.isString('abc');
	 * // => true
	 *
	 * _.isString(1);
	 * // => false
	 */
	function isString(value) {
	  return typeof value == 'string' || (isObjectLike(value) && objToString.call(value) == stringTag);
	}

	module.exports = isString;


/***/ },
/* 39 */
/***/ function(module, exports, __webpack_require__) {

	var baseValues = __webpack_require__(40),
	    keys = __webpack_require__(15);

	/**
	 * Creates an array of the own enumerable property values of `object`.
	 *
	 * **Note:** Non-object values are coerced to objects.
	 *
	 * @static
	 * @memberOf _
	 * @category Object
	 * @param {Object} object The object to query.
	 * @returns {Array} Returns the array of property values.
	 * @example
	 *
	 * function Foo() {
	 *   this.a = 1;
	 *   this.b = 2;
	 * }
	 *
	 * Foo.prototype.c = 3;
	 *
	 * _.values(new Foo);
	 * // => [1, 2] (iteration order is not guaranteed)
	 *
	 * _.values('hi');
	 * // => ['h', 'i']
	 */
	function values(object) {
	  return baseValues(object, keys(object));
	}

	module.exports = values;


/***/ },
/* 40 */
/***/ function(module, exports) {

	/**
	 * The base implementation of `_.values` and `_.valuesIn` which creates an
	 * array of `object` property values corresponding to the property names
	 * of `props`.
	 *
	 * @private
	 * @param {Object} object The object to query.
	 * @param {Array} props The property names to get values for.
	 * @returns {Object} Returns the array of property values.
	 */
	function baseValues(object, props) {
	  var index = -1,
	      length = props.length,
	      result = Array(length);

	  while (++index < length) {
	    result[index] = object[props[index]];
	  }
	  return result;
	}

	module.exports = baseValues;


/***/ },
/* 41 */
/***/ function(module, exports, __webpack_require__) {

	module.exports = __webpack_require__(42);


/***/ },
/* 42 */
/***/ function(module, exports, __webpack_require__) {

	var assignWith = __webpack_require__(43),
	    baseAssign = __webpack_require__(44),
	    createAssigner = __webpack_require__(46);

	/**
	 * Assigns own enumerable properties of source object(s) to the destination
	 * object. Subsequent sources overwrite property assignments of previous sources.
	 * If `customizer` is provided it's invoked to produce the assigned values.
	 * The `customizer` is bound to `thisArg` and invoked with five arguments:
	 * (objectValue, sourceValue, key, object, source).
	 *
	 * **Note:** This method mutates `object` and is based on
	 * [`Object.assign`](http://ecma-international.org/ecma-262/6.0/#sec-object.assign).
	 *
	 * @static
	 * @memberOf _
	 * @alias extend
	 * @category Object
	 * @param {Object} object The destination object.
	 * @param {...Object} [sources] The source objects.
	 * @param {Function} [customizer] The function to customize assigned values.
	 * @param {*} [thisArg] The `this` binding of `customizer`.
	 * @returns {Object} Returns `object`.
	 * @example
	 *
	 * _.assign({ 'user': 'barney' }, { 'age': 40 }, { 'user': 'fred' });
	 * // => { 'user': 'fred', 'age': 40 }
	 *
	 * // using a customizer callback
	 * var defaults = _.partialRight(_.assign, function(value, other) {
	 *   return _.isUndefined(value) ? other : value;
	 * });
	 *
	 * defaults({ 'user': 'barney' }, { 'age': 36 }, { 'user': 'fred' });
	 * // => { 'user': 'barney', 'age': 36 }
	 */
	var assign = createAssigner(function(object, source, customizer) {
	  return customizer
	    ? assignWith(object, source, customizer)
	    : baseAssign(object, source);
	});

	module.exports = assign;


/***/ },
/* 43 */
/***/ function(module, exports, __webpack_require__) {

	var keys = __webpack_require__(15);

	/**
	 * A specialized version of `_.assign` for customizing assigned values without
	 * support for argument juggling, multiple sources, and `this` binding `customizer`
	 * functions.
	 *
	 * @private
	 * @param {Object} object The destination object.
	 * @param {Object} source The source object.
	 * @param {Function} customizer The function to customize assigned values.
	 * @returns {Object} Returns `object`.
	 */
	function assignWith(object, source, customizer) {
	  var index = -1,
	      props = keys(source),
	      length = props.length;

	  while (++index < length) {
	    var key = props[index],
	        value = object[key],
	        result = customizer(value, source[key], key, object, source);

	    if ((result === result ? (result !== value) : (value === value)) ||
	        (value === undefined && !(key in object))) {
	      object[key] = result;
	    }
	  }
	  return object;
	}

	module.exports = assignWith;


/***/ },
/* 44 */
/***/ function(module, exports, __webpack_require__) {

	var baseCopy = __webpack_require__(45),
	    keys = __webpack_require__(15);

	/**
	 * The base implementation of `_.assign` without support for argument juggling,
	 * multiple sources, and `customizer` functions.
	 *
	 * @private
	 * @param {Object} object The destination object.
	 * @param {Object} source The source object.
	 * @returns {Object} Returns `object`.
	 */
	function baseAssign(object, source) {
	  return source == null
	    ? object
	    : baseCopy(source, keys(source), object);
	}

	module.exports = baseAssign;


/***/ },
/* 45 */
/***/ function(module, exports) {

	/**
	 * Copies properties of `source` to `object`.
	 *
	 * @private
	 * @param {Object} source The object to copy properties from.
	 * @param {Array} props The property names to copy.
	 * @param {Object} [object={}] The object to copy properties to.
	 * @returns {Object} Returns `object`.
	 */
	function baseCopy(source, props, object) {
	  object || (object = {});

	  var index = -1,
	      length = props.length;

	  while (++index < length) {
	    var key = props[index];
	    object[key] = source[key];
	  }
	  return object;
	}

	module.exports = baseCopy;


/***/ },
/* 46 */
/***/ function(module, exports, __webpack_require__) {

	var bindCallback = __webpack_require__(31),
	    isIterateeCall = __webpack_require__(37),
	    restParam = __webpack_require__(47);

	/**
	 * Creates a `_.assign`, `_.defaults`, or `_.merge` function.
	 *
	 * @private
	 * @param {Function} assigner The function to assign values.
	 * @returns {Function} Returns the new assigner function.
	 */
	function createAssigner(assigner) {
	  return restParam(function(object, sources) {
	    var index = -1,
	        length = object == null ? 0 : sources.length,
	        customizer = length > 2 ? sources[length - 2] : undefined,
	        guard = length > 2 ? sources[2] : undefined,
	        thisArg = length > 1 ? sources[length - 1] : undefined;

	    if (typeof customizer == 'function') {
	      customizer = bindCallback(customizer, thisArg, 5);
	      length -= 2;
	    } else {
	      customizer = typeof thisArg == 'function' ? thisArg : undefined;
	      length -= (customizer ? 1 : 0);
	    }
	    if (guard && isIterateeCall(sources[0], sources[1], guard)) {
	      customizer = length < 3 ? undefined : customizer;
	      length = 1;
	    }
	    while (++index < length) {
	      var source = sources[index];
	      if (source) {
	        assigner(object, source, customizer);
	      }
	    }
	    return object;
	  });
	}

	module.exports = createAssigner;


/***/ },
/* 47 */
/***/ function(module, exports) {

	/** Used as the `TypeError` message for "Functions" methods. */
	var FUNC_ERROR_TEXT = 'Expected a function';

	/* Native method references for those with the same name as other `lodash` methods. */
	var nativeMax = Math.max;

	/**
	 * Creates a function that invokes `func` with the `this` binding of the
	 * created function and arguments from `start` and beyond provided as an array.
	 *
	 * **Note:** This method is based on the [rest parameter](https://developer.mozilla.org/Web/JavaScript/Reference/Functions/rest_parameters).
	 *
	 * @static
	 * @memberOf _
	 * @category Function
	 * @param {Function} func The function to apply a rest parameter to.
	 * @param {number} [start=func.length-1] The start position of the rest parameter.
	 * @returns {Function} Returns the new function.
	 * @example
	 *
	 * var say = _.restParam(function(what, names) {
	 *   return what + ' ' + _.initial(names).join(', ') +
	 *     (_.size(names) > 1 ? ', & ' : '') + _.last(names);
	 * });
	 *
	 * say('hello', 'fred', 'barney', 'pebbles');
	 * // => 'hello fred, barney, & pebbles'
	 */
	function restParam(func, start) {
	  if (typeof func != 'function') {
	    throw new TypeError(FUNC_ERROR_TEXT);
	  }
	  start = nativeMax(start === undefined ? (func.length - 1) : (+start || 0), 0);
	  return function() {
	    var args = arguments,
	        index = -1,
	        length = nativeMax(args.length - start, 0),
	        rest = Array(length);

	    while (++index < length) {
	      rest[index] = args[start + index];
	    }
	    switch (start) {
	      case 0: return func.call(this, rest);
	      case 1: return func.call(this, args[0], rest);
	      case 2: return func.call(this, args[0], args[1], rest);
	    }
	    var otherArgs = Array(start + 1);
	    index = -1;
	    while (++index < start) {
	      otherArgs[index] = args[index];
	    }
	    otherArgs[start] = rest;
	    return func.apply(this, otherArgs);
	  };
	}

	module.exports = restParam;


/***/ },
/* 48 */
/***/ function(module, exports, __webpack_require__) {

	var baseToString = __webpack_require__(49);

	/** Used to generate unique IDs. */
	var idCounter = 0;

	/**
	 * Generates a unique ID. If `prefix` is provided the ID is appended to it.
	 *
	 * @static
	 * @memberOf _
	 * @category Utility
	 * @param {string} [prefix] The value to prefix the ID with.
	 * @returns {string} Returns the unique ID.
	 * @example
	 *
	 * _.uniqueId('contact_');
	 * // => 'contact_104'
	 *
	 * _.uniqueId();
	 * // => '105'
	 */
	function uniqueId(prefix) {
	  var id = ++idCounter;
	  return baseToString(prefix) + id;
	}

	module.exports = uniqueId;


/***/ },
/* 49 */
/***/ function(module, exports) {

	/**
	 * Converts `value` to a string if it's not one. An empty string is returned
	 * for `null` or `undefined` values.
	 *
	 * @private
	 * @param {*} value The value to process.
	 * @returns {string} Returns the string.
	 */
	function baseToString(value) {
	  return value == null ? '' : (value + '');
	}

	module.exports = baseToString;


/***/ }
/******/ ]);