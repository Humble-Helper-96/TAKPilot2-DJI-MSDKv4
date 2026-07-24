package com.dji.sdk.sample.takpilot2

import android.content.Context

/**
 * Map styles. The original mapbox:// styles need a Mapbox token; TAKPilot2 replaces them
 * with free, self-contained MapLibre raster styles (inline JSON) backed by open tile
 * sources — no API key required.
 *
 * setStyle(String) accepts a full style-JSON document, so each constant below IS the style.
 */
object MaplibreStyle {

    // Street map: OpenStreetMap standard raster tiles.
    const val MAPBOX_STREETS = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors",
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#1a1a1a" } },
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""

    // Satellite: Esri World Imagery (free, no key).
    const val SATELLITE = """
{
  "version": 8,
  "sources": {
    "sat": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "attribution": "© Esri",
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "sat", "type": "raster", "source": "sat" }
  ]
}
"""

    // Satellite + streets: Esri imagery with a translucent OSM road/label overlay.
    const val SATELLITE_STREETS = """
{
  "version": 8,
  "sources": {
    "sat": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256, "maxzoom": 19
    },
    "osm": {
      "type": "raster",
      "tiles": ["https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256, "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors, © Esri"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "sat", "type": "raster", "source": "sat" },
    { "id": "osm", "type": "raster", "source": "osm", "paint": { "raster-opacity": 0.35 } }
  ]
}
"""

    /** Pilot-supplied custom raster XYZ tile source (Pre-Flight Setup → Map Display → Custom),
     *  e.g. a self-hosted MapTiler/TileServer endpoint. Same structure as the constants above.
     *  Fixed 256px tile size — if a custom source actually serves 512px tiles this may render
     *  soft/misaligned; not worth a second "tile size" field until that's actually hit. */
    fun custom(tileUrlTemplate: String): String = """
{
  "version": 8,
  "sources": {
    "custom": {
      "type": "raster",
      "tiles": ["$tileUrlTemplate"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "custom", "type": "raster", "source": "custom" }
  ]
}
"""

    private const val PREFS = "takpilot2_tak"
    private const val KEY_STYLE = "map_style"       // "street" | "hybrid" | "custom"
    private const val KEY_CUSTOM_URL = "map_custom_url"

    fun savedStyleChoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, "street") ?: "street"

    fun savedCustomUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_URL, "") ?: ""

    fun saveStyleChoice(context: Context, choice: String, customUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STYLE, choice)
            .putString(KEY_CUSTOM_URL, customUrl.trim())
            .apply()
    }

    /** Resolves the pilot's saved map-style choice (Pre-Flight Setup → Map Display) to the
     *  actual style JSON to pass to setStyle(). Falls back to hybrid (the pre-existing default)
     *  for "custom" with no URL saved yet, rather than requesting an empty tile source. */
    fun selectedStyleJson(context: Context): String = when (savedStyleChoice(context)) {
        "street" -> MAPBOX_STREETS
        "custom" -> savedCustomUrl(context).let { if (it.isBlank()) SATELLITE_STREETS else custom(it) }
        else -> SATELLITE_STREETS
    }
}
