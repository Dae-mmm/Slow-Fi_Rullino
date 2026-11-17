package com.rullino.app.ui

import androidx.compose.material3.FilterChip
import com.rullino.app.R
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rullino.app.data.EmulsionEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import com.rullino.app.data.Shop
import kotlinx.coroutines.delay
import java.time.LocalTime

/** Il negozio-laboratorio anni '80: bancone, negoziante, scaffale e laboratorio. */
@Composable
fun ShopScreen(vm: AppViewModel) {
    val res = LocalContext.current.resources
    val emulsions by vm.emulsions.collectAsState()
    val rolls by vm.rolls.collectAsState()
    val inventory by vm.inventory.collectAsState()
    val coins by vm.shop.coins.collectAsState()
    val lastDaily by vm.shop.lastDaily.collectAsState()
    val line by vm.shopkeeperLine.collectAsState()
    val skip by vm.settings.skipLabWait.collectAsState()
    val dev by vm.settings.developerMode.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    val dailyAvailable = lastDaily != vm.shop.today()
    val toDeliver = rolls.filter { it.state == RollState.FINISHED && it.shotCount > 0 }
    fun ready(r: RollEntity) = r.state == RollState.DEVELOPED && ((dev && skip) || (r.readyAt ?: 0L) <= now)
    // Al laboratorio: in vasca o in attesa. Quelli pronti sono già tornati in Rullini.
    val atLab = rolls.filter { it.state == RollState.DEVELOPING || (it.state == RollState.DEVELOPED && !ready(it)) }

    LaunchedEffect(Unit) { if (vm.shopkeeperLine.value == null) vm.shopkeeperLine.value = greeting(res) }

    Row(Modifier.fillMaxSize().background(Color(0xFF1A1420))) {
        // ---- Il bancone con il negoziante ----
        Column(
            Modifier.width(300.dp).fillMaxHeight().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NeonSign("FOTO LAB")
            Spacer(Modifier.height(8.dp))
            SpeechBubble(line ?: greeting(res))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Shopkeeper(Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.95f))
            }
            // Bancone in legno
            Box(
                Modifier.fillMaxWidth().height(26.dp)
                    .background(Color(0xFF6B3E22), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .border(2.dp, Color(0xFF8A5530), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            )
        }

        // ---- Scaffali e laboratorio ----
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.coins, coins), color = Color(0xFFFFD36B), fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.stock_total, inventory.values.sum()), color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }

            item {
                ShopPanel(stringResource(R.string.daily_title)) {
                    Text(
                        if (dailyAvailable) stringResource(R.string.daily_available)
                        else stringResource(R.string.daily_taken),
                        color = if (dailyAvailable) Palette.Ink else Palette.Muted, fontSize = 13.sp,
                    )
                }
            }

            if (toDeliver.isNotEmpty() || atLab.isNotEmpty()) item {
                ShopPanel(stringResource(R.string.lab_title)) {
                    if (toDeliver.isNotEmpty()) {
                        PrimaryButton(onClick = { vm.deliverAll(toDeliver) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (toDeliver.size == 1) stringResource(R.string.develop_one) else stringResource(R.string.develop_all, toDeliver.size))
                        }
                    }
                    toDeliver.forEach { r ->
                        LabRow(r, stringResource(R.string.lab_to_develop)) {
                            // Come vuoi lo sviluppo: pull, normale o push.
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(-1, 0, 1, 2).forEach { p ->
                                    FilterChip(
                                        selected = r.push == p,
                                        onClick = { vm.setPush(r, p) },
                                        label = { Text(if (p > 0) "+$p" else if (p < 0) "−1" else "N", fontFamily = Mono, fontSize = 12.sp) },
                                    )
                                }
                            }
                        }
                    }
                    atLab.forEach { r ->
                        if (r.state == RollState.DEVELOPING) LabRow(r, stringResource(R.string.lab_in_tank)) {}
                        else LabRow(r, stringResource(R.string.lab_back, formatPickup(res, r.readyAt ?: now))) {
                            if (r.push != 0) Text(if (r.push > 0) "+${r.push}" else "−1", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                ShopPanel(stringResource(R.string.shelf_title, Shop.ROLL_PRICE)) {
                    if (emulsions.isEmpty()) Text(stringResource(R.string.shelf_empty), color = Palette.Muted)
                    emulsions.forEach { e ->
                        ShelfRow(
                            e, inventory[e.id] ?: 0,
                            canAfford = coins >= Shop.ROLL_PRICE,
                            freeToday = dailyAvailable,
                            onFree = { vm.claimDaily(e) },
                            onBuy = { vm.buy(e) },
                        )
                    }
                    Text(
                        stringResource(R.string.tokens_note),
                        color = Palette.Muted, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun greeting(res: android.content.res.Resources): String {
    val h = LocalTime.now().hour
    return when (h) {
        in 5..11 -> res.getString(R.string.greet_morning)
        in 12..17 -> res.getString(R.string.greet_afternoon)
        else -> res.getString(R.string.greet_evening)
    }
}

@Composable
private fun NeonSign(text: String) {
    Text(
        text,
        style = TextStyle(
            color = Color(0xFFFF4FA3),
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            letterSpacing = 4.sp,
            shadow = Shadow(color = Color(0xFFFF4FA3), offset = Offset.Zero, blurRadius = 18f),
        ),
    )
}

@Composable
private fun SpeechBubble(text: String) {
    Text(
        text,
        color = Color(0xFF1A1420),
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F1EA), RoundedCornerShape(10.dp))
            .padding(10.dp),
    )
}

@Composable
private fun ShopPanel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Plate).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), color = Color(0xFF7FE0D0), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
        content()
    }
}

@Composable
private fun LabRow(r: RollEntity, status: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(r.emulsionName, color = Palette.Ink)
            Text(stringResource(R.string.shot_status, r.shotCount, status), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
        }
        action()
    }
}

@Composable
private fun ShelfRow(e: EmulsionEntity, owned: Int, canAfford: Boolean, freeToday: Boolean, onFree: () -> Unit, onBuy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(e.name, color = Palette.Ink)
            Text(stringResource(R.string.iso_owned, e.iso, owned), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
        }
        if (freeToday) PrimaryButton(onClick = onFree) { Text(stringResource(R.string.free_today)) }
        SecondaryButton(enabled = canAfford, onClick = onBuy) { Text(stringResource(R.string.buy)) }
    }
}

/* ------------------------------------------------------------------ */
/* Negoziante: il tuo disegno se c'è, altrimenti il segnaposto          */
/* ------------------------------------------------------------------ */

@Composable
private fun Shopkeeper(modifier: Modifier) {
    val context = LocalContext.current
    val custom = remember {
        runCatching { context.assets.open("shop/negoziante.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    if (custom != null) {
        Image(
            bitmap = custom.asImageBitmap(),
            contentDescription = stringResource(R.string.shopkeeper),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None, // pixel nitidi
            modifier = modifier,
        )
    } else {
        PlaceholderShopkeeper(modifier)
    }
}

/**
 * Segnaposto in pixel art: occhiali tondi, baffoni, grembiule macchiato di chimici.
 * Ogni carattere è un pixel; '.' è trasparente.
 */
private val SPRITE = listOf(
    ".....hhhhhh.....",
    "....hhhhhhhh....",
    "....hsssssssh...",
    "...ssssssssss...",
    "...sgwgssgwgs...",
    "...ssssssssss...",
    "...smmmmmmmms...",
    "....smmssmms....",
    "....ssssssss....",
    ".....ssssss.....",
    "...bbaaaaaabb...",
    "..bbaaaaaaaabb..",
    "..bbaacaaaaabb..",
    "..ssaaaaacaass..",
    "..ssaaaaaaaass..",
    "....aacaaaaa....",
    "....aaaaaaaa....",
    "....kkk..kkk....",
)

private val SPRITE_COLORS = mapOf(
    'h' to Color(0xFF6A4026),
    's' to Color(0xFFE0A77A),
    'g' to Color(0xFF2A2A2A),
    'w' to Color(0xFFCFE8FF),
    'm' to Color(0xFF3A2414),
    'a' to Color(0xFFE9E6DF),
    'c' to Color(0xFFC98A2B),
    'b' to Color(0xFF3D6B8C),
    'k' to Color(0xFF2B1D14),
)

@Composable
private fun PlaceholderShopkeeper(modifier: Modifier) {
    Canvas(modifier) {
        val cols = SPRITE.maxOf { it.length }
        val rows = SPRITE.size
        val px = minOf(size.width / cols, size.height / rows).toInt().coerceAtLeast(1).toFloat()
        val left = (size.width - px * cols) / 2f
        val top = size.height - px * rows
        SPRITE.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val c = SPRITE_COLORS[ch] ?: return@forEachIndexed
                drawRect(c, Offset(left + x * px, top + y * px), Size(px, px))
            }
        }
    }
}
