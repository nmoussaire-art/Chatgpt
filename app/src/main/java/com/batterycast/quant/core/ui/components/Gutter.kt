package com.batterycast.quant.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The horizontal inset all screen content sits at. */
val ScreenGutter: Dp = 20.dp

/**
 * A vertically stacked list item, inset by [ScreenGutter].
 *
 * Screens that carry a full-bleed horizontal chip row keep their `LazyColumn`'s own horizontal
 * `contentPadding` at zero and opt every other item back in through this helper. The inset has to
 * be applied per item rather than to the container because Compose rejects a negative padding
 * outright — `Modifier.padding(horizontal = (-20).dp)` throws at composition time, so a child
 * cannot cancel a parent's inset by subtracting it back off.
 */
fun LazyListScope.gutterItem(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key, contentType = contentType) {
        val itemScope = this
        Box(modifier = Modifier.padding(horizontal = ScreenGutter)) { itemScope.content() }
    }
}
