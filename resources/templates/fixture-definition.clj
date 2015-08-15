(ns afterglow.fixtures.{{manufacturer|sanitize}}
  "Translated definition for the fixture {{model}}
  from {{manufacturer}}.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and will almost certainly need some manual adjustment
  in order to enable full Afterglow capabilities.{% if any has-pan-channel has-tilt-channel %} For example, this
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
  {:channels [{% for ch in m.channels %}{% if not forloop.first %}
              {% endif %}nil{% endfor %}]
   :name "{{model}}"{% if has-pan-channel %}
   :pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
   {% endif %}{% if has-tilt-channel %}
   :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
   {% endif %}
   ;; To be fleshed out 1: heads
   }
{% else %}([]
   ({{model|sanitize}} :{{modes.0.name|sanitize}}))
  ([mode]
   (let [build-channel (fn [c offset]
                         (case c{% for ch in channels %}
                           :{{ch.name|sanitize}} nil{% endfor %}))]
     (assoc (case mode{% for m in modes %}
                  :{{m.name|sanitize}}
                  {:channels [{% for ch in m.channels %}{% if not forloop.first %}
                              {% endif %}(build-channel :{{ch|sanitize}} {{forloop.counter}}){% endfor %}]}{% endfor %})
            :name "{{model}}"
            :mode mode{% if has-pan-channel %}
            :pan-center 128 :pan-half-circle 128    ; TODO: Fix these values{% endif %}{% if has-tilt-channel %}
            :tilt-center 128 :tilt-half-circle 128  ; TODO: Fix these values{% endif %}
            ;; To be fleshed out 2: heads
            ))){% endif %})
