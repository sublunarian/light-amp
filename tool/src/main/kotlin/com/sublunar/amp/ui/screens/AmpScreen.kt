package com.sublunar.amp.ui.screens

import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Every page Amp pushes. The volume rocker is LightOS's on every one of them,
 * casting or not: it turns the phone's media volume and shows its panel, and
 * while casting the output follows that level (see
 * PlaybackController's deviceVolume collector).
 */
abstract class AmpScreen<R>(sealed: SealedLightActivity) : SimpleLightScreen<R>(sealed)
