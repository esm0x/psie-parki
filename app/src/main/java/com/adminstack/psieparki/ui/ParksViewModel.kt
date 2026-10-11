package com.adminstack.psieparki.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adminstack.psieparki.PsieParkiApp
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.Locality
import com.adminstack.psieparki.data.ParkQuery
import com.adminstack.psieparki.data.ParksState
import com.adminstack.psieparki.data.RankedPark
import com.adminstack.psieparki.data.localities
import com.adminstack.psieparki.data.rank
import com.adminstack.psieparki.data.SendResult
import com.adminstack.psieparki.data.Submission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What distances are measured from: the phone's position or a place the user picked. */
data class Origin(val point: LatLon, val label: String, val fromDevice: Boolean)

data class ListUi(
    val parks: ParksState = ParksState(),
    val ranked: List<RankedPark> = emptyList(),
    val origin: Origin? = null,
    val locating: Boolean = false,
    val locationFailed: Boolean = false,
    val query: ParkQuery = ParkQuery(),
)

class ParksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PsieParkiApp
    private val repo = app.parks

    private val origin = MutableStateFlow(
        app.settings.pickedPlace?.let { Origin(it.center, it.label, fromDevice = false) },
    )
    private val locating = MutableStateFlow(false)
    private val locationFailed = MutableStateFlow(false)
    private val query = MutableStateFlow(ParkQuery(features = app.settings.features))
    private val _favourites = MutableStateFlow(app.settings.favourites)
    val favourites: StateFlow<Set<String>> = _favourites

    val ui: StateFlow<ListUi> = combine(repo.state, origin, locating, locationFailed, query) { parks, o, loc, failed, q ->
        // Sorting 600+ parks per keystroke belongs off the main thread, but the result
        // is published from here: a flowOn() before stateIn() would fuse and emit UI
        // state from a worker thread (the Compose monkey test caught exactly that).
        val ranked = withContext(Dispatchers.Default) { rank(parks.parks, q, o?.point) }
        ListUi(parks = parks, ranked = ranked, origin = o, locating = loc, locationFailed = failed, query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUi())

    val places: StateFlow<List<Locality>> = repo.state
        .map { withContext(Dispatchers.Default) { localities(it.parks) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val helpers = app.helpers.state

    init {
        viewModelScope.launch { repo.load() }
        viewModelScope.launch { app.helpers.load() }
        if (app.location.hasPermission()) locate()
    }

    fun refresh() = viewModelScope.launch { repo.load(force = true) }

    fun locate() {
        if (locating.value) return
        viewModelScope.launch {
            locating.value = true
            val fix = app.location.current()
            locating.value = false
            locationFailed.value = fix == null
            if (fix != null) origin.value = Origin(fix, "Twoja lokalizacja", fromDevice = true)
        }
    }

    fun pickPlace(place: Locality) {
        app.settings.pickedPlace = place
        locationFailed.value = false
        origin.value = Origin(place.center, place.label, fromDevice = false)
    }

    fun setText(text: String) {
        query.value = query.value.copy(text = text)
    }

    fun toggle(feature: Feature) {
        val features = query.value.features.let { if (feature in it) it - feature else it + feature }
        app.settings.features = features
        query.value = query.value.copy(features = features)
    }

    fun park(id: String) = repo.park(id)

    fun toggleFavourite(id: String) {
        val f = _favourites.value.let { if (id in it) it - id else it + id }
        app.settings.favourites = f
        _favourites.value = f
        // Keep the "Ulubione" filter in step: unhearting a park while filtering removes it.
        query.value.onlyIds?.let { query.value = query.value.copy(onlyIds = f) }
    }

    fun toggleFavouritesOnly() {
        query.value = query.value.copy(onlyIds = if (query.value.onlyIds == null) _favourites.value else null)
    }

    // ---- submissions -------------------------------------------------------

    val submissions = app.submissions
    val sent = submissions.sent

    private val _inviteDismissed = MutableStateFlow(app.settings.inviteDismissed)
    val inviteDismissed: StateFlow<Boolean> = _inviteDismissed

    fun dismissInvite() {
        app.settings.inviteDismissed = true
        _inviteDismissed.value = true
    }

    suspend fun send(submission: Submission, title: String): SendResult {
        val result = submissions.send(submission, title)
        // Someone who added a park has seen what the invitation asks for.
        if (result is SendResult.Sent && submission.parkId == null) dismissInvite()
        return result
    }

    fun refreshStatuses() = viewModelScope.launch { submissions.refreshStatuses() }
}
