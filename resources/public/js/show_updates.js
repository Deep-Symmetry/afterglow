function updateCueGrid( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).css('background-color', val.color);
        $('#' + val.id).css('color', val.textColor);
        $('#' + val.id).html(val.name);
    });
}

function updateEffectState() {
    if ($("#effects-table tr").length > 0) {
        $("#no-effects").hide();
        $("#effects-table").show();
    } else {
        $("#no-effects").show();
        $("#effects-table").hide();
    }
}

function buildEffectRow( data ) {
    var row = $('<tr></tr>', {
        "id": "effect-" + data.id
    });

    $('<td></td>')
        .text(data.name)
        .appendTo(row);

    var endCell = $('<td></td>');
    $('<button type="button" class="btn btn-warning"/>')
        .text("End")
        .click(function ( eventObject ) {
            $(this).removeClass("btn-warning").addClass("btn-danger").text("Kill");
            var jqxhr = $.post( (context + "/ui-event/" + page_id + "/end-effect"),
                                { "effect-id": data.id,
                                  "key": data.key,
                                  "__anti-forgery-token": csrf_token } )
                .fail(function() {
                    console.log("Problem ending an effect.");
                });
        })
        .appendTo(endCell);

    endCell.appendTo(row);

    return row;
}

function processEffectUpdate( data ) {
    $.each( data, function( key, val ) {
        switch (key) {

        case "ended":
            $("#effect-" + val).remove();
            break;

        case "started":
            if (val.after > 0) {
                $("#effect-" + val.after).after(buildEffectRow(val));
            } else {
                $("#effects-table").prepend(buildEffectRow(val));
            }
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
        $("#tap-tempo").text(shifted ? "Tap Beat" : "Tap Tempo");
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
    var jqxhr = $.getJSON( (context + "/ui-updates/" + page_id), function( data ) {
        $.each( data, function( key, val ) {
            switch (key) {
            case "grid-changes":
                updateCueGrid(val);
                break;

            case "effect-changes":
                updateEffectList(val);
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

function uiButtonClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token } ).fail(function() {
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
    var cell = this.id;
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + cell),
                        { "__anti-forgery-token": csrf_token,
                          "shift": eventObject.shiftKey }, function(data) {
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
                        }).fail(function() {
                            console.log("Problem requesting cue toggle.");
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
    $("#bpm-slider").slider({id: "slider-in-bpm"}).on("slideStart", bpmSlideStart).on("slide", bpmSlide).on("slideStop", bpmSlideStop);
    updateShow();
});
