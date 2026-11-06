package com.mazharansari.axion.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.PlanStatus
import com.mazharansari.axion.data.PlanStep
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/** v0.6 (Phase 2.2): the agent's plan as a live checklist — numbered steps, each pending / running / done / failed. */
@Composable
fun PlanCard(steps: List<PlanStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val p = P; val shape = RoundedCornerShape(14.dp)
    val done = steps.count { it.status == PlanStatus.DONE }
    val skipped = steps.count { it.status == PlanStatus.SKIPPED }
    Column(modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.cardBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plan", style = Type.small.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.weight(1f))
            Text("$done / ${steps.size}" + if (skipped > 0) " · $skipped skipped" else "", style = Type.small.copy(fontSize = 13.sp), color = p.text2)
        }
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
                    when (s.status) {
                        PlanStatus.DONE -> GkIcon(Gk.check, "done", 16.dp, Color(0xFF3FB950))
                        PlanStatus.FAILED -> GkIcon(Gk.close, "failed", 15.dp, p.danger)
                        PlanStatus.RUNNING -> PulseDot()
                        PlanStatus.SKIPPED -> GkIcon(Gk.close, "skipped", 13.dp, p.text3)
                        PlanStatus.PENDING -> Box(Modifier.size(9.dp).clip(CircleShape).border(1.5.dp, p.text3, CircleShape))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${s.text}", style = Type.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = when (s.status) { PlanStatus.PENDING, PlanStatus.SKIPPED -> p.text2; PlanStatus.FAILED -> p.danger; else -> p.text })
                    if (s.note.isNotBlank()) Text(s.note, style = Type.small.copy(fontSize = 12.5.sp), color = p.text2)
                }
            }
        }
    }
}
