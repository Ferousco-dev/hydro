package com.ferousco.hydro.core.model

/**
 * Where Hydro plans trips and finds places: Africa, with Nigeria as the main market.
 *
 * The outline is a coarse coastline (a few hundred kilometres of slack at worst, much less on most
 * coasts), enough to tell "somewhere in Africa" from "another continent". It is not a border map, so
 * do not use it to decide which country a point is in.
 */
object AfricaCoverage {

    // Longitude, latitude pairs, clockwise from Tangier along the Mediterranean.
    private val Mainland: List<Pair<Double, Double>> = listOf(
        -5.5 to 36.1, -2.0 to 35.2, 0.0 to 35.9, 3.0 to 36.8, 6.0 to 37.0, 8.6 to 36.95, 10.0 to 37.3,
        11.1 to 37.0, 10.6 to 35.8, 10.2 to 34.3, 11.0 to 33.2, 13.0 to 32.9, 15.2 to 32.3, 15.7 to 31.4,
        19.0 to 30.3, 20.1 to 32.1, 21.5 to 32.9, 22.6 to 32.8, 25.1 to 31.6, 27.5 to 31.3, 29.9 to 31.2,
        31.0 to 31.6, 32.3 to 31.3, 32.6 to 30.0, 33.6 to 27.9, 35.0 to 24.0, 36.9 to 22.0, 37.2 to 19.6,
        38.5 to 18.0, 39.5 to 15.6, 41.5 to 13.5, 43.1 to 12.7, 43.3 to 11.7, 45.0 to 10.4, 47.0 to 11.2,
        49.0 to 11.5, 51.3 to 11.8, 51.0 to 10.4, 50.7 to 8.2, 49.0 to 6.0, 48.0 to 4.5, 45.3 to 2.0,
        44.0 to 1.0, 42.5 to -0.5, 41.5 to -1.7, 40.0 to -2.8, 39.7 to -4.05, 39.3 to -6.8, 39.5 to -8.5,
        40.4 to -10.5, 40.6 to -12.5, 40.7 to -15.0, 39.0 to -17.0, 36.9 to -17.9, 34.8 to -19.9,
        35.4 to -23.9, 33.0 to -25.9, 32.4 to -28.8, 30.9 to -29.9, 28.0 to -32.7, 26.2 to -33.7,
        22.0 to -34.2, 20.0 to -34.83, 18.5 to -34.4, 18.4 to -33.9, 17.9 to -32.0, 16.4 to -28.6,
        15.2 to -27.0, 14.5 to -22.9, 11.8 to -17.3, 13.4 to -12.6, 13.2 to -8.8, 12.3 to -6.0,
        11.9 to -4.8, 8.7 to -0.6, 9.5 to 1.0, 9.9 to 2.9, 9.7 to 4.0, 8.3 to 4.5, 7.0 to 4.4, 5.2 to 5.4,
        4.5 to 6.2, 3.4 to 6.4, 1.2 to 6.1, -0.2 to 5.5, -2.0 to 4.7, -3.0 to 5.0, -4.0 to 5.3,
        -6.5 to 4.4, -7.5 to 4.4, -10.8 to 6.3, -13.3 to 8.5, -13.7 to 9.5, -16.5 to 12.0, -17.5 to 14.7,
        -16.5 to 16.0, -16.1 to 19.0, -17.0 to 20.8, -16.0 to 23.0, -14.5 to 26.0, -12.0 to 28.0,
        -9.6 to 30.4, -9.8 to 31.5, -7.6 to 33.6, -6.8 to 34.0, -6.0 to 35.0, -5.9 to 35.8
    )

    private val Madagascar: List<Pair<Double, Double>> = listOf(
        49.3 to -12.0, 50.5 to -15.5, 48.9 to -20.0, 47.0 to -25.5, 44.0 to -25.0, 43.5 to -21.0,
        44.3 to -16.0, 48.6 to -12.5
    )

    fun contains(latitude: Double, longitude: Double): Boolean =
        inside(Mainland, latitude, longitude) || inside(Madagascar, latitude, longitude)

    fun contains(location: GeoLocation): Boolean = contains(location.latitude, location.longitude)

    /** Standard ray-casting test over a (longitude, latitude) outline. */
    private fun inside(outline: List<Pair<Double, Double>>, latitude: Double, longitude: Double): Boolean {
        var inside = false
        var previous = outline.last()
        for (point in outline) {
            val (x1, y1) = previous
            val (x2, y2) = point
            if ((y2 > latitude) != (y1 > latitude) &&
                longitude < (x1 - x2) * (latitude - y2) / (y1 - y2) + x2
            ) {
                inside = !inside
            }
            previous = point
        }
        return inside
    }
}
