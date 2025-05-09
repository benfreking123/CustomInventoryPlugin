# CustomInventoryPlugin
Dependacies:
- Fabled
- Divinity

/gear command opens a custom inventory window

settings.yml allows for setting custom slots as new equiptable slots
Items allowed in each slot must have the lore formatting of 
'{form}: {type}', ex: 'Type: Ring' which is specificed in the settings.yml


```
# Debug mode settings
debug:
  enabled: false
  log-level: INFO # DEBUG, INFO, WARNING, ERROR

# Armor slots (fixed positions)
armor-slots:
  helmet: 0
  chestplate: 9
  leggings: 18
  boots: 27

# Custom slots configuration
# Lore Format <form>: <type>
custom-slots:
  slots:
    0:
      enabled: true
      form: "Type"
      type: "Ring"
      position: 2
    1:
      enabled: false
      form: "Type"
      type: "Amulet"
      position: 3
    2:
      enabled: false
      form: "Type"
      type: "Bracelet"
      position: 11
    3:
      enabled: false
      form: "Type"
      type: "Relic"
      position: 12
    4:
      enabled: false
      form: "Type"
      type: "Talisman"
      position: 20
    5:
      enabled: false
      form: "Type"
      type: "Charm"
      position: 21
    6:
      enabled: false
      form: "Type"
      type: "Medallion"
      position: 29
    7:
      enabled: false
      form: "Type"
      type: "Trinket"
      position: 30

# Item type settings
item-types:
  form: "Type"  # The form text (e.g., "Type: Ring")
  type: "Ring"  # The type text (e.g., "Type: Ring") 
```
