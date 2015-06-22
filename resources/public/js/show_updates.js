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

function cueCellClicked( eventObject ) {
    var jqxhr = $.post( (context + "/ui-event/" + page_id + "/" + this.id),
                        { "__anti-forgery-token": csrf_token } ).fail(function() {
        console.log("Problem requesting cue toggle.");
    });
}

$( document ).ready(function() {
    $(".grid-scroll-button").click(moveButtonClicked);
    $(".cue-cell").click(cueCellClicked);
    updateShow();
});
