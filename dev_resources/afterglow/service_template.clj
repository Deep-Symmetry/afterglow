(ns afterglow.{{classname|lower}}-service
  (:require [flatland.protobuf.core :refer :all])
  (import [{{package}}{% for item in declared-classes %} {{classname}}${{item}}{% endfor %}]))
