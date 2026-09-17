package com.neighborhood.safestreet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportBottomSheet(
    userLatitude: Double = 47.6062,
    userLongitude: Double = -122.3321,
    onDismiss: () -> Unit,
    onSubmit: (category: IncidentCategory, note: String, lat: Double, lon: Double) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(IncidentCategory.FIRE_SMOKE) }
    var noteText by remember { mutableStateOf("") }
    val maxChars = 500

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
        ) {
            Text(
                text = "Report Community Observation",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Privacy & UGC Guidelines Banner (Mandated by Plan & Google Play UGC policy)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AlertAmber.copy(alpha = 0.12f))
                    .border(1.dp, AlertAmber.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = AlertAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Report what you directly observe. Do NOT identify names, victims, suspects, phone numbers, or license plates. All community reports expire in exactly 24 hours.",
                        color = AlertAmber,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "OBSERVATION CATEGORY",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Category Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val categories = listOf(
                    IncidentCategory.FIRE_SMOKE,
                    IncidentCategory.VEHICLE_CRASH,
                    IncidentCategory.ROAD_HAZARD,
                    IncidentCategory.POLICE_ACTIVITY,
                    IncidentCategory.MEDICAL_RESPONSE,
                    IncidentCategory.HAZARD_CONDITION,
                    IncidentCategory.WEATHER_HAZARD
                )
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = { Text(category.displayName, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryBlue,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Factual Note Input
            OutlinedTextField(
                value = noteText,
                onValueChange = { if (it.length <= maxChars) noteText = it },
                label = { Text("Factual description (observable conditions)") },
                placeholder = { Text("e.g. Heavy black smoke visible rising near street intersection.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor = PrimaryBlue,
                    unfocusedLabelColor = TextSecondary
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approx location quantized to ~100m", color = TextMuted, fontSize = 11.sp)
                }
                Text("${noteText.length}/$maxChars", color = TextMuted, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSubmit(
                        selectedCategory,
                        noteText.ifBlank { "Observable ${selectedCategory.displayName} reported nearby." },
                        userLatitude,
                        userLongitude
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Publish Observation (Expires in 24h)", color = DarkBackground, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
