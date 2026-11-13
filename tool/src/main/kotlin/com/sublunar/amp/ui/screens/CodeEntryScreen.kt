package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.Icons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity

/**
 * A short code typed on a number pad, laid out as the phone's dial pad is:
 * a TV's pairing code. Hands the code back as soon as the last digit is in;
 * back hands back null.
 */
class CodeEntryScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val digits: Int = 4,
) : AmpScreen<String?>(sealed) {

    @Composable
    override fun Content() {
        var code by remember { mutableStateOf("") }
        ListScreen(onBack = { goBack(null) }, title = title) {
            Box(Modifier.fillMaxWidth().padding(vertical = px(70)), contentAlignment = Alignment.Center) {
                // Each digit typed, then a dash for each still to come.
                val shown = (code.map { it.toString() } + List(digits - code.length) { "–" }).joinToString("  ")
                AppText(shown, pxSp(LightType.HEADING_PX))
            }
            Spacer(Modifier.weight(1f))
            for (row in KEYS.chunked(3)) {
                Row(Modifier.fillMaxWidth()) {
                    for (key in row) {
                        val press: (() -> Unit)? = when (key) {
                            "" -> null
                            DELETE -> { { code = code.dropLast(1) } }
                            else -> {
                                {
                                    code += key
                                    if (code.length == digits) goBack(code)
                                }
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(px(KEY_HEIGHT_PX))
                                .then(if (press != null) Modifier.appClickable(press) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (key) {
                                "" -> Unit
                                DELETE -> AppIcon(Icons.AutoMirrored.Filled.Backspace)
                                else -> AppText(key, pxSp(LightType.HEADING_PX))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(px(40)))
        }
    }

    private companion object {
        const val DELETE = "delete"
        val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", DELETE)
        const val KEY_HEIGHT_PX = 150
    }
}
