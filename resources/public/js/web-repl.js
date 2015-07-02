function sendCommand(line) {
    var jqxhr = $.post( (context + "/console"),
                        { "command": line,
                          "__anti-forgery-token": csrf_token }, function (data) {
                              $.each( data, function( key, val ) {
                                  switch (key) {
                                  case "result":
                                      currentCallback([{msg: val,
                                                        className: "jquery-console-message-value"}]);
                                      break;

                                  default:
                                      currentCallback([{msg: val,
                                                        className: "jquery-console-message-error"}]);
                                  }
                              });
                          }, "json").fail(function() {
                              currentCallback([
                                  {msg: "Problem communicating with Afterglow Web REPL.",
                                   className: "jquery-console-message-error"}
                              ]);
                          });
}

function updateSize() {
    $('#console').css({ height: $(window).height() - 100 });
    $('.jquery-console-inner').css({ height: $(window).height() - 120 });
}

$(document).ready(function () {
    updateSize();
    $(window).resize(updateSize);
    $("#console").console({
        promptLabel: 'Afterglow> ',
        commandValidate:function(line) {
            if (line == "") {
                return false;
            }
            else {
                return true;
            }
        },
        commandHandle:function(line, callback) {
            currentCallback = callback;
            sendCommand(line);
        },
        welcomeMessage:'Enter Clojure code, and it will be evaluated within Afterglow.',
        autofocus:true,
        animateScroll:true,
        promptHistory:true
    });
});
