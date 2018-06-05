function updateCueGrid( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).css('background-color', val.color);
        $('#' + val.id).css('color', val.textColor);
        $('#' + val.id).html(val.name);
    });
}

function updateEffectState() {
    if ($("#effects-table tbody tr").length > 0) {
        $("#no-effects").hide();
        $("#some-effects").show();
    } else {
        $("#no-effects").show();
        $("#some-effects").hide();
    }
}

function buildFraction( n ) {
    return $('<span></span>', { "class": "time-fraction" })
        .text((n < 10? ".0" : ".") + n);
}

function buildEffectRow( data ) {
    var row = $('<tr></tr>', { id: "effect-" + data.id });
    var macroBox = "";

    if (data.macro) {
        macroBox = $('<input>', { id: "effect-selected-" + data.x + "-" + data.y + "-" + data.id,
                                  type: "checkbox",
                                  "class": "macro-checkbox"
                                }
                    );
    }

    var priority = "";
    if (data.priority != 0) {
        priority = $('<span></span>', { class: "small" })
            .text(" (Priority " + data.priority + ")")
    }
    effectName = $('<td></td>')
        .append($('<h5></h5>')
                .append(macroBox)
                .append($('<span></span>')
                        .text(" " + data.name)
                       )
                .append(priority)
               )
        .appendTo(row);

    var timeFrac = $('<span></span>', { "class": "time-fraction" })
        .text("." + data["start-time-frac"]);

    var beatFrac = $('<span></span>', { "class": "time-fraction" })
        .text("." + data["start-beat-frac"]);

    $('<td></td>', { style: "text-align: right" })
        .text(data["start-time"])
        .append(buildFraction(data["start-time-frac"]))
        .append("<br>")
        .append(data["start-beat"])
        .append(buildFraction(data["start-beat-frac"]))
        .appendTo(row);

    var endCell = $('<td></td>');
    $('<button/>', { type: "button", id: "effect-" + data.id + "-end", "class": "btn btn-sm btn-warning" })
        .text("End")
        .click(function ( eventObject ) {
            var jqxhr = $.post( (context + "/ui-event/" + page_id + "/end-effect"),
                                { "effect-id": data.id,
                                  "key": data.key,
                                  "__anti-forgery-token": csrf_token } )
                .fail(function() {
                    console.log("Problem ending effect with id " + data.id + ".");
                });
        })
        .appendTo(endCell);
    endCell.appendTo(row);

    // Space for cue variables, if any
    $('<td>')
        .append($('<table>', { id: "effect-" + data.id + "-vars", "class": "effect-var-table"}))
        .appendTo(row);

    $('<td></td>')
        .append($('<button/>', { type: "button", id: "effect-" + data.id + "-save", "class": "btn btn-sm btn-success",
                                 style: "display: none" })
                .text("Save")
                .click(function ( eventObject ) {
                    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/save-effect"),
                                        { "effect-id": data.id,
                                          "effect-key": data.key,
                                          "__anti-forgery-token": csrf_token } )
                        .fail(function() {
                            console.log("Problem saving effect with id " + data.id + ".");
                        });
                })
               )
        .append($('<button/>', { type: "button", id: "effect-" + data.id + "-clear", "class": "btn btn-sm btn-default",
                                 style: "display: none" })
                .text("Clear")
                .click(function ( eventObject ) {
                    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/clear-effect"),
                                        { "effect-id": data.id,
                                          "effect-key": data.key,
                                          "__anti-forgery-token": csrf_token } )
                        .fail(function() {
                            console.log("Problem saving effect with id " + data.id + ".");
                        });
                })
               )
        .appendTo(row);

    return row;
}

var cueSlidersBeingDragged = { };
var cuePickersSentValues = { };

function sendCueVarUpdate( effectKey, id, varKey, value ) {
    //console.log("setting effectKey " + effectKey + " (id " + id + ") varKey " + varKey + " to " + value);
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/set-cue-var"),
                        { "effect-key": effectKey,
                          "effect-id": id,
                          "var-key": varKey,
                          "value": value,
                          "__anti-forgery-token": csrf_token } )
        .fail(function() {
            console.log("Problem setting cue variable for effect with id " + id + ".");
        });
}

function createCueVarRow( data, varSpec, element ) {
    var body = $('#effect-' + data.id + '-vars');
    var cueVarRow = $('<tr></tr>')
        .append($('<td></td>', { style: "text-align: right" })
                .text(varSpec.name)
                .append("&nbsp;")
               )
        .append($('<td></td>')
                .append(element)
               );

    body.append(cueVarRow);
}

function findOrCreateCueVarSlider( data, varSpec ) {
    var idBase = 'cue-var-' + data.id + '-' + varSpec.key;
    var sliderId = idBase + '-slider';
    var valueId = idBase + '-value';
    var result = $('#' + sliderId);
    if (result.length < 1) {
        var sliderProps = { value: data.value,
                            min: varSpec.min,
                            max: varSpec.max,
                            handle: "triangle",
                            tooltip: "hide" };
        if (varSpec.resolution) {
            sliderProps.step = resolution;
        } else if (varSpec.type != "integer") {
            var range =  varSpec.max - varSpec.min;
            if (range < 200) {
                sliderProps.step = range / 200;
            }
        }

        var sliderSpan = $('<span></span>');
        var sliderInput = $("<input>", { id: sliderId });
        sliderSpan.append(sliderInput);
        var valueSpan = $('<span></span>', { id: valueId });
        sliderSpan.append(valueSpan);
        createCueVarRow(data, varSpec, sliderSpan);
        sliderInput.slider(sliderProps)
            .on("slideStart", function( e ) {
                cueSlidersBeingDragged[sliderId] = true;
                sendCueVarUpdate(data.effect, data.id, varSpec.key, this.value);
            })
            .on("slide", function ( e ) {
                sendCueVarUpdate(data.effect, data.id, varSpec.key, this.value);
            })
            .on("slideStop", function ( e ) {
                sendCueVarUpdate(data.effect, data.id, varSpec.key, this.value);
                delete cueSlidersBeingDragged[sliderId];
            });

        result = $('#' + sliderId);
    }
    return result;
}

function findOrCreateCheckbox (data, varSpec ) {
    var idBase = 'cue-var-' + data.id + '-' + varSpec.key;
    var boxId = idBase + '-checkbox';
    var result = $('#' + boxId);
    if (result.length < 1) {
        var switchProps = { state: data.value,
                            size: "mini",
                            onText: "Yes",
                            offText: "No",
                            onSwitchChange: function ( e, state ) {
                                sendCueVarUpdate(data.effect, data.id, varSpec.key, state);
                            }};
        var box = $('<input>', { id: boxId,
                                 type: "checkbox",
                                 value: data.value });
        createCueVarRow(data, varSpec, box);

        result = $('#' + boxId);
        result.bootstrapSwitch(switchProps);
        result = $('#' + boxId);
    }
    return result;
}

function findOrCreateColorPicker( data, varSpec ) {
    var idBase = 'cue-var-' + data.id + '-' + varSpec.key;
    var pickerId = idBase + '-slider';
    var result = $('#' + pickerId);
    if (result.length < 1) {
        var pickerProps = {  };

        var pickerInput = $('<input>', { id: pickerId,
                                         type: "hidden",
                                         value: data.value });
        cuePickersSentValues[pickerId] = data.value;
        createCueVarRow(data, varSpec, pickerInput);
        pickerInput.minicolors({ changeDelay: 33,
                                 position: "top right",
                                 change: function( value, opacity ) {
                                     if (cuePickersSentValues[pickerId] != value) {
                                         cuePickersSentValues[pickerId] = value;
                                         sendCueVarUpdate(data.effect, data.id, varSpec.key, value);
                                     }
                                 }});

        result = $('#' + pickerId);
    }
    return result;
}

function processCueVarChange( data ) {

    //console.log(data);
    var varSpec = data["var"];

    switch (varSpec.type) {

    case "color":
        var colorPicker = findOrCreateColorPicker(data, varSpec);
        if (data.value != cuePickersSentValues[colorPicker.attr("id")]) {
            cuePickersSentValues[colorPicker.attr("id")] = data.value;
            colorPicker.minicolors('value', data.value);
        }
        break;

    case "boolean":
        var checkbox = findOrCreateCheckbox(data, varSpec);
        $(checkbox).bootstrapSwitch('state', data.value);
        break;

    default:  // Integer or float
        var varSlider = findOrCreateCueVarSlider(data, varSpec);
        if (!cueSlidersBeingDragged[varSlider.attr("id")]) {
            varSlider.slider('setValue', Number(data.value));
        }
        $('#cue-var-' + data.id + '-' + varSpec.key + '-value').text(' ' + varSlider.slider('getValue'));
        break;
    }
}

function processEffectUpdate( data ) {
    $.each( data, function( key, val ) {

        switch (key) {

        case "ending":
            $("#effect-" + val).addClass("warning");
            $("#effect-" + val + "-end").removeClass("btn-warning").addClass("btn-danger").text("Kill");
            break;

        case "ended":
            $("#effect-" + val).remove();
            break;

        case "started":
            if (val.after > 0) {
                $("#effect-" + val.after).after(buildEffectRow(val));
            } else {
                $("#effects-table > tbody").prepend(buildEffectRow(val));
            }
            if (!makingMacro) {
                $(".macro-checkbox").hide();
            }
            break;

        case "cue-var-change":
            processCueVarChange(val);
            break;

        case "add-save-button":
            $("#effect-" + val + "-save").show();
            break;

        case "add-clear-button":
            $("#effect-" + val + "-clear").show();
            break;

        case "remove-save-button":
            $("#effect-" + val + "-save").hide();
            break;

        case "remove-clear-button":
            $("#effect-" + val + "-clear").hide();
            break;
        }
    });
}

function updateEffectList( data ) {
    $.each( data, function( key, val ) {
        processEffectUpdate(val);
    });
    updateEffectState();
}

var grandMasterSliderBeingDragged = false;

function updateGrandMaster( data ) {
    if (!grandMasterSliderBeingDragged) {
        $("#grand-master-slider").slider("setValue", Number(data));
    }
}

function grandMasterSlideStart( eventObject ) {
    grandMasterSliderBeingDragged = true;
}

function sendGrandMasterUpdate( control_id, value ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + control_id),
                        { "value": value,
                          "__anti-forgery-token": csrf_token } ).fail(function() {
                              console.log("Problem specifying updated Grand Master value.");
                          });
}

function grandMasterSlide( eventObject ) {
    sendGrandMasterUpdate(this.id, this.value);
}

function grandMasterSlideStop( eventObject ) {
    grandMasterSliderBeingDragged = false;
    sendGrandMasterUpdate(this.id, this.value);
}

function updateButtons( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).prop('disabled', val.disabled);
    });
}

var bpmSliderBeingDragged = false;
var bpmSyncMode = "";
var shifted = false;

function updateTapLabel( ) {
    switch (bpmSyncMode) {

    case "bpm":
        $("#tap-tempo").text(shifted ? "Tap Bar" : "Tap Beat");
        break;

    case "beat":
        $("#tap-tempo").text(shifted ? "Tap Phrase" : "Tap Bar");
        break;

    case "bar":
        $("#tap-tempo").text("Tap Phrase");
        break;

    default:
        $("#tap-tempo").text(shifted ? "Tap Bar" : "Tap Tempo");
    }
}

function updateMetronome( data ) {
    $.each( data, function( key, val ) {
        switch (val.id) {
        case "phrase":
        case "beat":
        case "bar":
            $("#" + val.id).html(val.val);
            break;

        case "bpm":
            $("#bpm").html(val.val);
            if (!bpmSliderBeingDragged) {
                $("#bpm-slider").slider("setValue", Number(val.val));
            }
            break;

        case "blink":
            if (val.val) {
                $('#tap-tempo').addClass('metronome-blink');
            } else {
                $('#tap-tempo').removeClass('metronome-blink');
            }
            break;

        case "sync":
            bpmSyncMode = val.val.level;
            if (bpmSyncMode) {
                $("#slider-in-bpm").fadeOut();
                $("#sync-button").removeClass('btn-primary');
                if (val.val.current) {
                    $("#sync-button").removeClass('btn-danger');
                    $("#sync-button").addClass('btn-success');
                } else {
                    $("#sync-button").addClass('btn-danger');
                    $("#sync-button").removeClass('btn-success');
                }
            } else {
                $("#slider-in-bpm").fadeIn();
                $("#sync-button").addClass('btn-primary');
                $("#sync-button").removeClass('btn-danger');
                $("#sync-button").removeClass('btn-success');
            }
            updateTapLabel();
            break;
        }
    });
}

function updateLinkMenu( data ) {
    $('#link-section').html(data);
    if ($("#link-select option").length > 1) {
        $("#link-section").fadeIn();
    } else {
        $("#link-section").fadeOut();
    }
    $("#link-select").change(linkMenuChanged);
}

var syncSave = "";

function updateSyncMenu( data ) {
    syncSave = data;
    $('#sync-menu').html(data);
}

function updateLoad( data ) {
    var canvas = $("#loadBar")[0];
    if (canvas.getContext) {
        var ctx = canvas.getContext("2d");
        ctx.fillStyle = "rgb(136,136,136)";
        ctx.fillRect(0,0,100,14);
        var red = 0;
        var green = 255;
        var width = data * 100;
        if (data > 0.5) {
            red = (data - 0.5) * 512;
            if (red > 255) {
                red = 255;
            }
            green = 255 + ((0.5 - data) * 512);
            if (green < 0) {
                green = 0;
            }
        }
        if (width > 100) {
            width = 100;
        }
        ctx.fillStyle = "rgb(" + (red|0) + "," + (green|0) + ",0)";
        ctx.fillRect(0,0,width,14);
    }
}

function updateStatus( data ) {
    if (data.error) {
        $('#status').html("Error ").removeClass('text-success').addClass('text-danger').removeClass('text-warning');
        $('#errorDetailsButton').show();
        $('#errorDescription').html(data.error.description);
        $('#errorCause').html(data.error.cause);
    } else {
        $('#status').removeClass('text-danger');
        $('#errorDetailsButton').hide();
    }
    if (data.running) {
        $('#stopButton').show();
        $('#startButton').hide();
        if (!data.error) {
            $('#status').html("Running ").addClass('text-success').removeClass('text-warning');
        }
    } else {
        updateLoad(0);
        $('#stopButton').hide();
        $('#startButton').show();
        if (!data.error) {
            $('#status').html("Stopped ").removeClass('text-success').addClass('text-warning');
        }
    }
}

function updateShow() {
    try {
        var jqxhr = $.getJSON( (context + "/ui-updates/" + page_id), function( data ) {
            $.each( data, function( key, val ) {
                switch (key) {
                case "grid-changes":
                    updateCueGrid(val);
                    break;

                case "effect-changes":
                    updateEffectList(val);
                    break;

                case "grand-master":
                    updateGrandMaster(val);
                    break;

                case "button-changes":
                    updateButtons(val);
                    break;

                case "link-menu-changes":
                    updateLinkMenu(val);
                    break;

                case "sync-menu-changes":
                    updateSyncMenu(val);
                    break;

                case "metronome-changes":
                    updateMetronome(val);
                    break;

                case "load-level":
                    updateLoad(val);
                    break;

                case "show-status":
                    updateStatus(val);
                    break;

                case "reload":
                    console.log("Reloading page since Afterglow does not recognize our page ID.");
                    location.reload(true);
                    break;

                default:
                    console.log("Unknown show update response:" + key + ":" + val);
                }
            });
            setTimeout(updateShow, 50);  // Try again quickly after success
        }).fail(function() {
            console.log("Problem updating show interface, waiting for a few seconds to try again.");
            setTimeout(updateShow, 3000);
        });
    }
    catch(e) {
        console.log("Exception while updating show interface, waiting a second to try again.");
        console.log(e);
        setTimeout(updateShow, 1000);
    }
}

function uiButtonClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token,
                          "shift": eventObject.shiftKey } ).fail(function() {
        console.log("Problem reporting UI button press.");
    });
}

function errorDetailsClicked( eventObject ) {
    $("#errorDetailsModal").modal('show');
}

function metronomeAdjustClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token,
                          "shift": eventObject.shiftKey } ).fail(function() {
        console.log("Problem requesting metronome adjustment.");
    });
}

var $doc = $(document);

function cueCellClicked( eventObject ) {
    if (eventObject.which != 1) {
        return;  // Only respond to ordinary, left-button clicks.
    }
    var cell = this.id;
    var props = { "shift": eventObject.shiftKey };
    if (makingMacro) {
        var selectedEffects = [];
        var selectedBoxes = $(".macro-checkbox:checked");
        for (var i = 0; i < selectedBoxes.length; i++) {
            var elems = selectedBoxes[i].id.split("-");
            selectedEffects = selectedEffects.concat([{x: parseInt(elems[2]), y: parseInt(elems[3]),
                                                       id: parseInt(elems[4])}]);
        }
        $.extend(props, { macroName: $("#macroName").val(),
                          macroEffects: selectedEffects
                        });
    }
    var jqxhr = $.ajax({
        url: (context + "/ui-event/" + page_id + "/" + cell),
        headers: { 'X-CSRF-Token': csrf_token },
        type: 'POST',
        contentType: 'application/json',
        data: $.toJSON(props),
        success: function(data) {
            //console.log(data);
            if ('macro-created' in data) {
                if (makingMacro) {
                    makeMacroChosen(null);
                }
            }
            if ('holding' in data) {
                var id = data['holding']['id'];
                var x = data['holding']['x'];
                var y = data['holding']['y'];
                $doc.mouseup(function() {
                    var jqxhr2 = $.post( (context + "/ui-event/" + page_id +
                                          "/release-" + x + "-" + y + "-" + id),
                                         { "__anti-forgery-token": csrf_token } ).fail(function() {
                                             console.log("Problem reporting held cue release.");
                                         });
                    $doc.unbind('mouseup');
                });
            }
        },
        error: function() {
            console.log("Problem requesting cue toggle.");
        }
    });
}

function deleteCue( cell ) {
    var jqxhr = $.ajax({
        url: (context + "/ui-event/" + page_id + "/delete-" + cell.attr('id')),
        headers: { 'X-CSRF-Token': csrf_token },
        type: 'POST',
        success: function(data) {
            //console.log(data);
        },
        error: function() {
            console.log("Problem requesting cue deletion.");
        }
    });
}

function linkMenuChanged( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "value": this.value,
                          "__anti-forgery-token": csrf_token } ).fail(function() {
        console.log("Problem requesting linked controller change.");
    });
}

function syncMenuChosen( eventObject ) {
    var syncValue = $('#sync-menu input:radio:checked').val()
    if (syncValue) {
        var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                            { "value": syncValue,
                              "__anti-forgery-token": csrf_token } ).fail(function() {
                                  console.log("Problem requesting metronome sync change.");
                              });
    }
}

function makeMacroChosen (eventObject) {
    if (makingMacro) {
        $(".macro-definition").hide();
        $(".macro-checkbox").hide();
        $("#makeMacro").text("Make Macro").addClass('btn-default').removeClass('btn-danger');
        makingMacro = false;
    } else {
        $(".macro-definition").show();
        $(".macro-checkbox").attr('checked', false);
        $(".macro-checkbox").show();
        $("#makeMacro").text("Cancel").removeClass('btn-default').addClass('btn-danger');
        $("#macroName").val("Macro " + macroCounter).focus().select();
        ++macroCounter;
        makingMacro = true
    }
}

function bpmSlideStart( eventObject ) {
    bpmSliderBeingDragged = true;
}

function sendBpmUpdate( control_id, value ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + control_id),
                        { "value": value,
                          "__anti-forgery-token": csrf_token } ).fail(function() {
                              console.log("Problem specifying updated BPM value.");
                          });
}

function bpmSlide( eventObject ) {
    sendBpmUpdate(this.id, this.value);
}

function bpmSlideStop( eventObject ) {
    bpmSliderBeingDragged = false;
    sendBpmUpdate(this.id, this.value);
}

function decorateMetronomeAdjusters( selector, onMouseDown ) {
    if (onMouseDown) {
        $(selector).mousedown(metronomeAdjustClicked);
    } else {
        $(selector).click(metronomeAdjustClicked);
    }
    $(selector).hover(
        function() {
            $(this).addClass('metronome-active');
        },
        function() {
            $(this).removeClass('metronome-active');
        }
    );
}

function checkShiftKey( eventObject ) {
    if (shifted != eventObject.shiftKey) {
        shifted = eventObject.shiftKey;
        updateTapLabel();
    }
}

var cueCellMenu;

function fakeScrollClick ( direction, eventObject ) {
    if (!$('#cues-' + direction).attr("disabled")) {
        var jqxhr = $.post( (context + "/ui-event/" + page_id + "/cues-" + direction),
                            { "__anti-forgery-token": csrf_token,
                              "shift": eventObject.shiftKey } ).fail(function() {
                                console.log("Problem reporting UI button press.");
                            });
    }
}

function fakeTempoTap( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/tap-tempo"),
                        { "__anti-forgery-token": csrf_token,
                          "shift": eventObject.shiftKey } ).fail(function() {
        console.log("Problem requesting metronome adjustment.");
    });
}

$( document ).ready(function() {
    $("#startButton").click(uiButtonClicked);
    $("#stopButton").click(uiButtonClicked);
    $("#errorDetailsButton").click(errorDetailsClicked);
    $(".grid-scroll-button").click(uiButtonClicked);
    decorateMetronomeAdjusters(".metronome-tap-target", true);
    decorateMetronomeAdjusters(".metronome-adjust-target", false);
    decorateMetronomeAdjusters(".metronome-reset-target", false);
    $(".cue-cell").mousedown(cueCellClicked);
    if ($("#link-select option").length > 1) {
        $("#link-section").fadeIn();
    }
    $("#link-select").change(linkMenuChanged);
    $("#choose-sync").click(syncMenuChosen);
    $("#syncModal").on("show.bs.modal", function(event) {
        syncSave = $("#sync-menu").html();
    });
    $("#syncModal").on("hidden.bs.modal", function(event) {
        if (syncSave) {
            $("#sync-menu").html(syncSave);
        }
    });
    $("#makeMacro").click(makeMacroChosen);
    $(".macro-definition").hide();

    // Set up to keep track of the shift key and update the tap tempo label accordingly
    $( document ).on("keyup keydown", function(e) {
        checkShiftKey(e);
        return true;
    });
    $( window ).on("focusin", function(e) {
        checkShiftKey(e);
        return true;
    });

    // See https://github.com/seiyria/bootstrap-slider
    $("#bpm-slider").slider({id: "slider-in-bpm"})
        .on("slideStart", bpmSlideStart)
        .on("slide", bpmSlide)
        .on("slideStop", bpmSlideStop);
    $("#grand-master-slider").slider({ id: "slider-in-grand-master",
                                       min: 0,
                                       max: 100,
                                       step: 0.1,
                                       handle: "triangle",
                                       tooltip: "show",
                                       tooltip_position: "bottom" })
        .on("slideStart", grandMasterSlideStart)
        .on("slide", grandMasterSlide)
        .on("slideStop", grandMasterSlideStop);

    cueCellMenu = new BootstrapMenu('.cue-cell', {
        fetchElementData: function($rowElem) {
            return $rowElem;
        },
        actions: {
            /*
            renameCue: {
                name: 'Rename',
                iconClass: 'fa-pencil',
                onClick: function(row) { console.log($(row).text()); },
                isEnabled: function(row) { return !!$(row).text().trim(); }
            },
            */
            deleteCue: {
                name: 'Delete',
                iconClass: 'fa-trash-o',
                onClick: function(row) { deleteCue($(row)); },
                isEnabled: function(row) { return !!$(row).text().trim(); }
            }
        }
    });

    // Respond to arrow keys when nothing is focused by acting as if the scroll buttons had been
    // pressed.
    $('html').keydown(function(e){
        // console.log("got keydown " + e.which + " for " + $(':focus').prop("tagName"));
        if (!$(':focus').prop("tagName")) {
            switch (e.which) {

            case 38:
                fakeScrollClick("up", e);
                e.preventDefault();
                break;

            case 40:
                fakeScrollClick("down", e);
                e.preventDefault();
                break;

            case 39:
                fakeScrollClick("right", e);
                e.preventDefault();
                break;

            case 37:
                fakeScrollClick("left", e);
                e.preventDefault();
                break;

            case 32:
                fakeTempoTap(e);
                e.preventDefault();
                break;

            default:
            }
        }
    });
    // Render the current effect state, and start the thread which updates the show state.
    updateEffectState();
    updateShow();
});
