package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.sublunar.amp.ui.components.appClickable
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

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
    onSubsonic: () -> Unit,
    onPlex: () -> Unit,
    onJellyfin: () -> Unit,
    onLocal: () -> Unit,
) {
    PlayerTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(text = "amp", variant = LightTextVariant.Heading)
            LightText(
                text = "Where is your music?",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2f.gridUnitsAsDp()),
            )

            SourceChoice("SUBSONIC SERVER", onSubsonic)
            SourceChoice("PLEX SERVER", onPlex)
            SourceChoice("JELLYFIN SERVER", onJellyfin)
            SourceChoice("MUSIC ON THIS PHONE", onLocal)

            Spacer(Modifier.height(1.5f.gridUnitsAsDp()))

            // Named because "Subsonic" is the protocol, not the thing most
            // people run — someone with a Navidrome server needs telling that
            // the first option is theirs.
            //
            // Bandcamp leads the list: it is the only one of them that needs
            // nothing self-hosted, so it is the name most likely to tell a
            // reader that this app is for them after all.
            LightText(
                text = "Subsonic covers Bandcamp, Navidrome, Airsonic and Ampache.",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One of the three, all drawn identically so none reads as the default. */
@Composable
private fun SourceChoice(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .appClickable(onClick = onClick)
            .padding(vertical = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Button)
    }
}
