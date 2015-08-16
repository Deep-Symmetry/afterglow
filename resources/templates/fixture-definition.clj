(ns afterglow.fixtures.{{manufacturer|sanitize}}
  "Translated definition for the fixture {{model}}
  from {{manufacturer}}.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and will almost certainly need some manual adjustment
  in order to enable full Afterglow capabilities.{% if has-pan-tilt %} For example, this
  fixture has pan/tilt channels, so you need to configure the scaling
  parameters (marked with TODO below), as described in Afterglow's
  Fixture Definitions documentation:
  https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#fixture-definitions{% endif %}

  If you have more than one fixture definition for this manufacturer,
  you can consolidate them into a single file if you like, with a
  single copy of this namespace definition, since it is the same for
  all fixture definitions translated by Afterglow.

  Once you have completed the fixture definition, and are happy with
  the way everything is being controlled by Afterglow, please consider
  submitting it for inclusion with Afterglow, either as a Pull Request
  at https://github.com/brunchboy/afterglow/pulls if you are
  comfortable putting that together, or just on the Wiki if that's
  easier for you:
  https://github.com/brunchboy/afterglow/wiki/Questions#defining-fixtures

  The original fixture defintition was created by {{creator.author}}
  using {{creator.name}} version {{creator.version}}.
  QLC+ Fixture Type: {{type}}"
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :as chan-fx]))

(defn {{model|sanitize}}
  "{{model}}.

  Please flesh out this documentation if you are submitting this for
  inclusion into Afterglow. See, for example, the Blizzard fixture
  definitions:
  http://deepsymmetry.org/afterglow/doc/afterglow.fixtures.blizzard.html"
  {% if modes|length < 2 %}[]
  {{open-curly}}{% if modes.0.has-pan-channel %}:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
   {% endif %}{% if modes.0.has-tilt-channel %}:tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
   {% endif %}:channels [{% for ch in modes.0.channels %}{% if not forloop.first %}
              {% endif %}{% channel-by-name ch.1 ch.0 %}{% endfor %}]{% if modes.0.heads|not-empty %}
   :heads [{% for head in modes.0.heads %}{% if not forloop.first %}
           {% endif %}{{open-curly}}{% if modes.0.heads|length > 1 %}:x 0 :y 0 :z 0 ; TODO: specify actual location! (and perhaps rotation?)
            {% endif %}{% if head.has-pan-channel %}:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
            {% endif %}{% if head.has-tilt-channel %}:tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
            {% endif %}:channels [{% for ch in head.channels %}{% if not forloop.first %}
                              {% endif %}{% channel-by-name ch.1 ch.0 %}{% endfor %}]}{% endfor %}]{% endif %}
   :name "{{model}}"}{% else %}([]
   ({{model|sanitize}} :{{modes.0.name|sanitize}}))
  ([mode]
   ;; Set up channel definitions functions for channels used by any mode
   (let [build-channel (fn [c offset]
                         (case c{% for ch in channels %}
                           :{{ch.name|sanitize}} {% channel ch %} {% endfor %}))]
     ;; Define the channels actually used by the chosen mode
     (assoc (case mode{% for m in modes %}
                  :{{m.name|sanitize}}
                  {{open-curly}}{% if m.has-pan-channel %}:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                   {% endif %}{% if m.has-tilt-channel %}:tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                   {% endif %}:channels [{% for ch in m.channels %}{% if not forloop.first %}
                              {% endif %}(build-channel :{{ch.1|sanitize}} {{ch.0}}){% endfor %}]{% if m.heads|not-empty %}
                   :heads [{% for head in m.heads %}{% if not forloop.first %}
                           {% endif %}{{open-curly}}{% if m.heads|length > 1 %}:x 0 :y 0 :z 0 ; TODO: specify actual location! (and perhaps rotation?)
                            {% endif %}{% if head.has-pan-channel %}:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                            {% endif %}{% if head.has-tilt-channel %}:tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                            {% endif %}:channels [{% for ch in head.channels %}{% if not forloop.first %}
                                       {% endif %}(build-channel :{{ch.1|sanitize}} {{ch.0}}){% endfor %}]}{% endfor %}]{% endif %}}{% endfor %})
            :name "{{model}}"
            :mode mode))){% endif %})
