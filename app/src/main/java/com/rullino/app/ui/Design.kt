package com.rullino.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* Componenti comuni del design Figma: pulsanti, chip, intestazioni, schede, scatole e caricatori. */

private val ButtonShape = RoundedCornerShape(3.dp)
private val ButtonText = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.1.sp)

/** Pulsante principale arancione. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = ButtonShape,
    border = if (enabled) BorderStroke(1.dp, Color(0xFFFF8A66)) else null,
    colors = ButtonDefaults.buttonColors(
        containerColor = Palette.Safelight, contentColor = Color(0xFF1B0E09),
        disabledContainerColor = Color(0xFF3A2A24), disabledContentColor = Color(0xFF8A6A5E),
    ),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
) { ProvideTextStyle(ButtonText) { content() } }

/** Pulsante secondario scuro, in rilievo. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier.drawBehind {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF322E2B), Color(0xFF201E1C))))
    }, enabled = enabled, shape = ButtonShape,
    border = BorderStroke(1.dp, Color(0xFF56504B)),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color.Transparent, contentColor = Palette.Ink,
        disabledContainerColor = Color.Transparent, disabledContentColor = Palette.Faint,
    ),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
) { ProvideTextStyle(ButtonText) { content() } }

/** Etichetta tonda: stato di un rullino, ruolo, tipo di pellicola. */
@Composable
fun Chip(text: String, active: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = if (active) Palette.Safelight else Palette.Muted,
        fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 0.6.sp,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Palette.SafelightDark else Color.Transparent)
            .border(1.dp, if (active) Color(0xFFA6472C) else Color(0xFF4A4541), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Intestazione di pagina: sopratitolo arancione in mono e titolo grande. */
@Composable
fun PageHeading(kicker: String, title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(kicker.uppercase(), color = Palette.Safelight, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Text(title, color = Palette.Ink, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 34.sp)
        }
        trailing()
    }
}

/** Fondo delle pagine: piano scuro punteggiato. */
fun Modifier.dottedPage(): Modifier = drawBehind {
    drawRect(Color(0xFF171514))
    val step = 5.dp.toPx()
    var y = 0f
    while (y < size.height) {
        var x = 0f
        while (x < size.width) { drawCircle(Color(0xFF3B3532), radius = 0.6f, center = Offset(x, y)); x += step }
        y += step
    }
}

/** Scheda scura con bordo, come i pannelli del design. */
fun Modifier.card(selected: Boolean = false): Modifier = this
    .clip(RoundedCornerShape(4.dp))
    .background(Brush.verticalGradient(listOf(Color(0xFF262321), Color(0xFF1C1A19))))
    .border(1.dp, if (selected) Palette.Safelight else Color(0xFF3A3533), RoundedCornerShape(4.dp))

/** Colori delle scatole: arancione per le colore, crema per il bianco e nero, blu per le diapositive. */
enum class BoxTone(val face: Color, val ink: Color) {
    ORANGE(Color(0xFFDF5C36), Color(0xFF1B0E09)),
    CREAM(Color(0xFFD8CCB0), Color(0xFF1B1A18)),
    BLUE(Color(0xFF4C8FA6), Color(0xFF0E1A1F)),
}

fun toneFor(isBw: Boolean, slide: Boolean = false) = when {
    isBw -> BoxTone.CREAM
    slide -> BoxTone.BLUE
    else -> BoxTone.ORANGE
}

/** La scatola della pellicola, disegnata: fascia, marchio, nome, ISO. */
@Composable
fun FilmBox(name: String, iso: Int, tone: BoxTone, modifier: Modifier = Modifier, exposures: Int = 36, compact: Boolean = false) {
    Box(modifier.rotate(-1.5f)) {
        Box(Modifier.offset(5.dp, 5.dp).matchSize(compact).background(Color(0xCC000000)))
        Column(
            Modifier.matchSize(compact).background(tone.face).border(2.dp, tone.face.copy(alpha = 0.7f)),
        ) {
            Text("SLOW—FI", color = tone.ink.copy(alpha = 0.75f), fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 7.sp else 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 7.dp, top = 6.dp))
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Box(Modifier.fillMaxWidth().height(if (compact) 5.dp else 7.dp).background(Color(0xFF1B1A18)))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFFF2C46B)))
            if (!compact) Text(name.uppercase(), color = tone.ink, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                maxLines = 1, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            Text(iso.toString(), color = tone.ink, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 13.sp else 20.sp, modifier = Modifier.padding(start = 7.dp, top = if (compact) 4.dp else 0.dp))
            if (!compact) Text("35 mm · $exposures exp.", color = tone.ink.copy(alpha = 0.8f), fontSize = 9.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun Modifier.matchSize(compact: Boolean) = if (compact) size(66.dp, 76.dp) else size(132.dp, 150.dp)

/** Il caricatore 35 mm visto di lato: tappo, corpo con etichetta, coda di pellicola. */
@Composable
fun Cartridge(name: String, iso: Int, isBw: Boolean, exposures: Int, modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size((9 * scale).dp, (60 * scale).dp).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF272523), Color(0xFFB1AAA4), Color(0xFF3B3735)))),
        )
        Column(
            Modifier.size((80 * scale).dp, (54 * scale).dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF6B6662), Color(0xFF252321), Color(0xFF33302E), Color(0xFF807A75))))
                .border(1.dp, Color(0xFF0B0A0A), RoundedCornerShape(4.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(name.uppercase(), color = Palette.Safelight, fontFamily = Barlow, fontWeight = FontWeight.Bold,
                fontSize = (10 * scale).sp, letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 3.dp))
            Text(iso.toString(), color = Palette.Ink, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = (16 * scale).sp, lineHeight = (17 * scale).sp)
            Text((if (isBw) "B&W" else "COLOR") + " / $exposures", color = Palette.Muted, fontSize = (7 * scale).sp)
        }
        Box(
            Modifier.size((34 * scale).dp, (38 * scale).dp)
                .background(Color(0xFF302A24), RoundedCornerShape(topEnd = 2.dp, bottomEnd = 6.dp)),
        )
    }
}

/** Scritta incisa piccola, come sul corpo macchina. */
@Composable
fun EngravedNote(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF756E68)) {
    Text(text.uppercase(), color = color, fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 10.sp,
        letterSpacing = 1.4.sp, modifier = modifier)
}
