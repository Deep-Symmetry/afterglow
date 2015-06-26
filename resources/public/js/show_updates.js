function updateCueGrid( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).css('background-color', val.color);
        $('#' + val.id).css('color', val.textColor);
        $('#' + val.id).html(val.name);
    });
}

function updateButtons( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).prop('disabled', val.disabled);
    });
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
            $("#bpm-slider").slider("setValue", Number(val.val));
            break;

        case "blink":
            if (val.val) {
                $('#tap-tempo').addClass('metronome-blink');
            } else {
                $('#tap-tempo').removeClass('metronome-blink');
            }
            break;

        case "sync":
            if (val.val.level) {
                $("#slider-in-bpm").fadeOut();
                $("#sync-button").removeClass('btn-primary');
                if (val.val.current) {
                    $("#sync-button").removeClass('btn-danger');
                    $("#sync-button").addClass('btn-success');
                } else {
                    $("#sync-button").addClass('btn-danger');
                    $("#sync-button").removeClass('btn-success');
                }
                switch (val.val.level) {

                case "bpm":
                    $("#tap-tempo").text("Tap Beat");
                    break;

                case "beat":
                    $("#tap-tempo").text("Tap Bar");
                    break;

                case "bar":
                    $("#tap-tempo").text("Tap Phrase");
                    break;

                default:
                    $("#tap-tempo").text("Beat");
                }
                    
            } else {
                $("#slider-in-bpm").fadeIn();
                $("#sync-button").addClass('btn-primary');
                $("#sync-button").removeClass('btn-danger');
                $("#sync-button").removeClass('btn-success');
                $("#tap-tempo").text("Tap Tempo");
            }
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

function updateSyncMenu( data ) {
    $('#sync-menu').html(data);
}

function updateShow() {
    var jqxhr = $.getJSON( (context + "/ui-updates/" + page_id), function( data ) {
        $.each( data, function( key, val ) {
            switch (key) {
            case "grid-changes":
                updateCueGrid(val);
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

function moveButtonClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token } ).fail(function() {
        console.log("Problem requesting cue grid move.");
    });
}

function metronomeAdjustClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token } ).fail(function() {
        console.log("Problem requesting metronome adjustment.");
    });
}

function cueCellClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token } ).fail(function() {
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

function bpmSlide( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "value": this.value,
                          "__anti-forgery-token": csrf_token } ).fail(function() {
        console.log("Problem specifying updated BPM value.");
    });
}

function decorateMetronomeAdjusters( selector ) {
    $(selector).click(metronomeAdjustClicked);
    $(selector).hover(
        function() {
            $(this).addClass('metronome-active');
        },
        function() {
            $(this).removeClass('metronome-active');
        }
    );
}

$( document ).ready(function() {
    $(".grid-scroll-button").click(moveButtonClicked);
    decorateMetronomeAdjusters(".metronome-adjust-target");
    decorateMetronomeAdjusters(".metronome-reset-target");
    $(".cue-cell").click(cueCellClicked);
    if ($("#link-select option").length > 1) {
        $("#link-section").fadeIn();
    }
    $("#link-select").change(linkMenuChanged);
    $("#choose-sync").click(syncMenuChosen);
    // See https://github.com/seiyria/bootstrap-slider
    $("#bpm-slider").slider({id: "slider-in-bpm"}).on("slide", bpmSlide).on("change", bpmSlide);
    updateShow();
});
