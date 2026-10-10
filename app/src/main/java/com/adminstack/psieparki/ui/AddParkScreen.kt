package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.SendResult
import com.adminstack.psieparki.data.Submission
import com.adminstack.psieparki.data.SubmissionType
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Dodaj wybieg": the place was picked on the map; here go the name and what is there.
 * Every field but the place is optional — a pin alone is already worth having, and a
 * form that asks too much does not get sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddParkScreen(
    point: LatLon,
    place: String?,
    onSend: suspend (Submission, String) -> SendResult,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    val features = remember { mutableStateMapOf<Feature, Boolean>() }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SendResult?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dodaj wybieg") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wstecz") }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(place ?: "Wybrane miejsce", fontWeight = FontWeight.Medium)
                        Text(
                            String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("Zmień") }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nazwa (jeśli ma)") },
                placeholder = { Text("np. Psi park na Bielanach") },
            )

            Text("Co tam jest?", style = MaterialTheme.typography.titleMedium)
            Feature.entries.forEach { f ->
                TriState(f, features[f]) { v -> if (v == null) features.remove(f) else features[f] = v }
            }

            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(300) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Uwagi (opcjonalnie)") },
                placeholder = { Text("np. wejście od ul. Leśnej, dużo cienia") },
            )

            Text(
                "Po automatycznym sprawdzeniu wybieg pojawi się w aplikacji z dopiskiem „niepotwierdzony”, " +
                    "dopóki nie potwierdzi go ktoś jeszcze. Nazwę i uwagi sprawdza automat — bez linków, " +
                    "reklam i danych osobowych. " +
                    "Zgłoszenie jest anonimowe i trafia do publicznego repozytorium z danymi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    sending = true
                    scope.launch {
                        result = onSend(
                            Submission(
                                type = SubmissionType.NEW,
                                lat = point.lat,
                                lon = point.lon,
                                name = name,
                                features = features.toMap(),
                                comment = comment,
                            ),
                            name.ifBlank { place?.let { "Wybieg, $it" } ?: "Nowy wybieg" },
                        )
                        sending = false
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Sprawdzam i wysyłam…")
                } else {
                    Text("Wyślij")
                }
            }
        }
    }

    result?.let { r ->
        val (title, text, done) = when (r) {
            is SendResult.Sent -> Triple(
                "Dziękujemy!",
                "Wybieg trafi na mapę po automatycznym sprawdzeniu, zwykle w ciągu kilku godzin. " +
                    "Status znajdziesz w Więcej → Moje zgłoszenia.",
                true,
            )
            is SendResult.Refused -> Triple("Nie możemy tego przyjąć", r.message, false)
            is SendResult.Failed -> Triple("Nie udało się wysłać", r.message, false)
        }
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { result = null; if (done) onDone() }) { Text(if (done) "Super" else "OK") }
            },
        )
    }
}

/** Tak / Nie / Nie wiem: "nie wiem" is the default and sends nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriState(feature: Feature, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(feature.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(feature.label, Modifier.weight(1f).padding(start = 10.dp))
        val options = listOf(true to "Tak", false to "Nie", null to "?")
        // Fixed and narrow: on a 360 dp phone the default width broke "Ogrodzony" in two.
        SingleChoiceSegmentedButtonRow(Modifier.width(150.dp)) {
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = value == v,
                    onClick = { onChange(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}
