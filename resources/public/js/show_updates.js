$( document ).ready(function() {
    // Set up XmlRpcRequest to update UI here

    console.log( "ready!" );
});

function updateCueGrid( data ) {
    $.each( data, function( key, val ) {
        $('#' + val.id).css('background-color', val.color);
        $('#' + val.id).html(val.name);
    });
}

function updateShow() {
    var jqxhr = $.getJSON( (context + "/ui-updates/" + page_id), function( data ) {
        $.each( data, function( key, val ) {
            switch (key) {
            case "grid-changes":
                updateCueGrid(val);
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

updateShow();
