      iNumSpots:   { type: 'i', value: {{ count }} },
      iSpotPosition: { type: 'v3v', value: [ 
        {% for pos in positions %}new THREE.Vector3({%
           for val in pos %}{{val}}{% if not forloop.last %}, {% endif %}{% endfor %}){% if not forloop.last %},
        {% endif %}{% endfor %} ] },
      iSpotRotation: { type: 'v2v', value: [ 
        {% for rot in rotations %}new THREE.Vector2({%
           for val in rot %}{{val}}{% if not forloop.last %}, {% endif %}{% endfor %}){% if not forloop.last %},
        {% endif %}{% endfor %} ] },
      iSpotColor: { type: 'v4v', value: [ 
        {% for col in colors %}new THREE.Vector4({%
           for val in col %}{{val}}{% if not forloop.last %}, {% endif %}{% endfor %}){% if not forloop.last %},
        {% endif %}{% endfor %} ] },
