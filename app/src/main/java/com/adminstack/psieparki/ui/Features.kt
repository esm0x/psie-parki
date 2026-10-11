package com.adminstack.psieparki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fence
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.SportsGymnastics
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adminstack.psieparki.data.Feature

val Feature.icon: ImageVector
    get() = when (this) {
        Feature.FENCED -> Icons.Outlined.Fence
        Feature.LIT -> Icons.Outlined.Lightbulb
        Feature.WATER -> Icons.Outlined.WaterDrop
        Feature.AGILITY -> Icons.Outlined.SportsGymnastics
        Feature.SMALL_DOGS -> Icons.Outlined.Pets
    }

/** The confirmed features of a park as small pills; unknown ones are simply absent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeaturePills(present: List<Feature>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        present.forEach { f ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(f.icon, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
    }
}
