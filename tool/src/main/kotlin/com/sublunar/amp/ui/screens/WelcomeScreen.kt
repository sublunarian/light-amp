package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ChoiceButton
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.source.AddEntry

/**
 * First run: pick where the music comes from.
 *
 * The three kinds are offered as equals. An earlier version put a Subsonic login
 * form here with the phone's own music as a footnote and no mention of Plex at
 * all, which quietly told anyone who came for the other two that they'd
 * installed the wrong app. None of the three is the default.
 *
 * Each choice hands off to the same screen used to add that kind of source later
 * from Settings, so there is one setup flow per kind rather than two that can
 * drift apart.
 */
@Composable
fun WelcomeContent(
    /** The ways to add a source — Providers.addEntries, the same as Add Source. */
    entries: List<AddEntry>,
    /** What the kinds cover, under the choices. */
    notes: List<String>,
    onChoose: (AddEntry) -> Unit,
) {
    PlayerTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = px(80)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppText("amp", pxSp(LightType.HEADING_PX))
            AppText(
                "Where is your music?",
                pxSp(LightType.DETAIL_PX),
                dim = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = px(80)),
            )

            entries.forEach { entry -> ChoiceButton(entry.label.uppercase()) { onChoose(entry) } }

            Spacer(Modifier.height(px(60)))

            notes.forEach { note ->
                AppText(
                    "$note.",
                    pxSp(LightType.DETAIL_PX),
                    dim = true,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
