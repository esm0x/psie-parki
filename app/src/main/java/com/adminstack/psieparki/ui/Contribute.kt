package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.Park
import com.adminstack.psieparki.data.SendResult
import com.adminstack.psieparki.data.SentSubmission
import com.adminstack.psieparki.data.Submission
import com.adminstack.psieparki.data.SubmissionType
import com.adminstack.psieparki.data.Submissions
import com.adminstack.psieparki.data.reasonLabel
import com.adminstack.psieparki.data.statusLabel
import com.adminstack.psieparki.data.wire
import kotlinx.coroutines.launch

/** "niepotwierdzony" next to a park someone added in the app and nobody else confirmed. */
@Composable
fun UnconfirmedTag(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text("niepotwierdzony", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * "Byłeś tu?" on a park's page: one tap to confirm it exists, one to say it does not,
 * and one per feature nobody described yet. The cheapest possible way to help, put
 * where people already are, so most of the map gets filled in by people passing by.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisitCard(
    park: Park,
    answered: Set<String>,
    onSubmit: suspend (Submission, String) -> SendResult,
) {
    var done by remember(park.id) { mutableStateOf(answered) }
    var message by remember(park.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun send(s: Submission, marks: Set<String>) {
        busy = true
        scope.launch {
            when (val r = onSubmit(s, park.title)) {
                is SendResult.Sent -> { done = done + marks; message = "Dziękujemy! Uwzględnimy to po sprawdzeniu." }
                is SendResult.Refused -> message = r.message
                is SendResult.Failed -> message = r.message
            }
            busy = false
        }
    }

    val unknown = Feature.entries.filter { it.test(park) == null && it.wire !in done }
    val askExists = Submissions.ANSWER_EXISTS !in done
    if (!askExists && unknown.isEmpty() && message == null) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Pets, contentDescription = null)
                Text(
                    if (!park.confirmed) "Byłeś tu? Potwierdź ten wybieg" else "Byłeś tu? Pomóż innym psiarzom",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (!park.confirmed) {
                Text(
                    "Ten wybieg dodał użytkownik aplikacji i nikt go jeszcze nie potwierdził.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (askExists) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        send(Submission(SubmissionType.CONFIRM, parkId = park.id), setOf(Submissions.ANSWER_EXISTS))
                    }) { Text("Wybieg jest") }
                    OutlinedButton(enabled = !busy, onClick = {
                        send(Submission(SubmissionType.GONE, parkId = park.id), setOf(Submissions.ANSWER_EXISTS))
                    }) { Text("Nie ma go tu") }
                }
            }
            if (unknown.isNotEmpty()) {
                Text("Nikt jeszcze nie opisał:", style = MaterialTheme.typography.bodyMedium)
                unknown.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(f.icon, contentDescription = null, Modifier.size(18.dp))
                        Text(f.label, Modifier.weight(1f).padding(start = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(enabled = !busy, onClick = {
                                send(Submission(SubmissionType.FEATURES, parkId = park.id, features = mapOf(f to true)), setOf(f.wire))
                            }, label = { Text("Tak") })
                            AssistChip(enabled = !busy, onClick = {
                                send(Submission(SubmissionType.FEATURES, parkId = park.id, features = mapOf(f to false)), setOf(f.wire))
                            }, label = { Text("Nie") })
                        }
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The invitation on the list. Shown once until closed, and in a stronger version when
 * nothing is known near the person: an empty map is the best moment to ask.
 */
@Composable
fun InviteCard(nearestMeters: Double?, onAdd: () -> Unit, onDismiss: (() -> Unit)?) {
    val empty = nearestMeters == null || nearestMeters > 5_000
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (empty) "W promieniu 5 km nie znamy żadnego wybiegu" else "Psie Parki tworzą psiarze",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Zamknij")
                    }
                }
            }
            Text(
                if (empty) {
                    "Znasz jakiś w okolicy? Dodaj go — zajmie to minutę, a następny psiarz z Twojej okolicy już go znajdzie."
                } else {
                    "Każdy wybieg w aplikacji ktoś kiedyś zaznaczył. Znasz taki, którego tu nie ma? Dodaj go w minutę."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Outlined.AddLocationAlt, contentDescription = null, Modifier.size(18.dp))
                Text("Dodaj wybieg", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySubmissionsScreen(sent: List<SentSubmission>, onRefresh: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Moje zgłoszenia") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wstecz") }
            },
        )
    }) { padding ->
        if (sent.isEmpty()) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                Hint("Nie wysłałeś jeszcze żadnego zgłoszenia. Dodaj wybieg z mapy albo potwierdź jakiś na jego stronie.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sent, key = { it.nr }) { s ->
                ListItem(
                    headlineContent = { Text(s.title) },
                    supportingContent = {
                        Text(listOfNotNull(statusLabel(s.status), reasonLabel(s.reason), s.sentOn).joinToString(" · "))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}
