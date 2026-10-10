package com.adminstack.psieparki.data

/** The yes/no questions people filter on. Only a confirmed yes passes a filter. */
enum class Feature(val label: String, val test: (Park) -> Boolean?) {
    FENCED("Ogrodzony", { it.fenced }),
    LIT("Oświetlony", { it.lit }),
    WATER("Woda", { it.water }),
    AGILITY("Agility", { it.agility }),
    SMALL_DOGS("Dla małych psów", { it.smallDogArea }),
}

data class ParkQuery(
    val text: String = "",
    val features: Set<Feature> = emptySet(),
)

data class RankedPark(val park: Park, val distance: Double?)

/**
 * What the list shows: matching parks, nearest first when there is an origin, otherwise
 * by place and name. The text matches name, locality, district and region, so typing
 * "rybnik" lists the whole city and "boguszowice" one district.
 */
fun rank(parks: List<Park>, query: ParkQuery, origin: LatLon?): List<RankedPark> {
    val needle = foldForSearch(query.text.trim())
    return parks.asSequence()
        .filter { p -> query.features.all { it.test(p) == true } }
        .filter { p ->
            needle.isEmpty() || listOfNotNull(p.name, p.locality, p.district, p.region)
                .any { foldForSearch(it).contains(needle) }
        }
        .map { RankedPark(it, origin?.let { o -> it.distanceFrom(o) }) }
        .sortedWith(
            if (origin != null) compareBy { it.distance }
            else compareBy<RankedPark>({ it.park.locality == null }, { it.park.locality?.let(::foldForSearch) }, { it.park.title })
        )
        .toList()
}

data class Locality(val label: String, val center: LatLon, val parkCount: Int)

/**
 * Every place people can pick when they will not share their location, centred on its
 * parks. Poland has dozens of places called Nowa Wieś, so a name that appears in more
 * than one voivodeship carries the voivodeship in its label.
 */
fun localities(parks: List<Park>): List<Locality> {
    val groups = parks.filter { it.locality != null }.groupBy { it.locality!! to it.region }
    val ambiguous = groups.keys.groupBy { it.first }.filterValues { it.size > 1 }.keys
    return groups.map { (key, ps) ->
        val (name, region) = key
        Locality(
            label = if (name in ambiguous && region != null) "$name ($region)" else name,
            center = LatLon(ps.map { it.lat }.average(), ps.map { it.lon }.average()),
            parkCount = ps.size,
        )
    }.sortedBy { foldForSearch(it.label) }
}
