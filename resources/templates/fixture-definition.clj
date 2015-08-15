(ns afterglow.fixtures.{{manufacturer|kebab}}
  "Translated definition for the fixture {{model}}
  from {{manufacturer}}.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and will almost certainly need some manual adjustment
  in order to enable full Afterglow capabilities (for example, if it
  has a moving head you need to configure the axis location and
  pan/tilt scaling parameters, as described in Afterglow's Fixture
  Definitions documentation:
  https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#fixture-definitions

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

(defn {{model|kebab}}
  "{{model}}.

  Please flesh out this documentation if you are submitting this for
  inclusion into Afterglow. See, for example, the Blizzard fixture
  definitions:
  http://deepsymmetry.org/afterglow/doc/afterglow.fixtures.blizzard.html"
  {% if modes|length < 2 %}[]
  ;; To be fleshed out 1
{% else %}([]
   ({{model|kebab}} :{{default-mode|kebab}}))
  ([mode]
   ;; To be fleshed out 2
   ){% endif %})
